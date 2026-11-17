package studio.junecity.greenhouse.sensors

import android.content.Context
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.settings.GreenhousePlanCache
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.settings.isStandaloneClient

/** A paired viewer can also collect for this account's previously configured local greenhouse. */
internal class CollectorEnrollment(
    private val context: Context,
    val source: CloudEnrollment?,
    private val authenticatedAs: CloudEnrollment? = source
) {
    private val secure = SecureSettingsStore(context)

    private fun sourceAllowed(): Boolean {
        if (source == authenticatedAs) {
            return source?.role != CloudDeviceRole.ViewerClient || (source.isStandaloneClient() &&
                GreenhousePlanCache.localCollector(context, source) == source)
        }
        return authenticatedAs != null && source != null &&
            GreenhousePlanCache.localCollector(context, authenticatedAs) == source
    }

    fun isCurrent() = secure.cloudEnrollment() == authenticatedAs && sourceAllowed()

    fun <T> whileCurrent(action: () -> T): T? {
        if (authenticatedAs == null) return if (isCurrent()) action() else null
        return secure.withCurrentEnrollment(authenticatedAs) { if (sourceAllowed()) action() else null }
    }
}
