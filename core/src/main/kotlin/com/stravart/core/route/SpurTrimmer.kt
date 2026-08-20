package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.geo.Vec2
import kotlin.math.abs
import kotlin.math.floor

/**
 * Supprime les allers-retours d'un itinéraire.
 *
 * Un point de passage qui tombe au fond d'une impasse oblige le moteur de routage à
 * y entrer puis à en ressortir par le même chemin. Le détour est correct du point de
 * vue du routage — il fallait bien atteindre ce point — mais il ruine le dessin, et
 * personne ne court volontairement 300 m dans une impasse pour faire demi-tour.
 *
 * On repère ces excursions après coup : ce sont les portions qui repartent d'un point
 * pour y revenir **sans rien entourer**. Un aller-retour n'enferme aucune surface,
 * là où un vrai tour de pâté de maisons en enferme une comparable au carré de sa
 * longueur. C'est ce critère qui distingue les deux, et non la longueur.
 *
 * Le tracé qui reste suit toujours des rues réelles : couper un aller-retour revient
 * à passer devant l'entrée de l'impasse sans y entrer.
 */
object SpurTrimmer {

    /** Deux points plus proches que cela sont considérés comme le même endroit. */
    const val DEFAULT_TOLERANCE_METERS = 25.0

    /** En deçà, l'aller-retour relève du bruit cartographique et son retrait n'apporte rien. */
    private const val MIN_SPUR_METERS = 40.0

    /**
     * Un « détour » qui représente plus d'un tiers du parcours n'en est plus un ;
     * cette borne protège aussi la boucle elle-même, qui revient par construction à
     * son point de départ.
     */
    private const val MAX_SPUR_RATIO = 0.35

    /**
     * Borne absolue, qui prime sur la précédente. Un aller-retour dans une impasse
     * est un accident local : quelques centaines de mètres, pas davantage. Sans ce
     * plafond, un tiers d'un parcours de 30 km ferait dix kilomètres — de quoi
     * effacer un lobe entier de la forme sous prétexte qu'il enferme peu de surface.
     */
    private const val MAX_SPUR_METERS = 1_200.0

    /**
     * Surface enfermée, rapportée au carré de la longueur, en dessous de laquelle la
     * portion est jugée dégénérée. Un cercle vaut 0,080 et un tour de pâté de maisons
     * carré 0,063 : le seuil laisse une marge confortable.
     */
    private const val DEGENERATE_AREA_RATIO = 0.01

    private const val MAX_PASSES = 3

    /**
     * @param keptIndices indices conservés dans le tracé d'origine, dans l'ordre.
     *   Les deux extrémités en font toujours partie, pour que la boucle reste fermée
     *   sur le point de départ choisi.
     */
    data class Result(
        val keptIndices: List<Int>,
        val removedMeters: Double,
        val spurCount: Int,
    ) {
        val trimmed: Boolean get() = spurCount > 0

        fun <T> select(values: List<T>): List<T> = keptIndices.map { values[it] }
    }

    fun trim(points: List<LatLon>, toleranceMeters: Double = DEFAULT_TOLERANCE_METERS): Result {
        if (points.size < 4) return Result(points.indices.toList(), 0.0, 0)

        var indices = points.indices.toList()
        var removed = 0.0
        var count = 0

        // Retirer un aller-retour peut en révéler un autre, jusqu'ici masqué par le
        // premier ; quelques passes suffisent à épuiser le phénomène.
        repeat(MAX_PASSES) {
            val pass = singlePass(indices.map { points[it] }, toleranceMeters)
            if (!pass.trimmed) return Result(indices, removed, count)
            indices = pass.keptIndices.map { indices[it] }
            removed += pass.removedMeters
            count += pass.spurCount
        }
        return Result(indices, removed, count)
    }

    private fun singlePass(points: List<LatLon>, tolerance: Double): Result {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return Result(points.indices.toList(), 0.0, 0)
        val maxSpur = kotlin.math.min(total * MAX_SPUR_RATIO, MAX_SPUR_METERS)

        val origin = points.first()
        val local = points.map { Geo.toLocal(origin, it) }
        val index = SpatialIndex(local, tolerance)

        val kept = ArrayList<Int>(points.size)
        var removed = 0.0
        var spurs = 0
        var i = 0
        while (i < points.size) {
            kept += i
            val j = index.farthestReturnTo(i, cumulative, maxSpur, tolerance)
            if (j != null && isDegenerate(local, i, j, cumulative)) {
                // On garde le point d'arrivée : le raccord entre les deux extrémités
                // de l'excursion mesure au plus la tolérance, et reste sur la voie.
                // Quand les deux se confondent, inutile de conserver les deux — sauf
                // s'il s'agit du départ, qui doit rester le premier point du tracé.
                if (kept.size > 1 && local[i].distanceTo(local[j]) < 1.0) {
                    kept.removeAt(kept.lastIndex)
                }
                removed += cumulative[j] - cumulative[i]
                spurs++
                i = j
            } else {
                i++
            }
        }
        return Result(kept, removed, spurs)
    }

    /** Une excursion est dégénérée quand elle n'enferme presque aucune surface. */
    private fun isDegenerate(local: List<Vec2>, from: Int, to: Int, cumulative: DoubleArray): Boolean {
        val length = cumulative[to] - cumulative[from]
        if (length < MIN_SPUR_METERS) return false

        var twiceArea = 0.0
        for (k in from until to) {
            twiceArea += local[k].x * local[k + 1].y - local[k + 1].x * local[k].y
        }
        // La portion se referme sur elle-même : le segment de fermeture complète l'aire.
        twiceArea += local[to].x * local[from].y - local[from].x * local[to].y
        val area = abs(twiceArea) / 2.0

        return area <= DEGENERATE_AREA_RATIO * length * length
    }

    /**
     * Grille régulière permettant de retrouver, pour un point donné, les points du
     * tracé qui repassent au même endroit — sans comparer toutes les paires.
     */
    private class SpatialIndex(private val local: List<Vec2>, private val cell: Double) {

        private val buckets = HashMap<Long, MutableList<Int>>()

        init {
            local.forEachIndexed { index, point ->
                buckets.getOrPut(key(point)) { ArrayList() } += index
            }
        }

        /**
         * Indice le plus éloigné, dans la fenêtre autorisée, où le tracé revient à
         * moins de [tolerance] du point [from]. Prendre le plus éloigné absorbe d'un
         * coup les allers-retours imbriqués.
         */
        fun farthestReturnTo(
            from: Int,
            cumulative: DoubleArray,
            maxSpur: Double,
            tolerance: Double,
        ): Int? {
            val point = local[from]
            val cx = floor(point.x / cell).toInt()
            val cy = floor(point.y / cell).toInt()

            var best = -1
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val bucket = buckets[key(cx + dx, cy + dy)] ?: continue
                    for (candidate in bucket) {
                        if (candidate <= best || candidate <= from) continue
                        val span = cumulative[candidate] - cumulative[from]
                        if (span > maxSpur || span < MIN_SPUR_METERS) continue
                        if (point.distanceTo(local[candidate]) > tolerance) continue
                        best = candidate
                    }
                }
            }
            return best.takeIf { it >= 0 }
        }

        private fun key(point: Vec2) = key(floor(point.x / cell).toInt(), floor(point.y / cell).toInt())

        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    }
}
