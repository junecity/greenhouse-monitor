package studio.junecity.greenhouse.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Vertices use feet in the editor and fractions of the bounding box in saved plans. */
object DrawnPlan {
    private fun distance(a: NormalizedPosition, b: NormalizedPosition) = hypot(a.x - b.x, a.y - b.y)

    fun project(p: NormalizedPosition, a: NormalizedPosition, b: NormalizedPosition): NormalizedPosition {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val t = (((p.x-a.x)*dx + (p.y-a.y)*dy) / (dx*dx+dy*dy).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        return NormalizedPosition(a.x + dx*t, a.y + dy*t)
    }

    fun area(points: List<NormalizedPosition>): Double = abs(points.indices.sumOf { i ->
        val a = points[i]
        val b = points[(i+1)%points.size]
        a.x*b.y - b.x*a.y
    }) / 2

    fun nearest(p: NormalizedPosition, points: List<NormalizedPosition>) = points.indices
        .map { project(p, points[it], points[(it+1)%points.size]) }.minBy { distance(p,it) }

    fun contains(p: NormalizedPosition, points: List<NormalizedPosition>): Boolean {
        var inside = false
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i+1)%points.size]
            if (distance(p,project(p,a,b)) < 1e-8) return true
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside = !inside
        }
        return inside
    }

    private fun crosses(a: NormalizedPosition,b: NormalizedPosition,c: NormalizedPosition,d: NormalizedPosition): Boolean {
        fun side(p: NormalizedPosition,q: NormalizedPosition,r: NormalizedPosition) =
            (q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x)
        if (listOf(distance(a,project(a,c,d)),distance(b,project(b,c,d)),distance(c,project(c,a,b)),distance(d,project(d,a,b))).any { it < 1e-8 }) return true
        return side(a,b,c)*side(a,b,d)<0 && side(c,d,a)*side(c,d,b)<0
    }

    fun valid(points: List<NormalizedPosition>): Boolean {
        if (points.size !in 3..48 || points.toSet().size != points.size ||
            points.any { !it.x.isFinite() || !it.y.isFinite() } || area(points) < 1e-6) return false
        for (i in points.indices) for (j in i+2 until points.size) {
            if (i == 0 && j == points.lastIndex) continue
            if (crosses(points[i],points[(i+1)%points.size],points[j],points[(j+1)%points.size])) return false
        }
        return true
    }

    private fun simplify(points: List<NormalizedPosition>, tolerance: Double): List<NormalizedPosition> {
        if (points.size < 3) return points
        val start = points.first()
        val end = points.last()
        val corner = (1 until points.lastIndex).maxBy { distance(points[it],project(points[it],start,end)) }
        if (distance(points[corner],project(points[corner],start,end)) <= tolerance) return listOf(start,end)
        return simplify(points.subList(0,corner+1),tolerance).dropLast(1) + simplify(points.subList(corner,points.size),tolerance)
    }

    fun fromStroke(raw: List<NormalizedPosition>, original: GreenhouseConfig, tolerance: Double): GreenhouseConfig? {
        if (raw.size < 2) return null
        val corners = simplify(raw,tolerance).toMutableList()
        if (distance(corners.first(),corners.last()) <= tolerance*2) corners.removeAt(corners.lastIndex)
        if (corners.size < 2 || corners.size > 48) return null
        if (corners.size == 2) {
            val (a,b) = corners
            corners.add(1,NormalizedPosition(b.x,a.y))
            corners.add(NormalizedPosition(a.x,b.y))
        }
        // Level nearly horizontal/vertical walls, including the closing edge. Diagonals remain diagonal.
        val snapped = corners.map { NormalizedPosition(round(it.x),round(it.y)) }.toMutableList()
        for (i in corners.indices) {
            val j = (i+1)%corners.size
            val a = snapped[i]
            val b = snapped[j]
            val dx = abs(a.x-b.x)
            val dy = abs(a.y-b.y)
            if (dx > 0 && dy <= dx*.12) snapped[j] = b.copy(y=a.y)
            else if (dy > 0 && dx <= dy*.12) snapped[j] = b.copy(x=a.x)
        }
        if (!valid(snapped)) return null
        val left = snapped.minOf { it.x }
        val top = snapped.minOf { it.y }
        val width = snapped.maxOf { it.x } - left
        val height = snapped.maxOf { it.y } - top
        val points = snapped.map { NormalizedPosition((it.x-left)/width,(it.y-top)/height) }
        return original.copy(shape=GreenhouseShape.Drawn, lengthFeet=width, widthFeet=height, vertices=points)
    }
}
