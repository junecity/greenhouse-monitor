package studio.junecity.greenhouse.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.widget.ClimateCardRepository
import studio.junecity.greenhouse.widget.GreenhouseWidgetUiState

/** The dashboard keeps its camera and controls. Read the detailed history only while its card is open. */
@Composable
internal fun DashboardClimateDetails(
    owner: CloudEnrollment?, houseId: String, onRefresh: () -> Unit,
    dashboard: @Composable (openDetails: (() -> Unit)?) -> Unit
) {
    val app = LocalContext.current.applicationContext
    key(owner?.tenantId, owner?.deviceId, owner?.role, owner?.greenhouseId, owner?.localSensors, houseId) {
        var open by rememberSaveable { mutableStateOf(false) }
        dashboard(if (owner == null) null else ({ open = true }))
        if (open && owner != null) {
            val readings = remember(owner, houseId) { ClimateCardRepository(app).observe(owner, houseId = houseId) }
            val report by readings.collectAsStateWithLifecycle(initialValue = GreenhouseWidgetUiState())
            MonitoringCardDialog(report, onRefresh = onRefresh, onDismiss = { open = false })
        }
    }
}
