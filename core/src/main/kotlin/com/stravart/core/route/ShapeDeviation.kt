package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.geo.Vec2
import kotlin.math.hypot

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
        val offsets = offsets(from, to, origin)
        return DoubleArray(offsets.size) { hypot(offsets[it].x, offsets[it].y) }
    }

    /**
     * Vecteur, en mètres, allant de chaque point de [from] au point le plus proche
     * du tracé [to]. Sa direction dit *de quel côté* le tracé s'est éloigné — ce que
     * la seule distance ne dit pas, et sans quoi on ne saurait pas où le ramener.
     */
    fun offsets(from: List<LatLon>, to: List<LatLon>, origin: LatLon = from.first()): List<Vec2> {
        if (from.isEmpty()) return emptyList()
        if (to.isEmpty()) return from.map { Vec2(0.0, 0.0) }

        val target = to.map { Geo.toLocal(origin, it) }
        return from.map { closestOffset(Geo.toLocal(origin, it), target) }
    }

    private fun closestOffset(point: Vec2, path: List<Vec2>): Vec2 {
        if (path.size == 1) return Vec2(path[0].x - point.x, path[0].y - point.y)
        var best = Vec2(0.0, 0.0)
        var bestDistance = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val offset = offsetToSegment(point, path[i - 1], path[i])
            val distance = hypot(offset.x, offset.y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = offset
            }
        }
        return best
    }

    private fun offsetToSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-12) return Vec2(a.x - p.x, a.y - p.y)
        val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0.0, 1.0)
        return Vec2(a.x + t * vx - p.x, a.y + t * vy - p.y)
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
