package studio.junecity.greenhouse.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import studio.junecity.greenhouse.data.ClimateBucket
import studio.junecity.greenhouse.data.WIDGET_WINDOW_MS

internal fun widgetSparkline(
    points: List<ClimateBucket>, now: Long, width: Int, height: Int,
    color: Int, value: (ClimateBucket) -> Double
): Bitmap {
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    val scale = width / 180f
    val margin = 3f * scale
    val bottom = height - margin
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = scale
        this.color = Color.argb(24, 170, 181, 193)
    }
    canvas.drawLine(margin, bottom, width - margin, bottom, paint)
    if (points.isEmpty()) return image
    val min = points.minOf(value)
    val max = points.maxOf(value)
    val center = (min + max) / 2
    val span = (max - min).coerceAtLeast(2.0) * 1.16
    val line = Path()
    val fill = Path()
    var firstX = 0f
    var lastX = 0f
    var lastY = 0f
    fun flush() {
        fill.set(line)
        fill.lineTo(lastX, bottom)
        fill.lineTo(firstX, bottom)
        fill.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, margin, 0f, bottom,
            (color and 0x00ffffff) or 0x2e000000, color and 0x00ffffff, Shader.TileMode.CLAMP)
        canvas.drawPath(fill, paint)
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.35f * scale
        canvas.drawPath(line, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(lastX, lastY, 2.2f * scale, paint)
    }
    points.forEachIndexed { index, point ->
        val x = margin + (width - 2 * margin) * (1f - (now - point.at).toFloat() / WIDGET_WINDOW_MS)
        val y = margin + (bottom - margin) * (.5f - ((value(point) - center) / span).toFloat())
        if (index == 0 || point.at - points[index - 1].at > 600_000L) {
            if (index > 0) flush()
            firstX = x
            line.reset()
            line.moveTo(x, y)
        } else line.lineTo(x, y)
        lastX = x
        lastY = y
    }
    flush()
    return image
}
