package studio.junecity.greenhouse.cloud

import org.json.JSONObject

internal data class PlanHouse(
    val id: String,
    val name: String,
    val active: Boolean,
    val free: Boolean,
    val pauseAtEpochMs: Long = 0,
    val grandfathered: Boolean = false,
    val serverGreenhouseId: String? = null,
    val pauseRequested: Boolean = false,
    val hostingGreenhouseId: String? = null
)

internal data class GreenhousePlanState(
    val houses: List<PlanHouse>,
    val extraSlots: Int,
    val productId: String?,
    val paidThroughEpochMs: Long,
    val accountBinding: String,
    val checkoutAvailable: Boolean,
    val licenseTest: Boolean,
    val maxExtraSlots: Int,
    val nextExtraSlots: Int? = null,
    val renews: Boolean? = null,
    val revision: Long = 0,
    val purchaseTokenDigest: String? = null
) {
    companion object {
        fun parse(json: JSONObject): GreenhousePlanState {
            val rows = json.getJSONArray("houses")
            val houses = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                PlanHouse(row.getString("id"), row.getString("name"), row.getBoolean("active"),
                    row.getBoolean("free"), row.optLong("pauseAtEpochMs"), row.optBoolean("grandfathered"),
                    row.optString("serverGreenhouseId").takeUnless { row.isNull("serverGreenhouseId") || it.isBlank() },
                    row.optBoolean("pauseRequested"),
                    row.optString("hostingGreenhouseId").takeUnless { row.isNull("hostingGreenhouseId") || it.isBlank() })
            }
            require(houses.distinctBy { it.id }.size == houses.size)
            return GreenhousePlanState(
                houses, json.getInt("extraSlots"),
                json.optString("productId").takeUnless { json.isNull("productId") || it.isBlank() },
                json.optLong("paidThroughEpochMs"), json.optString("accountBinding").takeUnless { json.isNull("accountBinding") }.orEmpty(),
                json.optBoolean("checkoutAvailable"), json.optBoolean("licenseTest"), json.getInt("maxExtraSlots"),
                if (json.isNull("nextExtraSlots")) null else json.getInt("nextExtraSlots"),
                if (json.isNull("renews")) null else json.getBoolean("renews"), json.optLong("revision"),
                json.optString("purchaseTokenDigest").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            )
        }
    }
}

internal suspend fun AuthenticatedCloudGateway.getGreenhousePlan() = greenhousePlanRequest()

internal suspend fun AuthenticatedCloudGateway.activatePlanHouse(houseId: String, name: String) =
    greenhousePlanRequest("activate", JSONObject().put("houseId", houseId).put("name", name))

internal suspend fun AuthenticatedCloudGateway.renamePlanHouse(houseId: String, name: String) =
    greenhousePlanRequest("rename", JSONObject().put("houseId", houseId).put("name", name))

internal suspend fun AuthenticatedCloudGateway.pausePlanHouse(houseId: String) =
    greenhousePlanRequest("pause", JSONObject().put("houseId", houseId))

internal suspend fun AuthenticatedCloudGateway.resumePlanHouse(houseId: String) =
    greenhousePlanRequest("resume", JSONObject().put("houseId", houseId))

internal suspend fun AuthenticatedCloudGateway.removePlanHouse(houseId: String) =
    greenhousePlanRequest("remove", JSONObject().put("houseId", houseId))

internal suspend fun AuthenticatedCloudGateway.verifyGreenhousePlan(productId: String, purchaseToken: String) =
    greenhousePlanRequest("verify", JSONObject().put("productId", productId).put("purchaseToken", purchaseToken))

internal fun greenhouseCapacityProduct(extra: Int) = "greenhouse_extra_" + extra

internal fun String.greenhouseCapacity(): Int? {
    val number = removePrefix("greenhouse_extra_").toIntOrNull() ?: return null
    return number.takeIf { it > 0 && this == greenhouseCapacityProduct(it) }
}
