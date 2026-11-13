package studio.junecity.greenhouse.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.billingclient.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.AuthenticatedCloudGateway
import studio.junecity.greenhouse.cloud.PlayCatalog

/** The camera owner remains outside the dialog. Returning from Play never starts a stream. */
@Composable
internal fun VideoSubscriptionDialog(api: AuthenticatedCloudGateway, onDismiss: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<PlayCatalog?>(null) }
    var details by remember { mutableStateOf<ProductDetails?>(null) }
    var offer by remember { mutableStateOf<ProductDetails.SubscriptionOfferDetails?>(null) }
    var message by remember { mutableStateOf("Checking camera access…") }
    var busy by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(false) }
    var reconnect by remember { mutableIntStateOf(0) }
    var client by remember { mutableStateOf<BillingClient?>(null) }

    LaunchedEffect(api) {
        try {
            val access = api.videoCheckout()
            catalog = access.catalog.takeUnless { access.disabled }
            if (catalog == null) {
                message = if (access.disabled) "Camera access is disabled for this account."
                    else "Subscriptions are not available for this account yet."
                busy = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Could not check your account. Close and try again."; busy = false }
    }

    DisposableEffect(catalog, reconnect) {
        val plan = catalog
        if (plan == null) return@DisposableEffect onDispose {}
        var closed = false
        lateinit var billing: BillingClient
        val work = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))
        suspend fun reconcile(purchase: Purchase) {
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                message = "Payment is pending in Google Play."
                return
            }
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
            try {
                if (purchase.accountIdentifiers?.obfuscatedAccountId != plan.accountBinding) {
                    message = "This purchase belongs to a different greenhouse account."
                    return
                }
                api.verifyVideoPurchase(plan.productId, purchase.purchaseToken)
                verified = true
                message = "Purchase verified. Close to start Live View."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not verify your purchase. Use Restore purchase to retry." }
        }
        billing = BillingClient.newBuilder(activity.applicationContext)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .setListener { result, purchases ->
                if (!closed) work.launch {
                    when (result.responseCode) {
                        BillingClient.BillingResponseCode.OK -> purchases.orEmpty()
                            .firstOrNull { plan.productId in it.products }?.let { reconcile(it) }
                        BillingClient.BillingResponseCode.USER_CANCELED -> message = "Purchase canceled."
                        else -> message = "Google Play could not complete checkout. Try Restore purchase."
                    }
                    busy = false
                }
            }.build()
        client = billing
        busy = true
        details = null
        offer = null
        message = "Checking Google Play…"
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                if (!closed) { busy = false; message = "Google Play disconnected. Tap Restore purchase to reconnect." }
            }
            override fun onBillingSetupFinished(result: BillingResult) {
                if (closed) return
                work.launch {
                    try {
                        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                            message = "Google Play is unavailable on this phone."
                            return@launch
                        }
                        val owned = billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                            .setProductType(BillingClient.ProductType.SUBS).build())
                        if (owned.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            message = "Could not check purchases. Tap Restore purchase to retry."
                            return@launch
                        }
                        val purchase = owned.purchasesList.firstOrNull { plan.productId in it.products }
                        if (purchase != null) { reconcile(purchase); return@launch }
                        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(plan.productId)
                            .setProductType(BillingClient.ProductType.SUBS).build()
                        val products = billing.queryProductDetails(QueryProductDetailsParams.newBuilder()
                            .setProductList(listOf(product)).build())
                        val item = products.productDetailsList?.singleOrNull { it.productId == plan.productId }
                        val base = item?.subscriptionOfferDetails?.firstOrNull {
                            it.basePlanId == plan.basePlanId && it.offerId == null &&
                                it.pricingPhases.pricingPhaseList.singleOrNull()?.let { phase ->
                                    phase.billingPeriod == "P1M" && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
                                } == true
                        }
                        details = item
                        offer = base
                        message = if (base == null) "The test subscription is unavailable in Google Play."
                            else "Renews monthly until canceled in Google Play. For testing, select a Google Play test payment method."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Google Play is unavailable. Try Restore purchase." }
                    finally { if (!closed) busy = false }
                }
            }
        })
        onDispose { closed = true; work.coroutineContext[kotlinx.coroutines.Job]?.cancel(); billing.endConnection(); client = null }
    }

    VideoPurchaseSheet(message, offer?.pricingPhases?.pricingPhaseList?.singleOrNull()?.formattedPrice,
        busy, verified, catalog != null, onDismiss, { reconnect++ }) {
        val item = details ?: return@VideoPurchaseSheet
        val selected = offer ?: return@VideoPurchaseSheet
        val plan = catalog ?: return@VideoPurchaseSheet
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(item).setOfferToken(selected.offerToken).build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params))
            .setObfuscatedAccountId(plan.accountBinding).build()
        busy = true
        val result = client?.launchBillingFlow(activity, flow)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            busy = false
            message = "Could not open Google Play. Try Restore purchase."
        }
    }
}

@Composable
internal fun VideoPurchaseSheet(message: String, price: String?, busy: Boolean, verified: Boolean,
                               canRestore: Boolean, onDismiss: () -> Unit, onRestore: () -> Unit, onBuy: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = DashboardTokens.Panel,
        title = { Text("Premium Live View", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (price != null && !verified) Text("$price / month", color = DashboardTokens.Amber)
                Text(message, color = DashboardTokens.Muted)
                if (canRestore && !verified) TextButton(onClick = onRestore, enabled = !busy) { Text("Restore purchase") }
            }
        },
        confirmButton = {
            if (price != null && !verified) Button(onClick = onBuy, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = Color.Black)) {
                Text("Subscribe")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (verified || price == null) "Close" else "Maybe later") } }
    )
}
