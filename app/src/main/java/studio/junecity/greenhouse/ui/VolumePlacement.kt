package studio.junecity.greenhouse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.junecity.greenhouse.data.ZoneClimate
import studio.junecity.greenhouse.model.GreenhouseConfig
import studio.junecity.greenhouse.model.SensorPosition

@Composable
internal fun VolumePlacement(
    house: GreenhouseConfig, name: String, projection: Projection, position: SensorPosition?,
    outside: Boolean, move: (SensorPosition?) -> Unit
) {
    val floor = position?.let { projection.point(it.xFeet, it.yFeet, 0.0) }
    val marker = position?.let { projection.point(it.xFeet, it.yFeet, ZoneClimate.mountingHeight(house, it) ?: 0.0) }
    val currentFloor by rememberUpdatedState(floor)
    val currentMarker by rememberUpdatedState(marker)
    val onMove by rememberUpdatedState(move)
    Box(Modifier.fillMaxSize().semantics { contentDescription = "3D sensor placement floor" }
        .pointerInput(house, projection) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val base = currentFloor
                val handle = currentMarker
                // Keep the finger on the raised pin when moving a mounted sensor.
                val grip = if (base != null && handle != null &&
                    (down.position - handle).getDistance() <= 16.dp.toPx()) base - down.position else Offset.Zero
                fun place(at: Offset) {
                    val p = projection.floorPoint(at + grip)
                    onMove(p.takeIf { house.containsPoint(it.xFeet, it.yFeet) })
                }
                place(down.position)
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    place(change.position)
                    change.consume()
                } while (change.pressed)
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            if (floor != null && marker != null) {
                drawLine(DashboardTokens.Amber, floor, marker, 2.dp.toPx())
                drawCircle(DashboardTokens.Amber, 7.dp.toPx(), floor, style = Stroke(2.dp.toPx()))
                drawCircle(DashboardTokens.Background, 10.dp.toPx(), marker)
                drawCircle(DashboardTokens.Amber, 7.dp.toPx(), marker)
            }
        }
        Text(if (outside) "Drop inside the greenhouse floor" else "Drag $name onto the floor",
            Modifier.padding(8.dp).background(DashboardTokens.PanelRaised, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (outside) DashboardTokens.Amber else Color.White, fontSize = 13.sp)
    }
}
