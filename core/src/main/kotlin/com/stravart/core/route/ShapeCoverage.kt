package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon

/** Portion de la forme que l'itinéraire n'a pas pu suivre. */
data class UnfollowedStretch(
    /** Le morceau de forme concerné, pour le montrer sur la carte. */
    val shapePoints: List<LatLon>,
    val lengthMeters: Double,
    val maxDeviationMeters: Double,
)

/** Ce que l'itinéraire a réussi — ou non — à suivre de la forme demandée. */
data class ShapeCoverageReport(
    val stretches: List<UnfollowedStretch>,
    /** Part de la forme laissée de côté, entre 0 et 1. */
    val unfollowedRatio: Double,
)

/**
 * Repère les endroits où l'itinéraire a dû abandonner la forme.
 *
 * Un fleuve dont les ponts sont espacés, un palais, un grand parc fermé : par
 * endroits il n'existe simplement aucune voie près du tracé voulu, et le moteur
 * contourne. Resserrer les points de passage n'y change rien — ils se collent tous
 * à la même rue de contournement.
 *
 * Plutôt que de laisser croire à un défaut de calcul, on nomme ces portions et on
 * les rend visibles : l'utilisateur voit alors *où* ça coince, et sait quoi déplacer.
 */
object ShapeCoverage {

    /**
     * Au-delà de cet écart, l'itinéraire n'a pas « arrondi » la forme : il est
     * ailleurs. C'est à peu près la distance à laquelle on cesse de reconnaître le
     * dessin sur la carte.
     */
    const val DEFAULT_THRESHOLD_METERS = 100.0

    /** En deçà, l'écart est trop bref pour se voir ou pour se corriger. */
    private const val MIN_STRETCH_METERS = 150.0

    /** Fenêtre de lissage : évite de hacher une même portion en plusieurs morceaux. */
    private const val SMOOTHING_POINTS = 5

    fun analyse(
        ideal: List<LatLon>,
        route: List<LatLon>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
    ): ShapeCoverageReport {
        if (ideal.size < 2 || route.size < 2) return ShapeCoverageReport(emptyList(), 0.0)

        val raw = ShapeDeviation.perPoint(ideal, route)
        // Le lissage sert à délimiter les portions sans les hacher ; l'écart annoncé
        // reste celui mesuré, qu'une moyenne glissante aurait sous-estimé.
        val deviation = ShapeDeviation.smoothed(raw, window = SMOOTHING_POINTS)
        val total = Geo.pathLength(ideal)
        if (total <= 0.0) return ShapeCoverageReport(emptyList(), 0.0)

        val stretches = ArrayList<UnfollowedStretch>()
        var start = -1
        for (index in ideal.indices) {
            val astray = deviation[index] > thresholdMeters
            if (astray && start < 0) start = index
            if (!astray && start >= 0) {
                stretchOf(ideal, raw, start, index - 1)?.let { stretches += it }
                start = -1
            }
        }
        if (start >= 0) stretchOf(ideal, raw, start, ideal.lastIndex)?.let { stretches += it }

        val unfollowed = stretches.sumOf { it.lengthMeters }
        return ShapeCoverageReport(stretches, (unfollowed / total).coerceIn(0.0, 1.0))
    }

    private fun stretchOf(
        ideal: List<LatLon>,
        deviation: DoubleArray,
        from: Int,
        to: Int,
    ): UnfollowedStretch? {
        if (to <= from) return null
        val points = ideal.subList(from, to + 1).toList()
        val length = Geo.pathLength(points)
        if (length < MIN_STRETCH_METERS) return null
        var worst = 0.0
        for (index in from..to) worst = maxOf(worst, deviation[index])
        return UnfollowedStretch(points, length, worst)
    }
}
