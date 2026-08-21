package com.stravart.core.shape

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Point du plan normalisé de la forme (x vers l'est, y vers le nord). */
data class Pt(val x: Double, val y: Double)

/** Rectangle englobant dans le plan normalisé. */
data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val centerX: Double get() = (minX + maxX) / 2
    val centerY: Double get() = (minY + maxY) / 2
}

/**
 * Un trait continu représentant la forme à dessiner sur la carte.
 *
 * Les points sont *normalisés* : centrés sur l'origine et mis à l'échelle pour que la
 * plus grande dimension du rectangle englobant vaille 1. La forme n'a donc pas de
 * taille propre — c'est la distance demandée par l'utilisateur qui la fixe.
 *
 * Une forme est un **trait unique** : un itinéraire ne peut pas lever le crayon.
 */
class ShapePath private constructor(
    val points: List<Pt>,
    val closed: Boolean,
) {

    /** Longueur du tracé dans le plan normalisé (sans unité). */
    val length: Double by lazy {
        var total = 0.0
        val pts = renderedPoints
        for (i in 1 until pts.size) {
            total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        total
    }

    /** Les points, avec retour au point de départ si la forme est fermée. */
    val renderedPoints: List<Pt> by lazy {
        if (closed && points.size > 1 && points.first() != points.last()) points + points.first()
        else points
    }

    val bounds: Box by lazy {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        Box(minX, minY, maxX, maxY)
    }

    /** Rééchantillonne le tracé en [count] points régulièrement espacés. */
    fun resampled(count: Int): ShapePath {
        require(count >= 2) { "count doit valoir au moins 2" }
        val pts = renderedPoints
        if (pts.size < 2) return this

        val cumulative = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cumulative[i] = cumulative[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val total = cumulative.last()
        if (total <= 0.0) return this

        val out = ArrayList<Pt>(count)
        var seg = 1
        for (i in 0 until count) {
            val target = total * i / (count - 1)
            while (seg < pts.size - 1 && cumulative[seg] < target) seg++
            val segStart = cumulative[seg - 1]
            val segLen = cumulative[seg] - segStart
            val t = if (segLen <= 0.0) 0.0 else (target - segStart) / segLen
            val a = pts[seg - 1]
            val b = pts[seg]
            out += Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        // Une forme fermée est stockée sans doublon final : on retire le point de bouclage.
        return ShapePath(if (closed) out.dropLast(1) else out, closed)
    }

    companion object {
        /** Nombre de points minimal exigé pour qu'une forme soit exploitable. */
        const val MIN_POINTS = 3

        /**
         * Construit une forme normalisée à partir de points bruts (repère quelconque,
         * y vers le haut). Les doublons consécutifs sont supprimés.
         */
        fun of(raw: List<Pt>, closed: Boolean): ShapePath {
            val deduped = dedupe(raw)
            require(deduped.size >= 2) { "une forme demande au moins 2 points distincts" }

            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in deduped) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2
            val extent = max(maxX - minX, maxY - minY)
            require(extent > 0.0) { "la forme est dégénérée (tous les points sont confondus)" }

            return ShapePath(deduped.map { Pt((it.x - cx) / extent, (it.y - cy) / extent) }, closed)
        }

        /** Construit une forme depuis un repère écran (y vers le bas), qu'on retourne. */
        fun fromScreen(raw: List<Pt>, closed: Boolean): ShapePath =
            of(raw.map { Pt(it.x, -it.y) }, closed)

        private fun dedupe(raw: List<Pt>): List<Pt> {
            if (raw.size < 2) return raw
            val out = ArrayList<Pt>(raw.size)
            out += raw[0]
            for (i in 1 until raw.size) {
                val prev = out.last()
                if (abs(raw[i].x - prev.x) > 1e-9 || abs(raw[i].y - prev.y) > 1e-9) out += raw[i]
            }
            return out
        }
    }
}
