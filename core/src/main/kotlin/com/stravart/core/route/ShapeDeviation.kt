package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.geo.Vec2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Écart, point par point, entre un tracé et un autre.
 *
 * Sert à deux choses : noter la ressemblance globale, et repérer *où* l'itinéraire
 * s'éloigne de la forme — ce qui permet d'y concentrer les points de passage plutôt
 * que de les répartir uniformément sur un tracé dont la majeure partie va déjà bien.
 *
 * Les coordonnées de la cible sont converties une seule fois : sans cela, mesurer
 * deux cents points contre un itinéraire de plusieurs milliers coûterait des
 * centaines de milliers de conversions trigonométriques.
 */
internal object ShapeDeviation {

    /** Distance en mètres de chaque point de [from] au tracé [to]. */
    fun perPoint(from: List<LatLon>, to: List<LatLon>, origin: LatLon = from.first()): DoubleArray {
        if (from.isEmpty()) return DoubleArray(0)
        if (to.isEmpty()) return DoubleArray(from.size)

        val target = to.map { Geo.toLocal(origin, it) }
        return DoubleArray(from.size) { index ->
            distanceToLocalPath(Geo.toLocal(origin, from[index]), target)
        }
    }

    private fun distanceToLocalPath(point: Vec2, path: List<Vec2>): Double {
        if (path.size == 1) return point.distanceTo(path[0])
        var best = Double.MAX_VALUE
        for (i in 1 until path.size) {
            best = min(best, pointToSegment(point, path[i - 1], path[i]))
            if (best == 0.0) return 0.0
        }
        return best
    }

    private fun pointToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-12) return p.distanceTo(a)
        val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * vx), p.y - (a.y + t * vy))
    }

    /**
     * Lisse une série d'écarts sur une fenêtre glissante.
     *
     * Un écart brut varie d'un point à l'autre ; s'en servir tel quel pour piloter la
     * densité de points de passage produirait un échantillonnage en dents de scie.
     */
    fun smoothed(values: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || values.size <= 2) return values
        val half = window / 2
        return DoubleArray(values.size) { index ->
            var sum = 0.0
            var count = 0
            for (k in (index - half)..(index + half)) {
                if (k in values.indices) {
                    sum += values[k]
                    count++
                }
            }
            sum / count
        }
    }
}
