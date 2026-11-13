package studio.junecity.greenhouse.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import studio.junecity.greenhouse.data.EnergyUnit
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.ui.DashboardTokens

internal class QuickStartData(val zip: String, val length: Double, val width: Double, val height: Double?,
    val unit: EnergyUnit, val price: Double?, val key: String)

@Composable
internal fun QuickStartScreen(owner: CloudEnrollment, dismiss: () -> Unit, save: (QuickStartData) -> Unit) {
    val viewer = owner.role == CloudDeviceRole.ViewerClient
    var step by rememberSaveable { mutableIntStateOf(if (viewer) 0 else 1) }
    var zip by rememberSaveable { mutableStateOf("") }
    var length by rememberSaveable { mutableStateOf("") }
    var width by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(EnergyUnit.Kwh) }
    var apiKey by remember { mutableStateOf("") }
    val first = if (viewer) 0 else 1
    BackHandler { if (step > first && step != 3) step-- else if (step == 3) step = 0 else dismiss() }
    val title = listOf("Quick start", "Your greenhouse", "Sensors and energy", "Greenhouse server")[step]
    val subtitle = listOf("How will you monitor your greenhouse?", "Enter the dimensions in feet. You can refine the shape later in Settings.",
        "Connect your Wi-Fi sensors and enter your utility rate now or later.", "Readings and the floor plan come from your paired greenhouse.")[step]
    CloudSetupShell(title, subtitle) {
        val wide = Modifier.fillMaxWidth()
        when (step) {
            0 -> {
                PrimaryButton("Use this phone only", wide, false) { step = 1 }
                SecondaryButton("Use a greenhouse server", wide, false) { step = 3 }
            }
            1 -> {
                CloudField(zip, { zip = it }, "Greenhouse ZIP code", KeyboardType.Number)
                CloudField(length, { length = it }, "Length (ft)", KeyboardType.Decimal)
                CloudField(width, { width = it }, "Width (ft)", KeyboardType.Decimal)
                CloudField(height, { height = it }, "Peak height (ft, optional)", KeyboardType.Decimal)
                fun dimension(text: String) = text.toDoubleOrNull()?.let { it in 0.1..480.0 } == true
                val valid = zip.matches(Regex("[0-9]{5}")) && dimension(length) && dimension(width) &&
                    (height.isBlank() || dimension(height))
                PrimaryButton("Continue", wide, !valid) { step = 2 }
            }
            2 -> {
                CloudField(apiKey, { apiKey = it }, "Govee API key (optional)", KeyboardType.Password, true)
                Text("Use the billing unit on your utility bill.", color = DashboardTokens.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnergyUnit.entries.forEach { choice -> FilterChip(selected = unit == choice,
                        onClick = { if (choice != unit) { unit = choice; price = "" } }, label = {
                            Text(choice.label, color = if (choice == unit) DashboardTokens.Amber else DashboardTokens.White)
                        }) }
                }
                CloudField(price, { price = it }, "Price per ${unit.label} in dollars (optional)", KeyboardType.Decimal)
                Text("Energy learns the indoor temperature from steady readings after sunset.", color = DashboardTokens.Muted)
                val rate = price.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                PrimaryButton("Finish setup", wide, price.isNotBlank() && rate == null) {
                    save(QuickStartData(zip, length.toDouble(), width.toDouble(), height.toDoubleOrNull(), unit, rate, apiKey.trim()))
                    apiKey = ""
                }
            }
            3 -> {
                Text(if (owner.greenhouseId.isBlank()) "On this phone, open Settings → Connectivity to pair a server. On the greenhouse phone, scan the pairing code."
                    else "Your account already has a greenhouse. Open Dashboard to see its readings.", color = DashboardTokens.White)
                PrimaryButton("Open dashboard", wide, false, dismiss)
            }
        }
        if (step != 3) SecondaryButton("Skip for now", wide, false, dismiss)
    }
}
