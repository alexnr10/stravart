package com.stravart.core.route

import com.stravart.core.geo.LatLon
import com.stravart.core.routing.ActivityType
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapePath

/** Tout ce qu'il faut savoir pour dessiner un parcours. */
data class RouteRequest(
    val shape: ShapePath,
    val start: LatLon,
    val distanceMeters: Double,
    val activity: ActivityType,
    /** Rotation horaire de la forme, en degrés. */
    val rotationDeg: Double = 0.0,
    /** Effet miroir est-ouest. */
    val mirrored: Boolean = false,
    val anchorMode: AnchorMode = AnchorMode.START,
    /** Écart relatif accepté sur la distance finale (0,03 = 3 %). */
    val toleranceRatio: Double = 0.03,
    /** Nombre maximal d'appels au moteur de routage. */
    val maxAttempts: Int = 5,
    /** Espacement visé entre points de passage ; `null` = valeur par défaut de l'activité. */
    val waypointSpacingMeters: Double? = null,
    val name: String? = null,
) {
    init {
        require(distanceMeters >= 200) { "la distance doit valoir au moins 200 m" }
        require(toleranceRatio > 0) { "la tolérance doit être positive" }
        require(maxAttempts >= 1) { "il faut au moins une tentative" }
    }

    /** Espacement effectif entre points de passage. */
    val effectiveSpacingMeters: Double
        get() = waypointSpacingMeters ?: when (activity) {
            ActivityType.RUN -> 300.0
            ActivityType.BIKE -> 600.0
        }
}

/** Le parcours produit, prêt à être affiché puis exporté. */
data class GeneratedRoute(
    /** Le tracé réellement suivi (collé aux routes si le moteur le permet). */
    val points: List<LatLon>,
    /** Altitudes associées à [points], si disponibles. */
    val elevations: List<Double>?,
    /** La forme visée, conservée pour l'afficher en surimpression sur la carte. */
    val idealShape: List<LatLon>,
    val distanceMeters: Double,
    val ascentMeters: Double?,
    val fidelity: Fidelity,
    val activity: ActivityType,
    val engineName: String,
    val snappedToRoads: Boolean,
    /** Nombre d'appels au moteur de routage effectivement réalisés. */
    val attempts: Int,
    val name: String,
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val start: LatLon get() = points.first()
}

/** Avancement d'une génération, pour tenir l'interface informée. */
data class RouteProgress(
    val attempt: Int,
    val maxAttempts: Int,
    val message: String,
)
