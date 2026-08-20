package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Mesure la part du parcours parcourue deux fois.
 *
 * Certains quartiers ne permettent tout simplement pas de boucler sans revenir sur
 * ses pas : lotissement en peigne, presqu'île, hameau desservi par une seule route.
 * Aucun réglage de forme n'y changera rien, et mieux vaut le dire que livrer un
 * parcours où l'on refait le même kilomètre à l'envers.
 *
 * Le tracé est parcouru par pas réguliers ; chaque pas marque une case d'une grille.
 * Retomber sur une case déjà marquée **loin en arrière dans le parcours** signale un
 * retour sur ses pas — la contrainte d'éloignement évitant de compter les pas
 * successifs, qui visitent forcément les mêmes cases.
 */
object RouteOverlap {

    /** Largeur du couloir : deux passages distants de moins que cela sont le même. */
    private const val CORRIDOR_METERS = 12.0

    /**
     * Écart minimal, le long du parcours, entre deux passages pour qu'ils comptent
     * comme un retour sur ses pas plutôt que comme la continuité du tracé.
     */
    private const val MIN_GAP_METERS = 150.0

    /** Fraction du parcours refaite en sens inverse, entre 0 et 1. */
    fun measure(points: List<LatLon>): Double {
        if (points.size < 2) return 0.0
        val origin = points.first()
        val local = points.map { Geo.toLocal(origin, it) }

        val lastVisit = HashMap<Long, Double>()
        var travelled = 0.0
        var retraced = 0.0

        for (i in 1 until local.size) {
            val a = local[i - 1]
            val b = local[i]
            val segment = a.distanceTo(b)
            if (segment <= 0.0) continue

            // Un segment peut traverser plusieurs cases : on l'échantillonne.
            val steps = ceil(segment / CORRIDOR_METERS).toInt().coerceAtLeast(1)
            val stepLength = segment / steps
            for (step in 0 until steps) {
                val t = (step + 0.5) / steps
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                val position = travelled + stepLength * (step + 0.5)

                if (seenEarlier(lastVisit, x, y, position)) retraced += stepLength
                lastVisit[key(x, y)] = position
            }
            travelled += segment
        }

        return if (travelled <= 0.0) 0.0 else (retraced / travelled).coerceIn(0.0, 1.0)
    }

    private fun seenEarlier(
        lastVisit: Map<Long, Double>,
        x: Double,
        y: Double,
        position: Double,
    ): Boolean {
        val cx = floor(x / CORRIDOR_METERS).toInt()
        val cy = floor(y / CORRIDOR_METERS).toInt()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val previous = lastVisit[key(cx + dx, cy + dy)] ?: continue
                if (abs(position - previous) >= MIN_GAP_METERS) return true
            }
        }
        return false
    }

    private fun key(x: Double, y: Double): Long =
        key(floor(x / CORRIDOR_METERS).toInt(), floor(y / CORRIDOR_METERS).toInt())

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
}
