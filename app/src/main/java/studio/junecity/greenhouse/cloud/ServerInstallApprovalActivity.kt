package studio.junecity.greenhouse.cloud

import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.SecureSettingsStore

/** Opened by a tap on the server. Android's installer keeps control of the confirmation. */
class ServerInstallApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        lifecycleScope.launch {
            try {
                val server = checkNotNull(SecureSettingsStore(this@ServerInstallApprovalActivity).cloudEnrollment())
                val gateway = gatewayForEnrollment(this@ServerInstallApprovalActivity,
                    checkNotNull(CloudRuntime.configurationOrNull()), server)
                startActivity(ManagedServerUpdate.authorizedConfirmation(this@ServerInstallApprovalActivity, id, server, gateway))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@ServerInstallApprovalActivity,
                    "Couldn’t verify this update. Check the connection and try again in Settings.", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }
}
