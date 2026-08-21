package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Choisit les points de passage à soumettre au moteur de routage.
 *
 * Un échantillonnage régulier le long du tracé paraît naturel, mais il **rabote les
 * angles** : sur un triangle, les trois sommets tombent entre deux échantillons et
 * disparaissent. On perd à la fois la ressemblance (un triangle arrondi est un
 * cercle) et la maîtrise de la distance, puisque le raccourci raccourcit le tracé de
 * façon imprévisible d'une itération à l'autre.
 *
 * On garde donc d'abord les sommets — les extrémités et les virages marqués — puis on
 * répartit le budget restant entre eux, proportionnellement à leur longueur.
 */
object WaypointSampler {

    /** Au-delà de cet angle, un changement de direction est considéré comme un sommet. */
    const val DEFAULT_CORNER_THRESHOLD_DEG = 25.0

    /** Deux points de passage plus proches que cela n'apportent rien au routage. */
    private const val MIN_SPACING_METERS = 15.0

    /**
     * @param count budget de points de passage ; le résultat en contient au plus autant.
     * @return au moins les deux extrémités, et les sommets les plus marqués en priorité.
     */
    fun sample(
        path: List<LatLon>,
        count: Int,
        cornerThresholdDeg: Double = DEFAULT_CORNER_THRESHOLD_DEG,
    ): List<LatLon> {
        require(count >= 2) { "il faut au moins 2 points de passage" }
        if (path.size < 2) return path

        val cumulative = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(path[i - 1], path[i])
        }
        if (cumulative.last() <= 0.0) return listOf(path.first(), path.last())

        val anchors = anchorIndices(path, count, cornerThresholdDeg)
        val budget = count - anchors.size
        val extras = distribute(budget, anchors, cumulative)

        val builder = Builder(count)
        for (i in 0 until anchors.size - 1) {
            builder.add(path[anchors[i]], isAnchor = true)
            val from = cumulative[anchors[i]]
            val to = cumulative[anchors[i + 1]]
            val slots = extras[i]
            for (k in 1..slots) {
                val target = from + (to - from) * k / (slots + 1)
                builder.add(interpolate(path, cumulative, target), isAnchor = false)
            }
        }
        builder.add(path[anchors.last()], isAnchor = true)
        return builder.points
    }

    /**
     * Assemble la liste finale en garantissant un espacement minimal : un point
     * intermédiaire trop proche est écarté, et un sommet chasse le point
     * intermédiaire qui le précède de trop près plutôt que de lui céder la place.
     */
    private class Builder(capacity: Int) {
        val points = ArrayList<LatLon>(capacity)
        private val anchors = ArrayList<Boolean>(capacity)

        fun add(point: LatLon, isAnchor: Boolean) {
            if (!isAnchor) {
                val last = points.lastOrNull() ?: return
                if (Geo.distance(last, point) < MIN_SPACING_METERS) return
                points += point
                anchors += false
                return
            }
            while (points.isNotEmpty() && !anchors.last() &&
                Geo.distance(points.last(), point) < MIN_SPACING_METERS
            ) {
                points.removeAt(points.lastIndex)
                anchors.removeAt(anchors.lastIndex)
            }
            if (points.isNotEmpty() && Geo.distance(points.last(), point) < 1e-3) return
            points += point
            anchors += true
        }
    }

    /** Indices à conserver coûte que coûte : les extrémités puis les virages les plus francs. */
    private fun anchorIndices(path: List<LatLon>, count: Int, thresholdDeg: Double): List<Int> {
        val corners = ArrayList<Pair<Int, Double>>()
        for (i in 1 until path.size - 1) {
            val turn = turnAngleDeg(path[i - 1], path[i], path[i + 1])
            if (turn >= thresholdDeg) corners += i to turn
        }

        val room = count - 2
        val kept = if (corners.size <= room) {
            corners.map { it.first }
        } else {
            corners.sortedByDescending { it.second }.take(room.coerceAtLeast(0)).map { it.first }.sorted()
        }
        return (listOf(0) + kept + listOf(path.lastIndex)).distinct()
    }

    /**
     * Répartit [budget] points intermédiaires entre les intervalles délimités par les
     * ancres, au prorata de leur longueur (méthode du plus fort reste).
     */
    private fun distribute(budget: Int, anchors: List<Int>, cumulative: DoubleArray): IntArray {
        val gaps = anchors.size - 1
        val result = IntArray(gaps)
        if (budget <= 0 || gaps <= 0) return result

        val lengths = DoubleArray(gaps) { cumulative[anchors[it + 1]] - cumulative[anchors[it]] }
        val total = lengths.sum()
        if (total <= 0.0) return result

        var assigned = 0
        val remainders = ArrayList<Pair<Int, Double>>(gaps)
        for (i in 0 until gaps) {
            val exact = budget * lengths[i] / total
            val whole = exact.toInt()
            result[i] = whole
            assigned += whole
            remainders += i to (exact - whole)
        }
        remainders.sortedByDescending { it.second }.take(budget - assigned).forEach { result[it.first]++ }
        return result
    }

    /** Point du tracé situé à l'abscisse [target] de la mesure cumulée [scale]. */
    private fun interpolate(path: List<LatLon>, scale: DoubleArray, target: Double): LatLon {
        var lo = 0
        var hi = scale.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (scale[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo == 0) lo = 1
        val previous = lo - 1
        val span = scale[lo] - scale[previous]
        val t = if (span <= 0.0) 0.0 else (target - scale[previous]) / span
        val a = path[previous]
        val b = path[lo]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Angle de changement de direction en B, en degrés (0 = tout droit). */
    private fun turnAngleDeg(a: LatLon, b: LatLon, c: LatLon): Double {
        val v1 = Geo.toLocal(b, a)
        val v2 = Geo.toLocal(b, c)
        val cross = (-v1.x) * v2.y - (-v1.y) * v2.x
        val dot = (-v1.x) * v2.x + (-v1.y) * v2.y
        return Math.toDegrees(abs(atan2(cross, dot)))
    }

    /** Nombre de points de passage souhaitable pour un tracé donné. */
    fun budgetFor(lengthMeters: Double, spacingMeters: Double, min: Int, max: Int): Int =
        ((lengthMeters / spacingMeters).roundToInt() + 1).coerceIn(min, max)
}
