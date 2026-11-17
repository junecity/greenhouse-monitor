package studio.junecity.greenhouse.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import studio.junecity.greenhouse.R
import studio.junecity.greenhouse.widget.OutdoorStatus
import studio.junecity.greenhouse.widget.GreenhouseWidgetUiState
import studio.junecity.greenhouse.widget.WidgetFeedStatus

@Composable
internal fun MonitoringCardDialog(state: GreenhouseWidgetUiState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        MonitoringCard(state, onRefresh, onDismiss, Modifier
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 640.dp))
    }
}

/** All sizes share the same observations and units as the pinned home screen widget. */
@Composable
internal fun MonitoringCard(
    state: GreenhouseWidgetUiState, onRefresh: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(28.dp)).background(colorResource(R.color.climate_surface))
        .semantics { paneTitle = "Greenhouse readings" }) {
        val availableHeight = maxHeight
        val availableWidth = maxWidth
        val compact = availableWidth < 340.dp || availableHeight < 212.dp || (availableWidth < 620.dp && availableHeight < 335.dp)
        val wide = !compact && availableWidth >= 620.dp
        val fontScale = LocalDensity.current.fontScale
        val pad = when {
            compact -> 12.dp
            wide && availableHeight < 355.dp -> 14.dp
            else -> 20.dp
        }
        val gap = if (compact) 8.dp else 12.dp
        Column(
            Modifier.fillMaxWidth().testTag("monitor-" + if (wide) "wide" else if (compact) "compact" else "standard")
                .verticalScroll(rememberScrollState()).animateContentSize(tween(180))
                .padding(horizontal = pad, vertical = if (compact || wide) 8.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_climate_greenhouse), null, Modifier.size(24.dp),
                    tint = colorResource(R.color.climate_primary))
                Spacer(Modifier.width(8.dp))
                ReadingText(state.title, Modifier.weight(1f).semantics { heading() }, size = 20, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                FeedStatus(state.status)
                IconButton(onDismiss, Modifier.size(48.dp).semantics { contentDescription = "Close live readings" }) {
                    val ink = colorResource(R.color.climate_secondary)
                    Canvas(Modifier.size(16.dp)) {
                        val a = size.minDimension * .17f
                        val b = size.minDimension - a
                        drawLine(ink, Offset(a, a), Offset(b, b), 1.8.dp.toPx(), StrokeCap.Round)
                        drawLine(ink, Offset(b, a), Offset(a, b), 1.8.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            state.statusMessage?.let { message ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReadingText(message, Modifier.weight(1f), size = 13, color = colorResource(R.color.climate_secondary))
                    if (state.status in setOf(WidgetFeedStatus.Delayed, WidgetFeedStatus.Offline, WidgetFeedStatus.Error, WidgetFeedStatus.Permission)) {
                        TextButton(onClick = onRefresh) { ReadingText("Retry", size = 14, color = colorResource(R.color.climate_temperature)) }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MetricPair(state, compact = false, modifier = Modifier.weight(1.13f))
                    MonitoringTrend(state, Modifier.weight(1f), height = if (availableHeight < 355.dp) 72.dp else 96.dp)
                }
            } else {
                MetricPair(state, compact, Modifier.fillMaxWidth())
                if (!compact) MonitoringTrend(state, Modifier.fillMaxWidth(), height = if (availableHeight < 450.dp) 72.dp else 104.dp)
            }
            if (!compact) {
                HorizontalDivider(color = colorResource(R.color.climate_divider))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Secondary("OUTSIDE", state.degrees(state.outdoorF), Modifier.weight(1f),
                        state.outdoorStatus.takeUnless { state.outdoorFreshness == OutdoorStatus.Current })
                    Secondary("TARGET", state.targetText, Modifier.weight(1f))
                    Secondary("DEW POINT", state.degrees(state.dewPointF), Modifier.weight(1f))
                    if (wide && fontScale < 1.5f) TargetCondition(state, Modifier.weight(1.2f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    Column(Modifier.weight(1f)) {
                        ReadingText("Target ${state.targetText}", size = 12, color = colorResource(R.color.climate_secondary))
                        if (state.warning) TargetCondition(state)
                    }
                } else if (!wide || fontScale >= 1.5f) {
                    TargetCondition(state, Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                ReadingText(state.updatedText, Modifier.widthIn(max = availableWidth * .51f), size = 12,
                    color = colorResource(R.color.climate_muted))
            }
        }
    }
}

@Composable
private fun FeedStatus(status: WidgetFeedStatus) {
    val color by animateColorAsState(colorResource(when (status) {
        WidgetFeedStatus.Live -> R.color.climate_healthy
        WidgetFeedStatus.Delayed, WidgetFeedStatus.Permission -> R.color.climate_warning
        WidgetFeedStatus.Error -> R.color.climate_critical
        else -> R.color.climate_muted
    }), tween(180), label = "Connection status")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        ReadingText(status.label, size = 11, color = color, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricPair(state: GreenhouseWidgetUiState, compact: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val stack = maxWidth / LocalDensity.current.fontScale < 225.dp
        val temp = colorResource(R.color.climate_temperature)
        val humidity = colorResource(R.color.climate_humidity)
        val secondary = colorResource(R.color.climate_secondary)
        val size = if (compact) 32 else 40
        // A unit or source switch must never crossfade from a different unit or greenhouse.
        key(state.title, state.sensorId, state.celsius) {
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Temperature", state.temperatureText, state.rateText, temp, temp, size, Modifier.fillMaxWidth())
                    MetricTile("Humidity", state.humidityText, state.humidityRangeText, humidity, secondary, size, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Temperature", state.temperatureText, state.rateText, temp, temp, size, Modifier.weight(1f).fillMaxHeight())
                    MetricTile("Humidity", state.humidityText, state.humidityRangeText, humidity, secondary, size, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, caption: String, accent: Color, captionColor: Color, numberSize: Int, modifier: Modifier) {
    Column(modifier.background(colorResource(R.color.climate_tile), RoundedCornerShape(16.dp))
        .semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingText(label, size = 13, color = accent, weight = FontWeight.SemiBold)
        Crossfade(value, animationSpec = tween(220), label = label) { reading ->
            ReadingText(reading, size = numberSize, weight = FontWeight.Bold)
        }
        ReadingText(caption, size = 11, color = captionColor)
    }
}

@Composable
private fun Secondary(label: String, value: String, modifier: Modifier, detail: String? = null) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReadingText(label, size = 11, color = colorResource(R.color.climate_muted), weight = FontWeight.Medium)
        ReadingText(value, size = 23, weight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) ReadingText(detail, size = 11, color = colorResource(R.color.climate_muted))
    }
}

@Composable
private fun TargetCondition(state: GreenhouseWidgetUiState, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        val label = state.targetStatus
        if (label != null) {
            Icon(painterResource(if (state.warning) R.drawable.ic_climate_alert_triangle else R.drawable.ic_climate_check_circle),
                null, Modifier.size(16.dp), tint = colorResource(if (state.warning) R.color.climate_critical else R.color.climate_healthy))
            Spacer(Modifier.width(6.dp))
            ReadingText(label, size = 12, color = colorResource(R.color.climate_secondary))
        }
    }
}

@Composable
internal fun ReadingText(text: String, modifier: Modifier = Modifier, size: Int, color: Color = colorResource(R.color.climate_primary),
    weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE) {
    Text(text = text, modifier = modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
            lineHeight = (size * 1.22f).sp, fontFeatureSettings = "tnum"))
}
