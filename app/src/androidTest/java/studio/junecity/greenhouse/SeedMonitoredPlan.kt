package studio.junecity.greenhouse

import android.content.Context
import studio.junecity.greenhouse.cloud.GreenhousePlanState
import studio.junecity.greenhouse.cloud.PlanHouse
import studio.junecity.greenhouse.model.AppPreferences
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.GreenhousePlanCache

/** Install the verified account projection a signed-in fixture's collector normally receives. */
internal fun seedMonitoredPlan(context: Context, owner: CloudEnrollment, prefs: AppPreferences = AppPreferences()) {
    GreenhousePlanCache.resetLocalCollectorOwner(context,owner)
    val cache = GreenhousePlanCache(context,owner)
    cache.record(GreenhousePlanState(
        houses=prefs.greenhouses.mapIndexed { index, house ->
            PlanHouse(cache.canonicalId(house,null),house.name,active=true,free=index == 0)
        },
        extraSlots=prefs.greenhouses.size-1, productId=null,
        paidThroughEpochMs=System.currentTimeMillis()+86_400_000,
        accountBinding="fixture",checkoutAvailable=false,licenseTest=true,maxExtraSlots=10
    ))
}
