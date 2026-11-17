package studio.junecity.greenhouse.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.android.billingclient.api.Purchase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import studio.junecity.greenhouse.settings.SecureSettingsStore

/** Recovers a checkout interrupted by leaving the app. Play pushes also reconcile on the backend. */
class GreenhousePlanPurchaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val secure = SecureSettingsStore(applicationContext)
        val owner = secure.cloudEnrollment() ?: return Result.success()
        if (owner.deviceId != inputData.getString("device") || owner.tenantId != inputData.getString("tenant") ||
            owner.deviceSecret != null) return Result.success()
        val binding = inputData.getString("binding") ?: return Result.failure()
        val wanted = inputData.getInt("wanted", -1)
        val from = inputData.getInt("from", -1)
        val config = CloudRuntime.configurationOrNull() ?: return Result.failure()
        return try {
            val api = gatewayForEnrollment(applicationContext, config, owner)
            val state = api.getGreenhousePlan()
            if (state.accountBinding != binding) return Result.success()
            if (state.appliesPlanChange(from, wanted)) return Result.success()
            val purchase = GreenhousePlanBilling(applicationContext).use { it.owned().greenhouseReceipt(state) }
            if (purchase?.purchaseState == Purchase.PurchaseState.PENDING || purchase?.pendingPurchaseUpdate != null)
                return Result.retry()
            if (purchase?.purchaseState == Purchase.PurchaseState.PURCHASED && secure.cloudEnrollment() == owner) {
                val product = requireNotNull(purchase.greenhousePlanProduct())
                if (api.verifyGreenhousePlan(product, purchase.purchaseToken).appliesPlanChange(from, wanted))
                    return Result.success()
            }
            val elapsed = System.currentTimeMillis() - inputData.getLong("started", 0)
            if (elapsed in 0 until TimeUnit.DAYS.toMillis(3)) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: CloudApiException) {
            if (e.statusCode == 400 || e.statusCode == 403 || e.statusCode == 404) Result.failure() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        internal fun schedule(context: Context, plan: GreenhousePlanState, wanted: Int) {
            val owner = SecureSettingsStore(context).cloudEnrollment() ?: return
            if (owner.deviceSecret != null || plan.accountBinding.isBlank()) return
            val work = OneTimeWorkRequestBuilder<GreenhousePlanPurchaseWorker>()
                .setInputData(workDataOf("device" to owner.deviceId, "tenant" to owner.tenantId,
                    "binding" to plan.accountBinding, "from" to plan.extraSlots, "wanted" to wanted,
                    "started" to System.currentTimeMillis()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork("greenhouse_plan_" + plan.accountBinding, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
