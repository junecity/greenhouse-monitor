package studio.junecity.greenhouse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat

/** The dark recess and amber edge follow the physical opening. No simulated lens is drawn. */
@Composable
internal fun Modifier.dashboardCutout(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout
    val left = cutout.getLeft(density, direction)
    val right = cutout.getRight(density, direction)
    if (left == 0 && right == 0) return displayCutoutPadding()

    var origin by remember { mutableStateOf(Offset.Zero) }
    val gutter = 9.dp
    val leftExtra = if (left > 0) gutter else 0.dp
    val rightExtra = if (right > 0) gutter else 0.dp
    val ltr = direction == LayoutDirection.Ltr
    return onGloballyPositioned { origin = it.positionInWindow() }.drawWithCache {
        val bounds = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
        val rims = bounds.mapNotNull { rect ->
            val cy = rect.exactCenterY() - origin.y
            val onLeft = rect.exactCenterX() - origin.x < size.width / 2
            val inset = if (onLeft) left else right
            if (inset <= 0 || cy !in 0f..size.height || size.width <= size.height) return@mapNotNull null
            val depth = inset + 4.dp.toPx()
            val reach = rect.height() / 2f + 19.dp.toPx()
            fun x(distance: Float) = if (onLeft) distance else size.width - distance
            val path = recessPath(cy, reach, ::x, depth)
            val accent = Brush.verticalGradient(listOf(Color.Transparent, DashboardTokens.Amber.copy(alpha=.85f),
                DashboardTokens.Amber.copy(alpha=.55f), Color.Transparent), cy-reach, cy+reach)
            path to accent
        }
        onDrawBehind {
            rims.forEach { (path, accent) ->
                drawPath(path, Color(0xff0b0c0f))
                drawPath(path, accent, style=Stroke(1.5.dp.toPx()))
            }
        }
    }.displayCutoutPadding().padding(start=if(ltr) leftExtra else rightExtra, end=if(ltr) rightExtra else leftExtra)
}

private fun recessPath(cy: Float, reach: Float, x: (Float) -> Float, depth: Float) = Path().apply {
    moveTo(x(-2f), cy - reach * 1.65f)
    cubicTo(x(0f), cy - reach, x(depth), cy - reach, x(depth), cy)
    cubicTo(x(depth), cy + reach * .8f, x(0f), cy + reach * .95f, x(-2f), cy + reach * 1.45f)
}

/** Extend the affected reading tile into the safe gutter, then cut its edge around the lens.
 * Text stays in its original measured column. Other tiles retain their standard rounded outline. */
@Composable
internal fun ReadingPanel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val left = WindowInsets.displayCutout.getLeft(density, direction)
    if (left <= 0) { ProPanel(modifier, content); return }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val tile = modifier.onGloballyPositioned {
        val origin = it.positionInWindow()
        bounds = androidx.compose.ui.geometry.Rect(origin, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }
    val margin = with(density) { 19.dp.toPx() }
    val lens = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects?.firstOrNull {
        bounds.width > 0 && bounds.left in left.toFloat()..(left + margin) &&
            it.right <= left && it.exactCenterY() in bounds.top..bounds.bottom
    }
    if (lens == null) { ProPanel(tile, content); return }
    androidx.compose.foundation.layout.Box(tile.drawWithCache {
        val cy = lens.exactCenterY() - bounds.top
        val reach = lens.height()/2f + margin
        val amplitude = left + 4.dp.toPx()
        val cut = recessPath(cy, reach, { it - bounds.left }, amplitude).apply { close() }
        val cardBounds = androidx.compose.ui.geometry.Rect(3.dp.toPx() - bounds.left, 0f, size.width, size.height)
        val outer = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cardBounds, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())))
        }
        val shape = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, outer, cut)
        val rim = recessPath(cy, reach, { it - bounds.left }, amplitude)
        val fill = Brush.linearGradient(listOf(Color(0xff1e2023), DashboardTokens.Panel))
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            drawPath(shape, fill)
            drawPath(shape, DashboardTokens.PanelEdge, style = stroke)
            clipRect(cardBounds.left, 0f, size.width, size.height) {
                drawPath(rim, DashboardTokens.Amber.copy(alpha=.8f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }, content = content)
}
