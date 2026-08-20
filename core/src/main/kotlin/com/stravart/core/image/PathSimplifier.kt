package com.stravart.core.image

import com.stravart.core.shape.Pt
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Débarrasse un contour de pixels de son escalier.
 *
 * Un bord suivi pixel par pixel avance en marches d'un pixel : converti tel quel en
 * itinéraire, il produirait des centaines de micro-virages sans rapport avec la forme
 * voulue. Ramer-Douglas-Peucker ne garde que les points qui portent réellement le
 * dessin — ceux dont la suppression déformerait le tracé de plus de la tolérance.
 */
object PathSimplifier {

    /** Réduit [points] en ne conservant que ceux qui portent la forme. */
    fun simplify(points: List<Pt>, tolerancePixels: Double): List<Pt> {
        if (points.size < 3 || tolerancePixels <= 0.0) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true

        // Pile explicite : un contour de photo peut compter des milliers de points,
        // de quoi épuiser la pile d'appels d'une récursion.
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)

        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            if (to <= from + 1) continue

            var farthest = -1
            var maxDistance = 0.0
            for (i in (from + 1) until to) {
                val distance = distanceToSegment(points[i], points[from], points[to])
                if (distance > maxDistance) {
                    maxDistance = distance
                    farthest = i
                }
            }

            if (maxDistance > tolerancePixels && farthest > 0) {
                keep[farthest] = true
                stack.addLast(from to farthest)
                stack.addLast(farthest to to)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    /** Lissage léger : une moyenne glissante sur trois points, en boucle fermée. */
    fun smoothClosed(points: List<Pt>): List<Pt> {
        if (points.size < 5) return points
        return List(points.size) { index ->
            val previous = points[(index - 1 + points.size) % points.size]
            val current = points[index]
            val next = points[(index + 1) % points.size]
            Pt(
                (previous.x + 2 * current.x + next.x) / 4,
                (previous.y + 2 * current.y + next.y) / 4,
            )
        }
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val length2 = vx * vx + vy * vy
        if (length2 < 1e-12) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / length2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * vx), p.y - (a.y + t * vy))
    }

    /** Aire algébrique d'un contour fermé, en pixels carrés. */
    fun area(points: List<Pt>): Double {
        if (points.size < 3) return 0.0
        var twice = 0.0
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            twice += current.x * next.y - next.x * current.y
        }
        return abs(twice) / 2.0
    }
}
