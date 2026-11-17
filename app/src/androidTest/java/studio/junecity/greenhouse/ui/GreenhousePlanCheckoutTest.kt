package studio.junecity.greenhouse.ui

import android.app.Activity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.junecity.greenhouse.MainActivity
import studio.junecity.greenhouse.cloud.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class GreenhousePlanCheckoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val binding = "greenhouse-owner"
    private val result = AtomicReference<GreenhousePlanState?>()
    private val verifiedTokens = CopyOnWriteArrayList<String>()
    private var playCapture: Purchase? = null
    private val otherPhone = AtomicReference<JSONObject?>()

    private fun purchase(extra: Int, token: String, owner: String? = binding, state: Int = 0) = Purchase(
        JSONObject().put("productId", greenhouseCapacityProduct(extra)).put("obfuscatedAccountId", owner)
            .put("purchaseState", state).put("purchaseToken", token).toString(), "fixture")

    private fun state(extra: Int, next: Int? = null, token: String = "old") =
        JSONObject().put("houses", JSONArray()).put("extraSlots", extra).put("maxExtraSlots", 10)
            .put("accountBinding", binding).put("licenseTest", true).put("checkoutAvailable", true)
            .put("renews", true).put("paidThroughEpochMs", 1_900_000_000_000)
            .putOpt("purchaseTokenDigest", if (extra > 0) purchase(extra, token).greenhouseReceiptDigest() else null)
            .putOpt("productId", if (extra > 0) greenhouseCapacityProduct(extra) else null).putOpt("nextExtraSlots", next)

    private fun product(extra: Int): GreenhousePlanOffer {
        val phase = JSONObject().put("billingPeriod", "P1M").put("priceAmountMicros", 4990000L)
            .put("formattedPrice", "TEST 4.99").put("priceCurrencyCode", "USD").put("recurrenceMode", 1)
        val offer = JSONObject().put("basePlanId", "monthly").put("offerIdToken", "offer-$extra")
            .put("pricingPhases", JSONArray().put(phase))
        val raw = JSONObject().put("productId", greenhouseCapacityProduct(extra)).put("type", "subs")
            .put("title", "Test greenhouse plan").put("name", "Test greenhouse plan").put("skuDetailsToken", "fixture")
            .put("subscriptionOfferDetails", JSONArray().put(offer))
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        val details = constructor.newInstance(raw.toString())
        val actual = details.subscriptionOfferDetails!!.single()
        return GreenhousePlanOffer(details, actual, actual.pricingPhases.pricingPhaseList.single().formattedPrice)
    }

    private inner class Store(var receipts: List<Purchase>) : GreenhousePlanStore {
        override val changes = Channel<Int>(Channel.CONFLATED)
        var purchased: Purchase? = null
        var cancel = false
        override suspend fun owned() = receipts
        override suspend fun offer(extra: Int) = product(extra)
        override fun launch(activity: Activity, plan: GreenhousePlanState, selected: GreenhousePlanOffer, current: Purchase?): Int {
            greenhousePlanFlow(plan, selected, current) // Exercise real Play parameter validation.
            playCapture = current
            if (!cancel) receipts = listOf(requireNotNull(purchased))
            changes.trySend(if (cancel) BillingResponseCode.USER_CANCELED else BillingResponseCode.OK)
            return BillingResponseCode.OK
        }
    }

    private fun show(desired: Int, store: Store, before: JSONObject, after: JSONObject = before, manageExisting: Boolean = false) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = if (request.url.encodedPath == "/v1/greenhouse-plan/verify") {
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val token = body.getString("purchaseToken")
                verifiedTokens.add(token)
                if (token == "new") after else before
            } else { assertEquals("/v1/greenhouse-plan", request.url.encodedPath); otherPhone.get() ?: before }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(data.toString().toResponseBody()).build()
        }.build()
        val api = AuthenticatedCloudGateway(CloudApiConfig("https://fixture.invalid", "owner-phone"), { "fixture" }, client = client)
        compose.activityRule.scenario.onActivity { it.setContent { GreenhousePlanCheckoutContent(api, desired, store, { data -> result.set(data) }, {}, manageExisting) } }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(12_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun firstPaidHouseShowsPlayPriceAndVerifiesOnlyAfterPayment() {
        val store = Store(emptyList()).apply { purchased = purchase(1, "new") }
        show(1, store, state(0), state(1))
        awaitText("TEST 4.99 / month total")
        assertTrue(verifiedTokens.isEmpty())
        compose.onNodeWithText("Subscribe").performClick()
        compose.waitUntil(12_000) { result.get()?.extraSlots == 1 }
        assertNull(playCapture)
        assertEquals(listOf("new"), verifiedTokens.toList())
    }

    @Test fun downgradeReplacesOwnedSubscriptionAndKeepsPaidCapacityUntilRenewal() {
        val original = purchase(2, "old")
        val store = Store(listOf(original)).apply { purchased = purchase(2, "new") }
        show(1, store, state(2), state(2, next = 1))
        awaitText("Change plan")
        compose.onNodeWithText("Change plan").performClick()
        compose.waitUntil(12_000) { result.get()?.nextExtraSlots == 1 }
        assertEquals("old", playCapture?.purchaseToken)
        assertEquals(2, result.get()?.extraSlots)
        assertEquals(listOf("old", "new"), verifiedTokens.toList())
    }

    @Test fun anotherAccountsPurchaseCannotStartASecondBill() {
        show(2, Store(listOf(purchase(1, "other", owner = "other-owner"))), state(1))
        awaitText("Open Google Play with the account that owns this greenhouse plan, then tap Restore purchase.")
        compose.onNodeWithText("Change plan").assertDoesNotExist()
        assertTrue(verifiedTokens.isEmpty())
    }

    @Test fun pendingPaymentNeverOpensAPaidHouseUntilRestored() {
        val store = Store(listOf(purchase(1, "pending", state = 4)))
        show(1, store, state(0), state(1))
        awaitText("Google Play is processing your payment. Your plan updates after approval.")
        assertNull(result.get())
        assertTrue(verifiedTokens.isEmpty())
        store.receipts = listOf(purchase(1, "new"))
        compose.onNodeWithText("Restore purchase").performClick()
        compose.waitUntil(12_000) { result.get()?.extraSlots == 1 }
    }

    @Test fun upgradeCreditsPriorPurchaseAndVerifiesNewCapacity() {
        val store = Store(listOf(purchase(1, "old"))).apply { purchased = purchase(2, "new") }
        show(2, store, state(1), state(2))
        awaitText("Change plan")
        compose.onNodeWithText("Change plan").performClick()
        compose.waitUntil(12_000) { result.get()?.extraSlots == 2 }
        assertEquals("old", playCapture?.purchaseToken)
        assertEquals(listOf("old", "new"), verifiedTokens.toList())
    }

    @Test fun canceledCheckoutLeavesHouseUnbilledAndCanBeRetried() {
        show(1, Store(emptyList()).apply { cancel = true }, state(0))
        awaitText("Subscribe")
        compose.onNodeWithText("Subscribe").performClick()
        awaitText("Checkout canceled.")
        awaitText("Subscribe")
        assertTrue(verifiedTokens.isEmpty())
        assertNull(result.get())
    }

    @Test fun oldFirstPurchaseOfferCannotStartAnotherBillAfterSecondPhoneSubscribes() {
        val store = Store(emptyList())
        show(2, store, state(0))
        awaitText("Subscribe")
        otherPhone.set(state(1))
        compose.onNodeWithText("Subscribe").performClick()
        awaitText("Open Google Play with the account that owns this greenhouse plan, then tap Restore purchase.")
        assertTrue(verifiedTokens.isEmpty())
        assertNull(store.purchased)
        assertNull(result.get())
    }

    @Test fun currentPlanManagementCanReverseAPlayCancellation() {
        show(1, Store(listOf(purchase(1, "old"))), state(1).put("renews", false), manageExisting = true)
        awaitText("Manage in Google Play")
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
        assertEquals(listOf("old"), verifiedTokens.toList())
        assertNull(result.get())
    }

    @Test fun restoredPlayStoreReceiptWithoutLocalBindingCanReplaceOnlyTheVerifiedToken() {
        val receipt = purchase(1, "old", owner = null)
        val store = Store(listOf(receipt)).apply { purchased = purchase(2, "new", owner = null) }
        show(2, store, state(1), state(2, token = "new"))
        awaitText("Change plan")
        compose.onNodeWithText("Change plan").performClick()
        compose.waitUntil(12_000) { result.get()?.extraSlots == 2 }
        assertEquals("old", playCapture?.purchaseToken)
    }

    @Test fun staleSameSkuReceiptCannotReplaceActiveSubscription() {
        show(2, Store(listOf(purchase(1, "superseded"))), state(1, token = "current"))
        awaitText("Google Play is updating your plan. Check again to see your confirmed capacity.")
        compose.onNodeWithText("Change plan").assertDoesNotExist()
    }

    @Test fun lastPaidGreenhouseOffersPlayCancellationWithoutRemovingHistories() {
        show(0, Store(emptyList()), state(1))
        awaitText("Manage in Google Play")
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
        assertNull(result.get())
        assertTrue(verifiedTokens.isEmpty())
    }
}
