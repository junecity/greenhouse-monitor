package studio.junecity.greenhouse.cloud

import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GreenhouseSubscriptionTest {
    private val base = GreenhousePlanState(emptyList(), 2, "greenhouse_extra_2", 600, "owner", true, true, 10)

    @Test fun currentAndScheduledPlansHaveSeparateEffectiveTimes() {
        assertFalse(base.appliesPlanChange(2, 1))
        assertTrue(base.copy(nextExtraSlots = 1).appliesPlanChange(2, 1))
        assertFalse(base.copy(nextExtraSlots = 1).appliesPlanChange(2, 0))
        assertTrue(base.copy(renews = false).appliesPlanChange(2, 0))
        assertFalse(base.copy(renews = false).appliesPlanChange(2, 1))
        assertTrue(base.copy(extraSlots = 3).appliesPlanChange(2, 3))
    }

    @Test fun changesReplaceOneSubscriptionAndCameraProductsAreIndependent() {
        assertEquals(ReplacementMode.DEFERRED, greenhouseReplacementMode(2, 1))
        assertEquals(ReplacementMode.CHARGE_PRORATED_PRICE, greenhouseReplacementMode(1, 2))
        assertThrows(IllegalArgumentException::class.java) { greenhouseReplacementMode(1, 0) }
        for (id in listOf("2", "camera_storage_monthly", "premium_monthly", "greenhouse_extra_01", "greenhouse_extra_0"))
            assertNull(id.greenhouseCapacity())
        assertEquals(10, greenhouseCapacityProduct(10).greenhouseCapacity())
    }

    @Test fun stateRetainsHouseIdentityAndServerEffectiveTimes() {
        val json = JSONObject("""{"houses":[{"id":"house-1","name":"North","free":false,"active":true,"pauseRequested":true,"pauseAtEpochMs":5000,"serverGreenhouseId":"cloud-1234"}],"extraSlots":2,"maxExtraSlots":10,"accountBinding":"owner","checkoutAvailable":true,"nextExtraSlots":1,"revision":4,"renews":true}""")
        val state = GreenhousePlanState.parse(json)
        val house = state.houses.single()
        assertEquals("cloud-1234", house.serverGreenhouseId)
        assertEquals(5000L, house.pauseAtEpochMs)
        assertTrue(house.active && house.pauseRequested)
        assertEquals(4L, state.revision)
        assertEquals(1, state.nextExtraSlots)
        assertNull(state.productId)
    }
}
