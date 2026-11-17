package studio.junecity.greenhouse.cloud

import android.app.Activity
import android.content.Context
import java.security.MessageDigest
import com.android.billingclient.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams

internal data class GreenhousePlanOffer(
    val details: ProductDetails,
    val offer: ProductDetails.SubscriptionOfferDetails,
    val monthlyPrice: String
)

internal fun Purchase.greenhousePlanProduct(): String? = products.firstOrNull { it.greenhouseCapacity() != null }

internal fun Purchase.greenhouseReceiptDigest() = MessageDigest.getInstance("SHA-256")
    .digest(purchaseToken.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A Play Store resubscription can omit the app-supplied account ID until backend acknowledgement. */
internal fun List<Purchase>.greenhouseReceipt(plan: GreenhousePlanState): Purchase? {
    val receipts = filter { it.greenhousePlanProduct() != null }
    val bound = receipts.filter { it.accountIdentifiers?.obfuscatedAccountId == plan.accountBinding }
    if (bound.isNotEmpty()) return bound.maxBy { it.purchaseTime }
    val unbound = receipts.filter { it.accountIdentifiers?.obfuscatedAccountId.isNullOrBlank() }
    return unbound.firstOrNull { it.greenhouseReceiptDigest() == plan.purchaseTokenDigest } ?: unbound.singleOrNull()
}

/** A local receipt can be used to replace only the Publisher-verified account subscription. */
internal fun GreenhousePlanState.ownsGreenhouseReceipt(purchase: Purchase): Boolean {
    val local = purchase.accountIdentifiers?.obfuscatedAccountId
    return !purchaseTokenDigest.isNullOrEmpty() && purchase.greenhouseReceiptDigest() == purchaseTokenDigest &&
        (local.isNullOrBlank() || local == accountBinding) && productId in purchase.products
}

/** Active upgrades preserve the renewal date; a reduction takes effect at that date. */
internal fun greenhouseReplacementMode(current: Int, wanted: Int): Int {
    require(current > 0 && wanted > 0 && current != wanted)
    return if (wanted > current) SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
    else SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
}

internal fun GreenhousePlanState.appliesPlanChange(from: Int, wanted: Int): Boolean =
    extraSlots == wanted || (wanted < from && nextExtraSlots == wanted) ||
        (wanted == 0 && extraSlots > 0 && renews == false && nextExtraSlots == null)

internal fun greenhousePlanFlow(plan: GreenhousePlanState, selected: GreenhousePlanOffer, current: Purchase?): BillingFlowParams {
    val wanted = requireNotNull(selected.details.productId.greenhouseCapacity())
    require(plan.checkoutAvailable && plan.accountBinding.isNotBlank() && wanted in 1..plan.maxExtraSlots && wanted != plan.extraSlots)
    val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(selected.details)
        .setOfferToken(selected.offer.offerToken)
    val flow = BillingFlowParams.newBuilder().setObfuscatedAccountId(plan.accountBinding)
    if (plan.extraSlots > 0) {
        require(current != null && !current.isSuspended && current.purchaseState == Purchase.PurchaseState.PURCHASED)
        require(plan.ownsGreenhouseReceipt(current))
        val replacement = SubscriptionProductReplacementParams.newBuilder()
            .setOldProductId(requireNotNull(plan.productId))
            .setReplacementMode(greenhouseReplacementMode(plan.extraSlots, wanted)).build()
        product.setSubscriptionProductReplacementParams(replacement)
        flow.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(current.purchaseToken).build())
    }
    return flow.setProductDetailsParamsList(listOf(product.build())).build()
}

/** The Play account or the account's subscription may change while the price sheet is displayed. */
internal fun GreenhousePlanState.stillCanBuy(
    displayed: GreenhousePlanState, expectedReceipt: Purchase?, purchases: List<Purchase>
): Boolean {
    if (!checkoutAvailable || accountBinding.isBlank() || accountBinding != displayed.accountBinding ||
        extraSlots != displayed.extraSlots || productId != displayed.productId || nextExtraSlots != displayed.nextExtraSlots ||
        renews != displayed.renews || purchaseTokenDigest != displayed.purchaseTokenDigest || licenseTest != displayed.licenseTest) return false
    val receipts = purchases.filter { it.greenhousePlanProduct() != null }
    if (extraSlots == 0) return expectedReceipt == null && receipts.isEmpty()
    val owned = receipts.greenhouseReceipt(this) ?: return false
    return owned.purchaseToken == expectedReceipt?.purchaseToken && !owned.isSuspended &&
        owned.pendingPurchaseUpdate == null && owned.purchaseState == Purchase.PurchaseState.PURCHASED &&
        ownsGreenhouseReceipt(owned)
}

internal interface GreenhousePlanStore {
    val changes: ReceiveChannel<Int>
    suspend fun owned(): List<Purchase>
    suspend fun offer(extra: Int): GreenhousePlanOffer?
    fun launch(activity: Activity, plan: GreenhousePlanState, selected: GreenhousePlanOffer, current: Purchase?): Int
}

internal class GreenhousePlanBilling(context: Context) : GreenhousePlanStore, AutoCloseable {
    // Purchases are re-queried on resume. Signals never grant local access.
    override val changes = Channel<Int>(Channel.CONFLATED)
    private val client = BillingClient.newBuilder(context.applicationContext)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, _ -> changes.trySend(result.responseCode) }
        .build()
    private var connection: CompletableDeferred<BillingResult>? = null

    private suspend fun connect() {
        if (client.isReady) return
        val pending = connection?.takeUnless { it.isCompleted } ?: CompletableDeferred<BillingResult>().also {
            connection = it
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { it.complete(result) }
                override fun onBillingServiceDisconnected() {
                    it.completeExceptionally(IllegalStateException("Play disconnected"))
                }
            })
        }
        val result = withTimeoutOrNull(15_000L) { pending.await() }
        if (result == null && connection === pending) connection = null
        check(result?.responseCode == BillingClient.BillingResponseCode.OK)
    }

    override suspend fun owned(): List<Purchase> {
        connect()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS)
            .includeSuspendedSubscriptions(true).build()
        val result = client.queryPurchasesAsync(params)
        check(result.billingResult.responseCode == BillingClient.BillingResponseCode.OK)
        return result.purchasesList
    }

    override suspend fun offer(extra: Int): GreenhousePlanOffer? {
        val productId = greenhouseCapacityProduct(extra)
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS).build()
        val response = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        check(response.billingResult.responseCode == BillingClient.BillingResponseCode.OK)
        val item = response.productDetailsList?.singleOrNull { it.productId == productId } ?: return null
        for (offer in item.subscriptionOfferDetails.orEmpty()) {
            if (offer.basePlanId != "monthly" || offer.offerId != null) continue
            val phase = offer.pricingPhases.pricingPhaseList.singleOrNull() ?: continue
            if (phase.billingPeriod == "P1M" && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING) {
                return GreenhousePlanOffer(item, offer, phase.formattedPrice)
            }
        }
        return null
    }

    override fun launch(activity: Activity, plan: GreenhousePlanState, selected: GreenhousePlanOffer, current: Purchase?) =
        client.launchBillingFlow(activity, greenhousePlanFlow(plan, selected, current)).responseCode

    override fun close() {
        connection?.cancel()
        client.endConnection()
        changes.close()
    }
}
