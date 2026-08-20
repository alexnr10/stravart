package com.stravart.core.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Géométrie locale.
 *
 * Toutes les opérations utilisent une projection équirectangulaire locale calée sur
 * l'ellipsoïde WGS84 (mètres par degré de latitude / longitude évalués à la latitude
 * de référence). Sur les distances manipulées ici — un parcours tient dans quelques
 * dizaines de kilomètres — l'erreur reste très inférieure à 0,1 %, et surtout la
 * projection et la mesure de distance sont *cohérentes entre elles* : une forme
 * projetée pour mesurer 10 km sera bien mesurée à 10 km.
 */
object Geo {

    /** Mètres par degré de latitude à la latitude [latDeg] (WGS84). */
    fun metersPerDegreeLat(latDeg: Double): Double {
        val p = Math.toRadians(latDeg)
        return 111132.92 - 559.82 * cos(2 * p) + 1.175 * cos(4 * p) - 0.0023 * cos(6 * p)
    }

    /** Mètres par degré de longitude à la latitude [latDeg] (WGS84). */
    fun metersPerDegreeLon(latDeg: Double): Double {
        val p = Math.toRadians(latDeg)
        return 111412.84 * cos(p) - 93.5 * cos(3 * p) + 0.118 * cos(5 * p)
    }

    /** Distance en mètres entre deux points proches. */
    fun distance(a: LatLon, b: LatLon): Double {
        val midLat = (a.lat + b.lat) / 2.0
        val dx = (b.lon - a.lon) * metersPerDegreeLon(midLat)
        val dy = (b.lat - a.lat) * metersPerDegreeLat(midLat)
        return hypot(dx, dy)
    }

    /** Longueur cumulée d'une polyligne, en mètres. */
    fun pathLength(points: List<LatLon>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        return total
    }

    /** Point obtenu en se décalant de [eastMeters] vers l'est et [northMeters] vers le nord. */
    fun offset(origin: LatLon, eastMeters: Double, northMeters: Double): LatLon {
        val lat = origin.lat + northMeters / metersPerDegreeLat(origin.lat)
        val lon = origin.lon + eastMeters / metersPerDegreeLon(origin.lat)
        return LatLon(
            lat.coerceIn(-89.9, 89.9),
            ((lon + 540.0) % 360.0) - 180.0,
        )
    }

    /** Coordonnées locales (est, nord) en mètres de [p] par rapport à [origin]. */
    fun toLocal(origin: LatLon, p: LatLon): Vec2 = Vec2(
        x = (p.lon - origin.lon) * metersPerDegreeLon(origin.lat),
        y = (p.lat - origin.lat) * metersPerDegreeLat(origin.lat),
    )

    /** Centre du rectangle englobant d'un ensemble de points. */
    fun boundsCenter(points: List<LatLon>): LatLon {
        require(points.isNotEmpty()) { "liste de points vide" }
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        return LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    }

    /**
     * Rééchantillonne une polyligne en [count] points régulièrement espacés le long
     * du tracé (le premier et le dernier point d'origine sont conservés).
     */
    fun resample(points: List<LatLon>, count: Int): List<LatLon> {
        require(count >= 2) { "count doit valoir au moins 2" }
        val clean = points.dedupeConsecutive()
        if (clean.size < 2) return List(count) { clean.firstOrNull() ?: return emptyList() }

        val cumulative = DoubleArray(clean.size)
        for (i in 1 until clean.size) {
            cumulative[i] = cumulative[i - 1] + distance(clean[i - 1], clean[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return List(count) { clean.first() }

        val out = ArrayList<LatLon>(count)
        var seg = 1
        for (i in 0 until count) {
            val target = total * i / (count - 1)
            while (seg < clean.size - 1 && cumulative[seg] < target) seg++
            val segStart = cumulative[seg - 1]
            val segLen = cumulative[seg] - segStart
            val t = if (segLen <= 0.0) 0.0 else (target - segStart) / segLen
            val a = clean[seg - 1]
            val b = clean[seg]
            out += LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
        return out
    }

    /** Distance en mètres entre [p] et la polyligne [path]. */
    fun distanceToPath(p: LatLon, path: List<LatLon>, origin: LatLon = path.first()): Double {
        require(path.isNotEmpty()) { "polyligne vide" }
        val local = toLocal(origin, p)
        if (path.size == 1) return local.distanceTo(toLocal(origin, path[0]))
        var best = Double.MAX_VALUE
        var prev = toLocal(origin, path[0])
        for (i in 1 until path.size) {
            val cur = toLocal(origin, path[i])
            best = min(best, pointToSegment(local, prev, cur))
            prev = cur
        }
        return best
    }

    private fun pointToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-12) return p.distanceTo(a)
        var t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / len2
        t = t.coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * vx), p.y - (a.y + t * vy))
    }

    private fun List<LatLon>.dedupeConsecutive(): List<LatLon> {
        if (size < 2) return this
        val out = ArrayList<LatLon>(size)
        out += this[0]
        for (i in 1 until size) {
            val prev = out.last()
            if (abs(this[i].lat - prev.lat) > 1e-9 || abs(this[i].lon - prev.lon) > 1e-9) out += this[i]
        }
        return out
    }
}

/** Vecteur plan en mètres (est, nord). */
data class Vec2(val x: Double, val y: Double) {
    fun distanceTo(other: Vec2): Double = hypot(other.x - x, other.y - y)
}
