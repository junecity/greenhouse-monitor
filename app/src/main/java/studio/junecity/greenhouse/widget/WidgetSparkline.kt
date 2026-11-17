package studio.junecity.greenhouse.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import studio.junecity.greenhouse.data.ClimateBucket
import studio.junecity.greenhouse.data.WIDGET_WINDOW_MS

/** Draw a six-hour trace at its intended aspect ratio. A gap starts a separate stroke and fill. */
internal fun widgetSparkline(
    points: List<ClimateBucket>, now: Long, width: Int, height: Int,
    color: Int, value: (ClimateBucket) -> Double
): Bitmap {
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (points.size < 2) return image
    val canvas = Canvas(image)
    val stroke = (width / 110f).coerceIn(1.5f, 5.5f)
    val radius = stroke * 1.7f
    val edge = radius + 1f
    val bottom = height - edge
    val minimum = points.minOf(value)
    val maximum = points.maxOf(value)
    val mid = (minimum + maximum) / 2
    val span = (maximum - minimum).coerceAtLeast(2.0) * 1.08
    val line = Path()
    val fill = Path()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val gradient = LinearGradient(0f, 0f, 0f, height.toFloat(),
        (color and 0x00ffffff) or 0x61000000, color and 0x00ffffff, Shader.TileMode.CLAMP)
    var startX = 0f
    var lastX = 0f
    var lastY = 0f
    var count = 0
    fun drawRun() {
        if (count > 1) {
            fill.set(line)
            fill.lineTo(lastX, bottom)
            fill.lineTo(startX, bottom)
            fill.close()
            paint.style = Paint.Style.FILL
            paint.color = -1
            paint.shader = gradient
            canvas.drawPath(fill, paint)
            paint.shader = null
            paint.color = color
            paint.style = Paint.Style.STROKE
            canvas.drawPath(line, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(lastX, lastY, stroke * .75f, paint)
        }
    }
    points.forEachIndexed { index, point ->
        val fraction = (1.0 - (now - point.at).toDouble() / WIDGET_WINDOW_MS).coerceIn(0.0, 1.0).toFloat()
        val x = edge + (width - 2 * edge) * fraction
        val y = edge + (bottom - edge) * (.5f - ((value(point) - mid) / span).toFloat())
        if (index == 0 || !point.continuesFrom(points[index - 1])) {
            if (index > 0) drawRun()
            startX = x
            line.reset()
            line.moveTo(x, y)
            count = 1
        } else {
            line.lineTo(x, y)
            count++
        }
        lastX = x
        lastY = y
    }
    drawRun()
    paint.style = Paint.Style.FILL
    paint.color = color
    canvas.drawCircle(lastX, lastY, radius, paint)
    return image
}
