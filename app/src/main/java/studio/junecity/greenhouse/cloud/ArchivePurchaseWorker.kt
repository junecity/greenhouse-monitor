package studio.junecity.greenhouse.cloud

import android.content.Context
import androidx.work.*
import com.android.billingclient.api.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient

/** A Play account can own subscriptions associated with more than one greenhouse account. */
internal fun List<Purchase>.purchaseFor(product: String, binding: String): Purchase? {
    val matching = filter { product in it.products && it.accountIdentifiers?.obfuscatedAccountId == binding }
    return matching.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED } ?: matching.firstOrNull()
}

/** Recovers an interrupted Play checkout even if the customer never reopens the purchase dialog. */
class ArchivePurchaseWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SecureSettingsStore(applicationContext)
        val owner = store.cloudEnrollment() ?: return Result.success()
        if (owner.role != CloudDeviceRole.ViewerClient || owner.isStandaloneClient() ||
            owner.deviceId != inputData.getString("device") || owner.tenantId != inputData.getString("tenant")) {
            return Result.success()
        }
        val binding = inputData.getString("binding") ?: return Result.failure()
        val config = CloudRuntime.configurationOrNull() ?: return Result.failure()
        val billing = BillingClient.newBuilder(applicationContext)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .setListener { _, _ -> }.build()
        try {
            val connected = CompletableDeferred<Boolean>()
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() { connected.complete(false) }
                override fun onBillingSetupFinished(result: BillingResult) {
                    connected.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
            })
            if (withTimeoutOrNull(15_000L) { connected.await() } != true) return Result.retry()
            val owned = billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build())
            if (owned.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return Result.retry()
            val purchase = owned.purchasesList.purchaseFor(PRODUCT, binding)
            if (purchase == null) {
                // A checkout can be abandoned without Android delivering its cancel callback.
                return if (System.currentTimeMillis() - inputData.getLong("started", 0) < TimeUnit.DAYS.toMillis(3))
                    Result.retry() else Result.success()
            }
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) return Result.retry()
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || store.cloudEnrollment() != owner)
                return Result.success()
            gatewayForEnrollment(applicationContext, config, owner).verifyArchivePurchase(owner.greenhouseId, purchase.purchaseToken)
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudApiException) {
            return if (e.statusCode in setOf(400, 403, 404)) Result.failure() else Result.retry()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            billing.endConnection()
        }
    }

    companion object {
        const val PRODUCT = "camera_storage_monthly"

        fun schedule(context: Context, house: String, binding: String) {
            val owner = SecureSettingsStore(context).cloudEnrollment() ?: return
            if (owner.role != CloudDeviceRole.ViewerClient || owner.greenhouseId != house || owner.isStandaloneClient()) return
            val request = OneTimeWorkRequestBuilder<ArchivePurchaseWorker>()
                .setInputData(workDataOf("device" to owner.deviceId, "tenant" to owner.tenantId, "binding" to binding,
                    "started" to System.currentTimeMillis()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("archive_purchase_$binding", ExistingWorkPolicy.KEEP, request)
        }

        fun finish(context: Context, binding: String) {
            WorkManager.getInstance(context).cancelUniqueWork("archive_purchase_$binding")
        }
    }
}
