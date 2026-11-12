package studio.junecity.greenhouse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat

/** Apply inside a card or navigation item. Its outer bounds always follow the full-width grid. */
@Composable
internal fun Modifier.dashboardLensClearance(curved: Boolean = false): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout
    val left = insets.getLeft(density, direction)
    val right = insets.getRight(density, direction)
    if (left == 0 && right == 0) return this

    val view = LocalView.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val positioned = onGloballyPositioned {
        bounds = Rect(it.positionInWindow(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }
    val lenses = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty().filter {
        bounds.left < it.right && it.left < bounds.right && bounds.top < it.bottom && it.top < bounds.bottom
    }
    if (lenses.isEmpty()) return positioned
    val leftLens = lenses.filter { left > 0 && it.right <= left }.maxByOrNull { it.right }
    val rightLens = lenses.filter { right > 0 && it.left >= view.rootView.width - right }.minByOrNull { it.left }
    val gap = with(density) { 4.dp.toPx() }
    val start = leftLens?.let { it.right - bounds.left + gap } ?: 0f
    val end = rightLens?.let { bounds.right - it.left + gap } ?: 0f
    val surface = if (!curved) positioned else positioned.drawWithCache {
        val rims = listOfNotNull(leftLens, rightLens).map { lens ->
            val onLeft = lens == leftLens
            fun x(distance: Float) = if (onLeft) distance else size.width - distance
            val depth = if (onLeft) start else end
            val center = lens.exactCenterY() - bounds.top
            val reach = lens.height() / 2 + 19.dp.toPx()
            Path().apply {
                moveTo(x(-2f), center - reach * 1.65f)
                cubicTo(x(0f), center-reach, x(depth), center-reach, x(depth), center)
                cubicTo(x(depth), center+reach*.8f, x(0f), center+reach*.95f, x(-2f), center+reach*1.45f)
            }
        }
        onDrawWithContent {
            drawContent()
            rims.forEach {
                drawPath(it, Color(0xff0b0c0f))
                drawPath(it, DashboardTokens.Amber.copy(alpha=.8f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
    return with(density) { surface.absolutePadding(left = start.toDp(), right = end.toDp()) }
}
