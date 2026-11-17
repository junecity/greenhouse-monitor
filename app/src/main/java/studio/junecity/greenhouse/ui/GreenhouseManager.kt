package studio.junecity.greenhouse.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.*
import studio.junecity.greenhouse.model.*
import studio.junecity.greenhouse.settings.*

private data class PlanPurchase(val extras: Int, val activateId: String? = null, val activateName: String? = null, val createLocal: Boolean = false, val manageExisting: Boolean = false)
private data class HouseRow(val id: String, val name: String, val local: GreenhouseConfig?, val remote: PlanHouse?)

@Composable
internal fun GreenhouseManager(prefs: AppPreferences, sensors: List<GoveeSensor>, editable: Boolean,
    onPrefs: ((AppPreferences) -> AppPreferences) -> Unit, onSelect: () -> Unit, onCreated: () -> Unit,
    owner: CloudEnrollment? = SecureSettingsStore(LocalContext.current).cloudEnrollment(),
    suppliedApi: AuthenticatedCloudGateway? = null) {
    val context = LocalContext.current
    val api = remember(owner, suppliedApi) {
        suppliedApi ?: owner?.let { o -> CloudRuntime.configurationOrNull()?.let { gatewayForEnrollment(context, it, o) } }
    }
    key(owner, api) {
        val secure = remember(context) { SecureSettingsStore(context) }
        val cache = remember(owner) { owner?.let { GreenhousePlanCache(context, it) } }
        var plan by remember { mutableStateOf<GreenhousePlanState?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var purchase by remember { mutableStateOf<PlanPurchase?>(null) }
        var remove by remember { mutableStateOf<HouseRow?>(null) }
        var pause by remember { mutableStateOf<HouseRow?>(null) }
        var bindHere by remember { mutableStateOf<PlanHouse?>(null) }
        val scope = rememberCoroutineScope()
        val canBuy = owner?.role == CloudDeviceRole.ViewerClient && owner.deviceSecret == null
        val latestPreferences by rememberUpdatedState(onPrefs)
        val currentPlan = plan
        val rows = prefs.greenhouses.map { house ->
            val id = cache?.canonicalId(house, plan) ?: house.id
            HouseRow(id, house.name, house, plan?.houses?.find { it.id == id })
        }.toMutableList()
        val listed = rows.mapTo(mutableSetOf()) { it.id }
        plan?.houses?.filterNot { it.id in listed }?.forEach { house -> rows += HouseRow(house.id, house.name, null, house) }

        fun accept(updated: GreenhousePlanState) {
            check(secure.cloudEnrollment() == owner) { "Enrollment changed" }
            if (plan != null && plan!!.revision > updated.revision) return
            plan = updated
            cache?.record(updated)
            if (cache != null) latestPreferences { cache.applyTo(it) }
        }

        fun runAction(block: suspend () -> Unit) {
            if (busy) return
            message = null
            busy = true
            scope.launch {
                try { check(secure.cloudEnrollment() == owner) { "Enrollment changed" }; block() }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) {
                    message = when {
                        e is CloudApiException && e.statusCode == 403 -> "Manage this greenhouse from the phone signed in to its account."
                        e is CloudApiException && e.statusCode == 409 -> "The greenhouse plan changed. Refresh the plan and try again."
                        else -> "Could not update your plan. Check your connection and try again."
                    }
                } finally { busy = false }
            }
        }

        suspend fun refresh() {
            if (api != null && owner != null) accept(GreenhousePlanCache.refresh(context, owner, api))
            else message = "Sign in to manage additional greenhouses."
        }

        fun checkout(wanted: PlanPurchase) {
            when {
                !canBuy -> message = "Open Settings → General → Manage greenhouses on the client phone to change the monthly plan."
                plan?.checkoutAvailable != true -> message = "Greenhouse subscriptions are unavailable for this account."
                wanted.extras > (plan?.maxExtraSlots ?: 0) -> message = "You’ve reached the largest available greenhouse plan."
                else -> purchase = wanted
            }
        }

        suspend fun activate(id: String, name: String, createLocal: Boolean) {
            val gateway = api ?: error("Missing greenhouse account")
            val entry = plan?.houses?.find { it.id == id }
            val updated = try {
                if (entry == null) gateway.activatePlanHouse(id, name) else gateway.resumePlanHouse(id)
            } catch (e: CloudApiException) {
                if (e.statusCode == 409 && e.message == "greenhouse_plan_required") {
                    checkout(PlanPurchase((plan?.extraSlots ?: 0) + 1, id, name, createLocal))
                    return
                }
                throw e
            }
            accept(updated)
            check(updated.houses.any { it.id == id && it.active }) { "Activation pending" }
            if (createLocal) {
                latestPreferences { previous ->
                    val current = previous.greenhouses.firstOrNull { cache?.canonicalId(it, updated) == id || it.id == id }
                    val added = if (current != null) previous.greenhouses.map {
                        if (it.id == current.id) it.copy(name=name,billingHouseId=id) else it
                    } else previous.greenhouses + GreenhouseConfig(id=id, name=name, billingHouseId=id)
                    val saved = previous.copy(greenhouses=added, activeGreenhouseId=current?.id ?: id)
                    cache?.applyTo(saved) ?: saved
                }
                cache?.completeAddition(id)
                onCreated()
            } else if (updated.houses.first { it.id == id }.pauseAtEpochMs > 0) {
                message = "Google Play still has a lower capacity scheduled. Manage your subscription to keep this greenhouse monitoring."
            } else message = "$name is monitoring."
        }

        LaunchedEffect(api, owner) {
            try { refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "Plan unavailable. Connect to check your subscription." }
        }

        val offered = purchase
        if (offered != null && api != null) GreenhousePlanCheckoutDialog(api, offered.extras,
            onVerified = { updated ->
                accept(updated)
                purchase = null
                if (offered.activateId != null && offered.activateName != null && updated.extraSlots >= offered.extras) {
                    runAction { activate(offered.activateId, offered.activateName, offered.createLocal) }
                }
            },
            onDismiss = { purchase = null; runAction { refresh() } }, manageExisting = offered.manageExisting)

        val unboundPrimary = rows.firstOrNull { row ->
            owner.isStandaloneClient() && row.local?.id == "main" && row.local.billingHouseId == null &&
                row.remote == null && plan != null && cache?.isLocalLegacy(row.local) == false
        }
        bindHere?.let { house ->
            AlertDialog(onDismissRequest={ bindHere=null }, containerColor=DashboardTokens.PanelRaised,
                title={ Text("Use ${house.name} on this phone?", color=Color.White) },
                text={ Text("Choose this if your phone and sensors are in that greenhouse. Future readings from its local floor plan will use this greenhouse’s monitoring access.", color=DashboardTokens.Muted) },
                confirmButton={ TextButton(onClick={
                    bindHere=null
                    runAction {
                        val updated = checkNotNull(api).getGreenhousePlan()
                        val confirmed = updated.houses.single { it.id == house.id && it.active && it.serverGreenhouseId == null && it.hostingGreenhouseId == null }
                        accept(updated)
                        latestPreferences { previous ->
                            val existing = previous.greenhouses.firstOrNull { cache?.canonicalId(it,updated) == confirmed.id }
                                ?: unboundPrimary?.local?.let { target -> previous.greenhouses.find { it.id == target.id } }
                            val selected = existing?.copy(name=confirmed.name,billingHouseId=confirmed.id)
                                ?: GreenhouseConfig(id=confirmed.id,name=confirmed.name,billingHouseId=confirmed.id)
                            val layouts = if (existing == null) previous.greenhouses + selected else
                                previous.greenhouses.map { if (it.id == selected.id) selected else it }
                            checkNotNull(cache).applyTo(previous.copy(activeGreenhouseId=selected.id,greenhouses=layouts))
                        }
                        onSelect()
                    }
                }) { Text("Use this greenhouse") } },
                dismissButton={ TextButton(onClick={ bindHere=null }) { Text("Cancel") } })
        }
        pause?.let { row ->
            AlertDialog(onDismissRequest = { pause = null }, containerColor=DashboardTokens.PanelRaised,
                title = { Text("Pause ${row.name}?", color=Color.White) },
                text = { Text("Monitoring and alerts continue through your paid period. Your readings, sensors and layout will be kept. Confirm the lower monthly plan in Google Play.", color=DashboardTokens.Muted) },
                confirmButton = { TextButton(onClick = {
                    pause = null
                    runAction {
                        val updated = checkNotNull(api).pausePlanHouse(row.id)
                        accept(updated)
                        checkout(PlanPurchase(updated.houses.count { it.active && !it.free && !it.grandfathered && !it.pauseRequested }))
                    }
                }) { Text("Review in Google Play") } },
                dismissButton = { TextButton(onClick = { pause = null }) { Text("Keep monitoring") } })
        }
        remove?.let { row ->
            AlertDialog(onDismissRequest = { remove = null }, containerColor=DashboardTokens.PanelRaised,
                title = { Text("Delete ${row.name}?", color=Color.White) },
                text = { Text("This removes its floor plan. Its sensor history is kept. You can manage your monthly plan separately.", color=DashboardTokens.Muted) },
                confirmButton = { TextButton(onClick = {
                    remove = null
                    runAction {
                        if (row.remote != null) accept(checkNotNull(api).removePlanHouse(row.id))
                        else if (row.local?.billingHouseId != null) error("Check current plan")
                        row.local?.let { old -> latestPreferences { it.removeFloorPlan(old.id, sensors) } }
                    }
                }) { Text("Delete greenhouse", color=DashboardTokens.Danger) } },
                dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (currentPlan != null) {
                val count = currentPlan.extraSlots
                Text(if (count == 0) "One greenhouse included" else "One included · $count paid " + if (count == 1) "space" else "spaces",
                    color=DashboardTokens.Muted, fontSize=14.sp)
            }
            if (owner?.role == CloudDeviceRole.ViewerClient && !owner.isStandaloneClient()) {
                TextButton(enabled=!busy, onClick={
                    secure.withCurrentEnrollment(owner) { secure.saveCloudEnrollment(owner.copy(localSensors=true)) }
                    onSelect()
                }) { Text("Open standalone greenhouses on this phone",color=DashboardTokens.Amber) }
            }
            rows.forEach { row ->
                Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        if (row.local != null) TextButton(onClick={
                            latestPreferences { it.copy(activeGreenhouseId = row.local.id) }
                            onSelect()
                        }) { Text(row.name, fontWeight=FontWeight.Bold, color=if (prefs.activeGreenhouseId==row.local.id) DashboardTokens.Amber else Color.White) }
                        else Text(row.name, fontWeight=FontWeight.Bold, color=Color.White)
                        val label = when {
                            row.local?.awaitingPlanCheck == true -> "Choose greenhouse access"
                            row.remote?.active == false || row.local?.isMonitoring() == false -> "Paused"
                            row.remote?.free == true -> "Included"
                            row.remote?.grandfathered == true || (row.local != null && cache?.isLocalLegacy(row.local) == true && row.remote == null) -> "Existing greenhouse"
                            (row.remote?.pauseAtEpochMs ?: 0) > 0 -> "Pauses ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.remote!!.pauseAtEpochMs))}"
                            row.remote?.pauseRequested == true -> "Pause selected"
                            row.remote?.active == true -> "Monitoring"
                            else -> null
                        }
                        label?.let { Text(it, color=DashboardTokens.Muted, fontSize=13.sp) }
                    }
                    val entry = row.remote
                    val serverId = entry?.serverGreenhouseId ?: entry?.hostingGreenhouseId
                    if (owner?.role == CloudDeviceRole.ViewerClient && serverId != null &&
                        (owner.isStandaloneClient() || owner.greenhouseId != serverId)) {
                        TextButton(enabled=!busy, onClick={
                            secure.withCurrentEnrollment(owner) { secure.saveCloudEnrollment(owner.copy(greenhouseId=serverId, localSensors=false)) }
                            onSelect()
                        }) { Text("View server", color=DashboardTokens.Amber) }
                    } else if (owner.isStandaloneClient() && row.local == null && entry?.active == true && serverId == null) {
                        TextButton(enabled=!busy, onClick={ bindHere=entry }) { Text("Use here", color=DashboardTokens.Amber) }
                    }
                    if (entry != null && !entry.free && !entry.grandfathered && (canBuy || !entry.active)) {
                        val action = when {
                            !entry.active -> "Resume"
                            entry.pauseAtEpochMs > 0 -> "Keep monitoring"
                            entry.pauseRequested -> "Undo pause"
                            else -> "Pause"
                        }
                        TextButton(enabled=!busy, onClick={
                            when {
                                entry.pauseAtEpochMs > 0 && entry.active -> checkout(PlanPurchase(checkNotNull(plan).extraSlots, manageExisting=true))
                                !entry.active || entry.pauseRequested -> runAction { activate(row.id, row.name, false) }
                                else -> pause=row
                            }
                        }) { Text(action,color=DashboardTokens.Amber) }
                    } else if (entry == null && row.local?.isMonitoring() == false && row != unboundPrimary && api != null) {
                        TextButton(enabled=!busy, onClick={ runAction { activate(row.id, row.name, false) } }) { Text("Activate") }
                    }
                    if (editable && row.local != null && prefs.greenhouses.size > 1)
                        TextButton(enabled=!busy, onClick={ remove=row }, modifier=Modifier.semantics { contentDescription = "Delete ${row.name}" }) {
                            Text("Delete", color=DashboardTokens.Danger)
                        }
                }
            }
            if (editable) Button(enabled=!busy && currentPlan != null, onClick={
                runAction {
                    if (plan != null && cache != null) {
                        val names = rows.map { it.name }.toSet()
                        val next = generateSequence(2) { it+1 }.first { "Greenhouse $it" !in names }
                        if (unboundPrimary != null) {
                            val name = if (plan?.houses?.any { it.name == unboundPrimary.name } == true)
                                "Greenhouse $next" else unboundPrimary.name
                            activate(unboundPrimary.id, name, true)
                            return@runAction
                        }
                        val (id, name) = cache.pendingAddition("Greenhouse $next")
                        activate(id, name, true)
                    }
                }
            }, colors=ButtonDefaults.buttonColors(containerColor=DashboardTokens.Amber, contentColor=Color.Black)) {
                Text(if (unboundPrimary == null) "Add greenhouse" else "Activate this greenhouse")
            }
            if (api != null && currentPlan != null && canBuy && currentPlan.checkoutAvailable) {
                if (!editable && currentPlan.extraSlots < currentPlan.maxExtraSlots) TextButton(enabled=!busy, onClick={
                    checkout(PlanPurchase(currentPlan.extraSlots+1))
                }) { Text("Add greenhouse capacity") }
                val requested = currentPlan.houses.count { it.active && it.pauseRequested }
                if (requested > 0 && currentPlan.nextExtraSlots == null) TextButton(enabled=!busy, onClick={
                    checkout(PlanPurchase(currentPlan.houses.count { it.active && !it.free && !it.grandfathered && !it.pauseRequested }))
                }) { Text("Finish plan change in Google Play") }
                val used = currentPlan.houses.count { it.active && !it.free && !it.grandfathered }
                if (currentPlan.extraSlots > used) TextButton(enabled=!busy, onClick={ checkout(PlanPurchase(used)) }) {
                    Text("Remove unused monthly capacity")
                }
                TextButton(enabled=!busy, onClick={ checkout(PlanPurchase(currentPlan.extraSlots.coerceAtLeast(1), manageExisting=currentPlan.extraSlots > 0)) }) {
                    Text("Manage or restore subscription")
                }
            }
            message?.let { Text(it,color=DashboardTokens.Muted,fontSize=14.sp) }
            if (api != null && (message != null || currentPlan==null))
                TextButton(enabled=!busy, onClick={ runAction { refresh() } }) { Text("Refresh plan") }
        }
    }
}
