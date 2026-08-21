package com.stravart.core.placement

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/** Une voie du réseau, telle que la renvoie OpenStreetMap : une suite de points. */
data class RoadWay(val points: List<LatLon>)

/**
 * Le réseau viaire d'un secteur, projeté en mètres et indexé pour être interrogé des
 * millions de fois.
 *
 * L'objet répond à une seule question, mais très vite : « à quel prix ce point de la
 * forme, orienté ainsi, se rattache-t-il à une voie ? ». Le prix mêle la distance et
 * le désaccord d'orientation, car une rue perpendiculaire à la forme ne permet pas
 * de la suivre, si proche soit-elle.
 *
 * Les segments sont rangés dans des tableaux parallèles plutôt que dans des objets :
 * à ce nombre d'interrogations, l'allocation et le déréférencement dominent le calcul.
 */
class RoadNetwork private constructor(
    private val originLat: Double,
    private val originLon: Double,
    private val metersPerLat: Double,
    private val metersPerLon: Double,
    private val ax: DoubleArray,
    private val ay: DoubleArray,
    private val bx: DoubleArray,
    private val by: DoubleArray,
    /** Orientation du segment, repliée sur [0, 180) : une rue se parcourt dans les deux sens. */
    private val angle: DoubleArray,
    private val cellMeters: Double,
    private val minX: Double,
    private val minY: Double,
    private val cols: Int,
    private val rows: Int,
    private val cellStart: IntArray,
    private val cellItems: IntArray,
) {

    val segmentCount: Int get() = ax.size

    /** Abscisse locale, en mètres à l'est de l'origine. */
    fun localX(p: LatLon): Double = (p.lon - originLon) * metersPerLon

    /** Ordonnée locale, en mètres au nord de l'origine. */
    fun localY(p: LatLon): Double = (p.lat - originLat) * metersPerLat

    /**
     * Prix de rattachement d'un point de la forme au réseau.
     *
     * @param bearingDeg orientation locale de la forme en ce point, repliée sur [0, 180).
     * @param maxRadius au-delà, on considère qu'aucune voie ne dessert ce point ;
     *   [maxRadius] ne peut dépasser la taille de maille, sinon le voisinage de neuf
     *   cellules cesserait de garantir qu'on a tout vu.
     * @param bearingWeight ce que coûte, en mètres, un désaccord d'orientation complet.
     *   C'est le seul réglage vraiment subjectif : il dit combien de détour on accepte
     *   pour une rue qui va dans le bon sens.
     * @return le prix, plafonné à `maxRadius + bearingWeight` si rien ne convient.
     */
    fun matchCost(
        x: Double,
        y: Double,
        bearingDeg: Double,
        maxRadius: Double,
        bearingWeight: Double,
    ): Double {
        val cap = maxRadius + bearingWeight
        if (ax.isEmpty()) return cap

        val col = floor((x - minX) / cellMeters).toInt()
        val row = floor((y - minY) / cellMeters).toInt()
        var best = cap

        var r = row - 1
        while (r <= row + 1) {
            if (r in 0 until rows) {
                var c = col - 1
                while (c <= col + 1) {
                    if (c in 0 until cols) {
                        val cell = r * cols + c
                        var i = cellStart[cell]
                        val end = cellStart[cell + 1]
                        while (i < end) {
                            val s = cellItems[i]
                            val d = pointToSegment(x, y, ax[s], ay[s], bx[s], by[s])
                            if (d < best) {
                                val cost = d + bearingWeight * misalignment(angle[s], bearingDeg)
                                if (cost < best) best = cost
                            }
                            i++
                        }
                    }
                    c++
                }
            }
            r++
        }
        return min(best, cap)
    }

    companion object {

        /**
         * Taille de maille par défaut.
         *
         * Elle vaut le rayon de recherche : c'est la condition pour que les neuf
         * cellules autour d'un point suffisent à garantir qu'aucune voie n'a été
         * manquée. Plus fine, l'index pèse davantage ; plus large, chaque
         * interrogation parcourt trop de segments.
         */
        const val DEFAULT_CELL_METERS = 120.0

        fun of(ways: List<RoadWay>, cellMeters: Double = DEFAULT_CELL_METERS): RoadNetwork {
            require(cellMeters > 0) { "la maille doit être positive" }
            val all = ways.flatMap { it.points }
            val origin = if (all.isEmpty()) LatLon(0.0, 0.0) else Geo.boundsCenter(all)
            val metersPerLat = Geo.metersPerDegreeLat(origin.lat)
            val metersPerLon = Geo.metersPerDegreeLon(origin.lat)

            val ax = ArrayList<Double>()
            val ay = ArrayList<Double>()
            val bx = ArrayList<Double>()
            val by = ArrayList<Double>()
            val angle = ArrayList<Double>()

            for (way in ways) {
                val pts = way.points
                for (i in 1 until pts.size) {
                    val x0 = (pts[i - 1].lon - origin.lon) * metersPerLon
                    val y0 = (pts[i - 1].lat - origin.lat) * metersPerLat
                    val x1 = (pts[i].lon - origin.lon) * metersPerLon
                    val y1 = (pts[i].lat - origin.lat) * metersPerLat
                    // Un segment de longueur nulle n'a pas d'orientation : il fausserait
                    // le désaccord au lieu de l'éclairer.
                    if (hypot(x1 - x0, y1 - y0) < 0.5) continue
                    ax += x0; ay += y0; bx += x1; by += y1
                    angle += foldAngle(Math.toDegrees(atan2(y1 - y0, x1 - x0)))
                }
            }

            if (ax.isEmpty()) {
                return RoadNetwork(
                    origin.lat, origin.lon, metersPerLat, metersPerLon,
                    DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0),
                    cellMeters, 0.0, 0.0, 1, 1, intArrayOf(0, 0), IntArray(0),
                )
            }

            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in ax.indices) {
                minX = minOf(minX, ax[i], bx[i]); maxX = maxOf(maxX, ax[i], bx[i])
                minY = minOf(minY, ay[i], by[i]); maxY = maxOf(maxY, ay[i], by[i])
            }
            val cols = (((maxX - minX) / cellMeters).toInt() + 1).coerceAtLeast(1)
            val rows = (((maxY - minY) / cellMeters).toInt() + 1).coerceAtLeast(1)

            // Un segment est inscrit dans toutes les cellules que traverse son rectangle
            // englobant : le seul moyen d'être certain qu'une recherche à neuf cellules
            // ne rate pas un segment qui n'affleure qu'un coin.
            val counts = IntArray(cols * rows + 1)
            fun forEachCell(i: Int, action: (Int) -> Unit) {
                val c0 = floor((minOf(ax[i], bx[i]) - minX) / cellMeters).toInt().coerceIn(0, cols - 1)
                val c1 = floor((maxOf(ax[i], bx[i]) - minX) / cellMeters).toInt().coerceIn(0, cols - 1)
                val r0 = floor((minOf(ay[i], by[i]) - minY) / cellMeters).toInt().coerceIn(0, rows - 1)
                val r1 = floor((maxOf(ay[i], by[i]) - minY) / cellMeters).toInt().coerceIn(0, rows - 1)
                for (r in r0..r1) for (c in c0..c1) action(r * cols + c)
            }

            for (i in ax.indices) forEachCell(i) { counts[it + 1]++ }
            for (i in 1 until counts.size) counts[i] += counts[i - 1]
            val start = counts.copyOf()
            val items = IntArray(counts.last())
            val cursor = counts.copyOf()
            for (i in ax.indices) forEachCell(i) { items[cursor[it]++] = i }

            return RoadNetwork(
                origin.lat, origin.lon, metersPerLat, metersPerLon,
                ax.toDoubleArray(), ay.toDoubleArray(), bx.toDoubleArray(), by.toDoubleArray(),
                angle.toDoubleArray(),
                cellMeters, minX, minY, cols, rows, start, items,
            )
        }

        /** Ramène un angle quelconque dans [0, 180) : une rue n'a pas de sens de parcours. */
        fun foldAngle(deg: Double): Double {
            var a = deg % 180.0
            if (a < 0) a += 180.0
            return a
        }

        /** Désaccord d'orientation, de 0 (parallèle) à 1 (perpendiculaire). */
        fun misalignment(a: Double, b: Double): Double {
            val d = abs(a - b)
            return min(d, 180.0 - d) / 90.0
        }

        private fun pointToSegment(
            px: Double, py: Double,
            x0: Double, y0: Double,
            x1: Double, y1: Double,
        ): Double {
            val vx = x1 - x0
            val vy = y1 - y0
            val len2 = vx * vx + vy * vy
            if (len2 < 1e-9) return hypot(px - x0, py - y0)
            var t = ((px - x0) * vx + (py - y0) * vy) / len2
            if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
            return hypot(px - (x0 + t * vx), py - (y0 + t * vy))
        }
    }
}
