package studio.junecity.greenhouse.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.AppPreferences
import studio.junecity.greenhouse.model.GreenhouseConfig

/** Account authority never travels in customer backups or in viewer sensor snapshots. */
internal class GreenhousePlanCache(private val context: Context, private val owner: CloudEnrollment) {
    private val store: SharedPreferences = context.getSharedPreferences("greenhouse_plan_access", Context.MODE_PRIVATE)
    private val scope = JSONArray(listOf(owner.tenantId, owner.deviceId,
        if (owner.isStandaloneClient()) "standalone" else owner.greenhouseId)).toString()
    private val legacyKey = "legacy:$scope"
    private val snapshotKey = "plan:$scope"

    // A restarted purchase or network retry claims exactly the same physical greenhouse.
    fun pendingAddition(name: String): Pair<String, String> = synchronized(lock) {
        val key = "pending:$scope"
        val saved = store.getString(key, null)?.let(::JSONArray)
        if (saved != null) saved.getString(0) to saved.getString(1)
        else {
            val id = UUID.randomUUID().toString()
            check(store.edit().putString(key, JSONArray(listOf(id, name)).toString()).commit())
            id to name
        }
    }

    fun completeAddition(id: String) = synchronized(lock) {
        val key = "pending:$scope"
        if (store.getString(key,null)?.let { JSONArray(it).getString(0) } == id) store.edit().remove(key).apply()
    }

    private val nameKey = "names:$scope"

    fun queueName(house: GreenhouseConfig) = synchronized(lock) {
        val changes = JSONObject(store.getString(nameKey, "{}")!!)
        changes.put(canonicalId(house,null),house.name)
        store.edit().putString(nameKey,changes.toString()).apply()
    }

    private suspend fun syncNames(api: AuthenticatedCloudGateway, initial: GreenhousePlanState): GreenhousePlanState {
        var current = initial
        val changes = synchronized(lock) { JSONObject(store.getString(nameKey, "{}")!!) }
        for (id in changes.keys()) {
            val name = changes.getString(id)
            val target = current.houses.firstOrNull { it.id == id || it.serverGreenhouseId == id }
            if (target != null && target.name != name) current = api.renamePlanHouse(target.id,name)
            synchronized(lock) {
                val pending = JSONObject(store.getString(nameKey, "{}")!!)
                if (pending.optString(id) == name) {
                    pending.remove(id)
                    store.edit().putString(nameKey,pending.toString()).apply()
                }
            }
        }
        return current
    }

    fun watch(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == snapshotKey) onChange()
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        return { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun hasMigrationRecord() = store.contains(legacyKey)

    /** Capture only pre-upgrade plans on their collecting installation, before any backup import or new edit. */
    fun rememberPreviousInstallation(prefs: AppPreferences, savedOnThisPhone: Boolean) = synchronized(lock) {
        if (hasMigrationRecord()) return@synchronized
        val collectsHere = owner.isStandaloneClient() || owner.role == CloudDeviceRole.GreenhouseServer
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val migrationKey = if (owner.isStandaloneClient()) "migrated:standalone" else "migrated:server"
        val eligible = collectsHere && savedOnThisPhone && !store.contains(migrationKey) &&
            packageInfo.lastUpdateTime > packageInfo.firstInstallTime
        val ids = if (eligible) prefs.greenhouses.filter { it.billingHouseId == null }.map { it.id } else emptyList()
        val editor = store.edit().putStringSet(legacyKey, ids.toSet())
        if (collectsHere) editor.putBoolean(migrationKey, true)
        editor.apply()
    }

    private fun projection(): JSONObject? = store.getString(snapshotKey, null)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }

    fun canonicalId(house: GreenhouseConfig, plan: GreenhousePlanState?): String {
        house.billingHouseId?.let { return it }
        if (house.id != "main") return house.id
        if (owner.isStandaloneClient()) return UUID.nameUUIDFromBytes(scope.toByteArray()).toString()
        return plan?.houses?.firstOrNull { it.serverGreenhouseId == owner.greenhouseId }?.id
            ?: projection()?.optString("serverMain").orEmpty().ifBlank { owner.greenhouseId.ifBlank { house.id } }
    }

