package studio.junecity.greenhouse.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.billingclient.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.AuthenticatedCloudGateway
import studio.junecity.greenhouse.cloud.PlayCatalog
import studio.junecity.greenhouse.cloud.ArchivePurchaseWorker
import studio.junecity.greenhouse.cloud.VideoCheckout
import studio.junecity.greenhouse.cloud.purchaseFor

/** The camera owner remains outside the dialog. Returning from Play never starts a stream. */
@Composable
internal fun VideoSubscriptionDialog(api: AuthenticatedCloudGateway, archiveHouse: String? = null, onDismiss: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var catalog by remember { mutableStateOf<PlayCatalog?>(null) }
    var details by remember { mutableStateOf<ProductDetails?>(null) }
    var offer by remember { mutableStateOf<ProductDetails.SubscriptionOfferDetails?>(null) }
    var message by remember { mutableStateOf("Checking camera access…") }
    var busy by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(false) }
    var reconnect by remember { mutableIntStateOf(0) }
    var client by remember { mutableStateOf<BillingClient?>(null) }

    LaunchedEffect(api, archiveHouse) {
        try {
            val access = if (archiveHouse == null) api.videoCheckout() else {
                val access = api.archiveRequest(archiveHouse, "access")
                if (access.optBoolean("active")) {
                    verified = true
                    busy = false
                    message = "Cloud recording is active for your account."
                    return@LaunchedEffect
                }
                val offer = access.optJSONObject("checkout")
                VideoCheckout(false, offer?.let {
                    PlayCatalog(it.getString("productId"), it.getString("basePlanId"), it.getString("accountBinding"), it.optBoolean("licenseTest"))
                })
            }
            catalog = access.catalog.takeUnless { access.disabled }
            if (catalog == null) {
                message = if (access.disabled) "Camera access is disabled for this account."
                    else "Subscriptions are not available for this account yet."
                busy = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Could not check your account. Close and try again."; busy = false }
    }

    DisposableEffect(catalog, reconnect, lifecycle) {
        val plan = catalog
        if (plan == null) return@DisposableEffect onDispose {}
        var closed = false
        lateinit var billing: BillingClient
        val work = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))
        suspend fun reconcile(purchase: Purchase) {
            details = null
            offer = null
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                archiveHouse?.let { ArchivePurchaseWorker.schedule(activity, it, plan.accountBinding) }
                message = "Payment is pending in Google Play. Access starts when payment clears."
                return
            }
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
            try {
                if (archiveHouse == null) api.verifyVideoPurchase(plan.productId, purchase.purchaseToken)
                else {
                    ArchivePurchaseWorker.schedule(activity, archiveHouse, plan.accountBinding)
                    api.verifyArchivePurchase(archiveHouse, purchase.purchaseToken)
                    ArchivePurchaseWorker.finish(activity, plan.accountBinding)
                }
                verified = true
                message = if (archiveHouse == null) "Purchase verified. Close to start Live View." else "Cloud recording is available for this greenhouse."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not verify your purchase. Use Restore purchase to retry." }
        }
        billing = BillingClient.newBuilder(activity.applicationContext)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .setListener { result, purchases ->
                if (!closed) work.launch {
                    when (result.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            val purchase = purchases.orEmpty().purchaseFor(plan.productId, plan.accountBinding)
                            if (purchase != null) reconcile(purchase) else reconnect++
                        }
                        BillingClient.BillingResponseCode.USER_CANCELED -> message = "Purchase canceled."
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> reconnect++
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
                        val purchase = owned.purchasesList.purchaseFor(plan.productId, plan.accountBinding)
                        if (purchase != null) { reconcile(purchase); return@launch }
                        if (owned.purchasesList.any { plan.productId in it.products }) {
                            message = "Your Google Play subscription belongs to another greenhouse account."
                            return@launch
                        }
                        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(plan.productId)
                            .setProductType(BillingClient.ProductType.SUBS).build()
                        val products = billing.queryProductDetails(QueryProductDetailsParams.newBuilder()
                            .setProductList(listOf(product)).build())
                        if (products.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            message = "Could not load the price from Google Play. Try again."
                            return@launch
                        }
                        val item = products.productDetailsList?.singleOrNull { it.productId == plan.productId }
                        val base = item?.subscriptionOfferDetails?.firstOrNull {
                            it.basePlanId == plan.basePlanId && it.offerId == null &&
                                it.pricingPhases.pricingPhaseList.singleOrNull()?.let { phase ->
                                    phase.billingPeriod == "P1M" && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
                                } == true
                        }
                        details = item
                        offer = base
                        message = when {
                            base == null -> "This subscription is unavailable for this Google Play account."
                            plan.licenseTest -> "For this test purchase, select a Google Play test payment method."
                            else -> "Renews monthly until you cancel in Google Play."
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Google Play is unavailable. Try Restore purchase." }
                    finally { if (!closed) busy = false }
                }
            }
        })
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused && !verified) {
                // Requery owned purchases when returning from Play, including delayed payments.
                reconnect++
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            closed = true
            lifecycle.removeObserver(observer)
            work.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            billing.endConnection()
            client = null
        }
    }

    VideoPurchaseSheet(message, offer?.pricingPhases?.pricingPhaseList?.singleOrNull()?.formattedPrice,
        busy, verified, catalog != null, onDismiss, { reconnect++ },
        title = if (archiveHouse == null) "Premium Live View" else "Cloud recording",
        description = archiveHouse?.let { "Save up to 24 one-minute clips per day across your greenhouses. Each clip stays available for seven days." }) {
        val item = details ?: return@VideoPurchaseSheet
        val selected = offer ?: return@VideoPurchaseSheet
        val plan = catalog ?: return@VideoPurchaseSheet
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(item).setOfferToken(selected.offerToken).build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params))
            .setObfuscatedAccountId(plan.accountBinding).build()
        busy = true
        archiveHouse?.let { ArchivePurchaseWorker.schedule(activity, it, plan.accountBinding) }
        val result = client?.launchBillingFlow(activity, flow)
        if (result?.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            reconnect++
        } else if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            busy = false
            message = "Could not open Google Play. Try Restore purchase."
        }
    }
}

@Composable
internal fun VideoPurchaseSheet(message: String, price: String?, busy: Boolean, verified: Boolean,
                               canRestore: Boolean, onDismiss: () -> Unit, onRestore: () -> Unit,
                               title: String = "Premium Live View", description: String? = null, onBuy: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = DashboardTokens.Panel,
        title = { Text(title, color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (price != null && !verified) Text("$price / month", color = DashboardTokens.Amber)
                if (description != null && !verified) Text(description, color = Color.White)
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
