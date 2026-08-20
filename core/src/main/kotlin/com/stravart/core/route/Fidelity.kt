package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** À quel point l'itinéraire réel ressemble à la forme demandée. */
data class Fidelity(
    /** Écart moyen entre le tracé idéal et l'itinéraire, en mètres. */
    val meanDeviationMeters: Double,
    /** Pire écart constaté, en mètres. */
    val maxDeviationMeters: Double,
    /** Note de ressemblance, de 0 (méconnaissable) à 100 (superposé). */
    val score: Int,
)

/**
 * Mesure la ressemblance entre la forme visée et l'itinéraire finalement obtenu.
 *
 * L'écart est calculé dans les deux sens — les points de la forme qui s'éloignent de
 * l'itinéraire *et* les détours de l'itinéraire hors de la forme comptent — puis
 * rapporté à la taille de la forme : 100 m d'écart sur une boucle de 2 km n'ont rien
 * à voir avec 100 m sur une boucle de 40 km.
 */
object ShapeFidelity {

    /** Écart relatif considéré comme « moyen » : il vaut exactement 50 / 100. */
    private const val HALF_SCORE_RATIO = 0.12

    /** Raideur de la courbe de notation. */
    private const val STEEPNESS = 1.585

    private const val SAMPLES = 160

    fun evaluate(ideal: List<LatLon>, actual: List<LatLon>): Fidelity {
        if (ideal.size < 2 || actual.size < 2) return Fidelity(0.0, 0.0, 0)

        val origin = ideal.first()
        val idealSamples = Geo.resample(ideal, SAMPLES)
        val actualSamples = Geo.resample(actual, SAMPLES)

        val forward = idealSamples.map { Geo.distanceToPath(it, actual, origin) }
        val backward = actualSamples.map { Geo.distanceToPath(it, ideal, origin) }

        val mean = (forward.average() + backward.average()) / 2.0
        val worst = max(forward.maxOrNull() ?: 0.0, backward.maxOrNull() ?: 0.0)

        val ratio = mean / characteristicSize(ideal)
        val score = 100.0 / (1.0 + (ratio / HALF_SCORE_RATIO).pow(STEEPNESS))

        return Fidelity(
            meanDeviationMeters = mean,
            maxDeviationMeters = worst,
            score = score.coerceIn(0.0, 100.0).toInt(),
        )
    }

    /** Taille caractéristique de la forme : moyenne géométrique de son emprise. */
    private fun characteristicSize(points: List<LatLon>): Double {
        val origin = points.first()
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) {
            val v = Geo.toLocal(origin, p)
            if (v.x < minX) minX = v.x
            if (v.x > maxX) maxX = v.x
            if (v.y < minY) minY = v.y
            if (v.y > maxY) maxY = v.y
        }
        val size = sqrt(max(maxX - minX, 1.0) * max(maxY - minY, 1.0))
        return max(size, 1.0)
    }
}