    fun isLocalLegacy(house: GreenhouseConfig): Boolean =
        house.billingHouseId == null && house.id in store.getStringSet(legacyKey, emptySet()).orEmpty()

    fun applyTo(prefs: AppPreferences): AppPreferences {
        val snapshot = projection()
        val entries = snapshot?.optJSONObject("houses")
        val pauses = snapshot?.optJSONObject("pauses")
        val seen = mutableSetOf<String>()
        return prefs.copy(greenhouses = prefs.greenhouses.map { house ->
            val id = canonicalId(house, null)
            // A copied or imported alias cannot cover two separate local layouts.
            val duplicate = !seen.add(id)
            val deadline = when {
                duplicate -> 0L
                entries?.has(id) == true -> entries.getLong(id).takeUnless { it == Long.MAX_VALUE }
                isLocalLegacy(house) -> null
                house.billingHouseId != null -> 0L
                house.id == "main" && snapshot == null && !owner.isStandaloneClient() -> null
                // A viewer with an older server may see layout-only plans lacking a plan ID.
                house.id != "main" && owner.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient() -> null
                else -> 0L
            }
            house.copy(
                billingHouseId = house.billingHouseId ?: id.takeIf { owner.isStandaloneClient() && entries?.has(id) == true },
                monitoringUntilEpochMs = deadline,
                awaitingPlanCheck = owner.isStandaloneClient() && entries?.has(id) != true && deadline == 0L && house.billingHouseId == null,
                scheduledPauseAtEpochMs = pauses?.optLong(id)?.takeIf { it > 0 }
            )
        })
    }

    private val scheduleKey = "schedule:" + ownerKey(owner)

    fun scheduledPauses(): List<PlanHouse> {
        val snapshot = store.getString(scheduleKey, null)?.let(::JSONObject) ?: return emptyList()
        val houses = snapshot.getJSONArray("scheduled")
        return List(houses.length()) { index ->
            val house = houses.getJSONObject(index)
            PlanHouse(house.getString("id"), house.getString("name"), active = true, free = false,
                pauseAtEpochMs = house.getLong("at"))
        }
    }

    fun record(plan: GreenhousePlanState, authenticatedAs: CloudEnrollment = owner) {
        SecureSettingsStore(context).withCurrentEnrollment(authenticatedAs) {
            if (owner != authenticatedAs && localCollector(context, authenticatedAs) != owner) return@withCurrentEnrollment
            synchronized(lock) {
                val previous = projection()
                if ((previous?.optLong("revision") ?: -1L) > plan.revision) return@synchronized
                if (owner.role == CloudDeviceRole.ViewerClient) {
                    val prior = store.getString(scheduleKey, null)?.let(::JSONObject)?.optLong("revision") ?: -1L
                    if (plan.revision >= prior) {
                        val scheduled = JSONArray()
                        plan.houses.filter { it.active && it.pauseAtEpochMs > 0 }.forEach {
                            scheduled.put(JSONObject().put("id", it.id).put("name", it.name).put("at", it.pauseAtEpochMs))
                        }
                        store.edit { putString(scheduleKey, JSONObject().put("revision", plan.revision)
                            .put("scheduled", scheduled).toString()) }
                    }
                }
                val houses = JSONObject()
                // An account removal wins over legacy access on an older collecting installation.
                previous?.optJSONObject("houses")?.keys()?.forEach { houses.put(it, 0L) }
                val pauses = JSONObject()
                plan.houses.forEach { house ->
                    val fromThisSource = if (owner.isStandaloneClient())
                        house.hostingGreenhouseId == null && (house.serverGreenhouseId == null || house.serverGreenhouseId != house.id)
                    else house.serverGreenhouseId == owner.greenhouseId || house.hostingGreenhouseId == owner.greenhouseId ||
                        (house.id == owner.greenhouseId && house.serverGreenhouseId == null)
                    val end = when {
                        !fromThisSource -> 0L
                        !house.active -> 0L
                        house.free || house.grandfathered -> Long.MAX_VALUE
                        house.pauseAtEpochMs > 0 -> minOf(house.pauseAtEpochMs, plan.paidThroughEpochMs)
                        else -> plan.paidThroughEpochMs
                    }
                    houses.put(house.id, end)
                    if (fromThisSource && house.active && house.pauseAtEpochMs > 0) pauses.put(house.id, house.pauseAtEpochMs)
                }
                val main = plan.houses.firstOrNull { it.serverGreenhouseId == owner.greenhouseId && owner.greenhouseId.isNotBlank() }
                val snapshot = JSONObject().put("houses", houses).put("revision", plan.revision).put("serverMain", main?.id).put("pauses",pauses)
                store.edit().putString(snapshotKey, snapshot.toString()).apply()
            }
        }
    }

