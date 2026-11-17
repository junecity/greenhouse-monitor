package studio.junecity.greenhouse.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.cloud.*

/** Shows the total monthly price for exactly this capacity. Only the verified backend result completes a change. */
@Composable
internal fun GreenhousePlanCheckoutDialog(
    api: AuthenticatedCloudGateway,
    desiredExtra: Int,
    onVerified: (GreenhousePlanState) -> Unit,
    onDismiss: () -> Unit,
    manageExisting: Boolean = false
) {
    val activity = LocalActivity.current ?: return
    val billing = remember(activity, api) { GreenhousePlanBilling(activity) }
    DisposableEffect(billing) { onDispose { billing.close() } }
    GreenhousePlanCheckoutContent(api, desiredExtra, billing, onVerified, onDismiss, manageExisting)
}

@Composable
internal fun GreenhousePlanCheckoutContent(
    api: AuthenticatedCloudGateway,
    desiredExtra: Int,
    billing: GreenhousePlanStore,
    onVerified: (GreenhousePlanState) -> Unit,
    onDismiss: () -> Unit,
    manageExisting: Boolean = false
) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val checkoutScope = rememberCoroutineScope()
    val verified by rememberUpdatedState(onVerified)
    var refresh by remember(api, desiredExtra) { mutableIntStateOf(0) }
    var plan by remember(api, desiredExtra) { mutableStateOf<GreenhousePlanState?>(null) }
    var owned by remember(api, desiredExtra) { mutableStateOf<Purchase?>(null) }
    var offer by remember(api, desiredExtra) { mutableStateOf<GreenhousePlanOffer?>(null) }
    var busy by remember(api, desiredExtra) { mutableStateOf(true) }
    var message by remember(api, desiredExtra) { mutableStateOf("") }
    var messageFromPlay by remember(api, desiredExtra) { mutableStateOf("") }
    DisposableEffect(lifecycle) {
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) left = true
            if (left && event == Lifecycle.Event.ON_RESUME) { left = false; refresh++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(billing) {
        for (code in billing.changes) {
            messageFromPlay = when (code) {
                BillingClient.BillingResponseCode.OK, BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ""
                BillingClient.BillingResponseCode.USER_CANCELED -> "Checkout canceled."
                else -> "Google Play could not finish checkout."
            }
            refresh++
        }
    }
    LaunchedEffect(api, billing, desiredExtra, refresh) {
        busy = true
        plan = null
        offer = null
        owned = null
        message = "Checking your greenhouse plan…"
        try {
            var access = api.getGreenhousePlan()
            val from = access.extraSlots
            plan = access
            if (desiredExtra !in 0..access.maxExtraSlots) {
                message = "This capacity is unavailable."
                return@LaunchedEffect
            }
            if (manageExisting && access.extraSlots > 0 && !access.checkoutAvailable) {
                message = "Review your renewal, payment method and cancellation in Google Play."
                return@LaunchedEffect
            }
            if (!manageExisting && access.appliesPlanChange(from, desiredExtra)) {
                verified(access)
                return@LaunchedEffect
            }
            if (desiredExtra == 0 && !manageExisting) {
                message = "Cancel your greenhouse subscription in Google Play. Monitoring continues through the paid period."
                return@LaunchedEffect
            }
            if (!access.checkoutAvailable || access.accountBinding.isBlank()) {
                message = "Greenhouse subscriptions are unavailable for this account."
                return@LaunchedEffect
            }
            val purchases = billing.owned().filter { it.greenhousePlanProduct() != null }
            val purchase = purchases.greenhouseReceipt(access)
            if (purchase != null) {
                if (purchase.purchaseState == Purchase.PurchaseState.PENDING || purchase.pendingPurchaseUpdate != null) {
                    val expected = if (manageExisting) purchase.greenhousePlanProduct()?.greenhouseCapacity() ?: desiredExtra else desiredExtra
                    GreenhousePlanPurchaseWorker.schedule(activity, access, expected)
                    message = "Google Play is processing your payment. Your plan updates after approval."
                    return@LaunchedEffect
                }
                if (purchase.isSuspended) {
                    message = "Update your payment method in Google Play, then restore your plan."
                    owned = purchase
                    return@LaunchedEffect
                }
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                    message = "Your purchase is awaiting confirmation from Google Play."
                    return@LaunchedEffect
                }
                access = api.verifyGreenhousePlan(requireNotNull(purchase.greenhousePlanProduct()), purchase.purchaseToken)
                plan = access
                if (manageExisting && from > 0 && access.extraSlots > 0) {
                    message = "Review your renewal, payment method and cancellation in Google Play."
                    return@LaunchedEffect
                }
                if ((!manageExisting && access.appliesPlanChange(from, desiredExtra)) || (manageExisting && access.extraSlots > 0)) {
                    verified(access)
                    return@LaunchedEffect
                }
                if (access.extraSlots == 0 || !access.ownsGreenhouseReceipt(purchase)) {
                    message = "Google Play is updating your plan. Check again to see your confirmed capacity."
                    return@LaunchedEffect
                }
                owned = purchase
            } else if (access.extraSlots > 0 || purchases.isNotEmpty()) {
                message = "Open Google Play with the account that owns this greenhouse plan, then tap Restore purchase."
                return@LaunchedEffect
            }
            if (manageExisting) {
                message = "No greenhouse subscription found for this Google Play account."
                return@LaunchedEffect
            }
            offer = billing.offer(desiredExtra)
            val paidThrough = access.paidThroughEpochMs.takeIf { it > 0 }?.let { DateFormat.getDateInstance().format(Date(it)) }
            message = when {
                offer == null -> "This monthly plan is unavailable in your Google Play store."
                desiredExtra < access.extraSlots -> "Your current capacity continues through " + (paidThrough ?: "the billing period") + ". The lower price starts at renewal."
                desiredExtra > access.extraSlots && access.extraSlots > 0 -> "Google Play shows today’s prorated charge before you confirm. Your next renewal uses the new total."
                else -> "Your first greenhouse is free. The monthly plan renews until you cancel in Google Play."
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            message = "Could not check your plan or its Play price. Tap Restore purchase to try again."
        } finally { busy = false }
    }

    val current = plan
    val selected = offer
    val manage = current != null && (((desiredExtra == 0 || manageExisting) && current.extraSlots > 0) || owned?.isSuspended == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashboardTokens.Panel,
        title = { Text("Greenhouse plan", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected != null) {
                    Text(if (desiredExtra == 1) "1 additional greenhouse" else "$desiredExtra additional greenhouses", color = Color.White)
                    Text("${selected.monthlyPrice} / month total", color = DashboardTokens.Amber)
                    if (current?.licenseTest == true) Text("License test · Use a Google Play test payment method.", color = DashboardTokens.Muted)
                }
                if (messageFromPlay.isNotBlank()) Text(messageFromPlay, color = DashboardTokens.Muted)
                Text(message, color = DashboardTokens.Muted)
                if (!busy) TextButton(onClick = { messageFromPlay = ""; refresh++ }) { Text("Restore purchase") }
            }
        },
        confirmButton = {
            if (current != null && (manage || selected != null)) {
                Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber,
                    contentColor = Color.Black), onClick = {
                    if (manage) {
                        val url = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
                        (current.productId ?: owned?.greenhousePlanProduct())?.let {
                            url.appendQueryParameter("sku", it).appendQueryParameter("package", activity.packageName)
                        }
                        GreenhousePlanPurchaseWorker.schedule(activity, current, desiredExtra)
                        try { activity.startActivity(Intent(Intent.ACTION_VIEW, url.build())) }
                        catch (_: ActivityNotFoundException) { message = "Open Play Store → Payments & subscriptions → Subscriptions." }
                    } else if (selected != null) {
                        messageFromPlay = ""
                        busy = true
                        val expectedReceipt = owned
                        checkoutScope.launch {
                            try {
                                val account = api.getGreenhousePlan()
                                if (!account.stillCanBuy(current, expectedReceipt, billing.owned())) {
                                    messageFromPlay = "Your greenhouse plan changed. Review your current plan."
                                    refresh++
                                    return@launch
                                }
                                GreenhousePlanPurchaseWorker.schedule(activity, account, desiredExtra)
                                val code = billing.launch(activity, account, selected, expectedReceipt)
                                busy = code == BillingClient.BillingResponseCode.OK
                                if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh++
                                else if (!busy) messageFromPlay = "Could not open Google Play checkout."
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                busy = false
                                messageFromPlay = "Could not confirm your current plan. Try again."
                            }
                        }
                    }
                }) { Text(if (manage) "Manage in Google Play" else if (current.extraSlots > 0) "Change plan" else "Subscribe") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
