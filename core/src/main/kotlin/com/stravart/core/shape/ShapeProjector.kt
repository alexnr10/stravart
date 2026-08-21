package com.stravart.core.shape

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.cos
import kotlin.math.sin

/** Où poser la forme par rapport au point de départ choisi par l'utilisateur. */
enum class AnchorMode {
    /** Le parcours commence (et finit) exactement au point choisi. */
    START,

    /** Le point choisi est le centre de la forme ; pratique pour rester dans un quartier. */
    CENTER,
}

/** Pose une forme normalisée sur la carte. */
object ShapeProjector {

    /**
     * Projette [shape] autour de [anchor] de façon à ce que le tracé idéal mesure
     * exactement [distanceMeters].
     *
     * @param rotationDeg rotation horaire de la forme, en degrés (0 = orientation d'origine).
     * @param mirrored inverse la forme sur l'axe est-ouest (utile pour les formes asymétriques).
     */
    fun project(
        shape: ShapePath,
        anchor: LatLon,
        distanceMeters: Double,
        rotationDeg: Double = 0.0,
        mode: AnchorMode = AnchorMode.START,
        mirrored: Boolean = false,
    ): List<LatLon> {
        require(distanceMeters > 0) { "la distance doit être positive" }
        val pts = shape.renderedPoints
        require(pts.size >= 2) { "forme invalide" }

        // La longueur normalisée fixe le facteur d'échelle : scale * length = distance.
        val scale = distanceMeters / shape.length

        val theta = Math.toRadians(-rotationDeg) // sens horaire à l'écran
        val cosT = cos(theta)
        val sinT = sin(theta)

        val local = pts.map { p ->
            val x = if (mirrored) -p.x else p.x
            val y = p.y
            doubleArrayOf((x * cosT - y * sinT) * scale, (x * sinT + y * cosT) * scale)
        }

        val originX: Double
        val originY: Double
        when (mode) {
            AnchorMode.START -> {
                originX = local.first()[0]
                originY = local.first()[1]
            }
            AnchorMode.CENTER -> {
                var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                for (v in local) {
                    if (v[0] < minX) minX = v[0]
                    if (v[0] > maxX) maxX = v[0]
                    if (v[1] < minY) minY = v[1]
                    if (v[1] > maxY) maxY = v[1]
                }
                originX = (minX + maxX) / 2
                originY = (minY + maxY) / 2
            }
        }

        return local.map { Geo.offset(anchor, it[0] - originX, it[1] - originY) }
    }
}