    companion object {
        private val lock = Any()

        private fun ownerKey(owner: CloudEnrollment) = JSONArray(listOf(owner.tenantId,owner.deviceId)).toString()

        // The original standalone data directory is shared across UI modes and cleared at new enrollment.
        // Only its owning client may use it while viewing another greenhouse. A blank tenant cannot
        // authorize a background collector under a different source.
        fun localCollector(context: Context, current: CloudEnrollment): CloudEnrollment? {
            if (current.role != CloudDeviceRole.ViewerClient) return null
            if (current.tenantId.isBlank()) return current.takeIf { it.isStandaloneClient() }
            val store = context.getSharedPreferences("greenhouse_plan_access", Context.MODE_PRIVATE)
            val expected = ownerKey(current)
            synchronized(lock) {
                val saved = store.getString("standaloneOwner", null)
                if (saved != null && saved != expected) return null
                if (!current.isStandaloneClient()) return if (saved == expected && current.tenantId.isNotBlank())
                    current.copy(localSensors=true) else null
                if (saved == expected) return current
            }
            return SecureSettingsStore(context).withCurrentEnrollment(current) {
                synchronized(lock) {
                    val saved = store.getString("standaloneOwner",null)
                    if (saved != null && saved != expected) null else {
                        store.edit().putString("standaloneOwner",expected).apply()
                        current
                    }
                }
            }
        }

        fun resetLocalCollectorOwner(context: Context, owner: CloudEnrollment) {
            SecureSettingsStore(context).withCurrentEnrollment(owner) {
                synchronized(lock) {
                    val edit = context.getSharedPreferences("greenhouse_plan_access", Context.MODE_PRIVATE).edit()
                    if (owner.isStandaloneClient()) edit.putString("standaloneOwner",ownerKey(owner))
                    else edit.remove("standaloneOwner")
                    edit.apply()
                }
            }
        }

        suspend fun refresh(context: Context, owner: CloudEnrollment, api: AuthenticatedCloudGateway): GreenhousePlanState {
            val cache = GreenhousePlanCache(context, owner)
            var plan = api.getGreenhousePlan()
            check(SecureSettingsStore(context).cloudEnrollment() == owner) { "Enrollment changed" }
            val collecting = localCollector(context,owner)
            if (plan.houses.isEmpty() && collecting == owner && owner.isStandaloneClient()) {
                val primary = AppPreferencesStore(context, "_standalone").load().greenhouses.first()
                plan = api.activatePlanHouse(cache.canonicalId(primary, plan), primary.name)
            }
            cache.record(plan)
            val localCache = collecting?.takeIf { it != owner }?.let { GreenhousePlanCache(context,it) }
            localCache?.record(plan,authenticatedAs=owner)
            // Keep the latest verified access available even if a separate label write needs retrying.
            val renamed = try {
                val current = cache.syncNames(api,plan)
                localCache?.syncNames(api,current) ?: current
            }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { plan }
            if (renamed !== plan) {
                cache.record(renamed)
                localCache?.record(renamed,authenticatedAs=owner)
            }
            return renamed
        }
    }
}
