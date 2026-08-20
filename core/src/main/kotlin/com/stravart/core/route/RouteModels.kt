package com.stravart.core.route

import com.stravart.core.geo.Geo
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
    /** Nombre maximal d'appels au moteur de routage pour ajuster la distance. */
    val maxAttempts: Int = 5,
    /**
     * Tentatives de replacement des points de passage que le moteur n'a pas honorés.
     * Chacune coûte un appel réseau et n'est conservée que si elle rapproche
     * réellement le tracé de la forme.
     */
    val relocationPasses: Int = 1,
    /** Espacement visé entre points de passage ; `null` = valeur par défaut de l'activité. */
    val waypointSpacingMeters: Double? = null,
    /**
     * Part maximale du parcours acceptée en aller-retour. Au-delà, le quartier ne
     * permet pas de boucler et la génération échoue plutôt que de livrer un tracé
     * qui refait deux fois les mêmes rues.
     */
    val maxOverlapRatio: Double = 0.30,
    val name: String? = null,
) {
    init {
        require(distanceMeters >= 200) { "la distance doit valoir au moins 200 m" }
        require(toleranceRatio > 0) { "la tolérance doit être positive" }
        require(maxAttempts >= 1) { "il faut au moins une tentative" }
        require(relocationPasses >= 0) { "le nombre de replacements ne peut être négatif" }
        require(maxOverlapRatio in 0.0..1.0) { "la part d'aller-retour tolérée doit être une fraction" }
    }

    /**
     * Espacement effectif entre points de passage.
     *
     * C'est ce qui décide de la fidélité au tracé voulu : entre deux points de
     * passage, le moteur est libre de préférer la belle avenue à la petite rue qui
     * longeait la forme, et il ne s'en prive pas. Les resserrer le ramène sur la
     * ligne — jusqu'à un palier, atteint autour de 120 m, au-delà duquel c'est la
     * maille du réseau lui-même qui limite et non plus l'échantillonnage.
     */
    val effectiveSpacingMeters: Double
        get() = waypointSpacingMeters ?: when (activity) {
            ActivityType.RUN -> 120.0
            ActivityType.BIKE -> 200.0
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
    /** Part du parcours empruntée deux fois, entre 0 et 1. */
    val overlapRatio: Double,
    /**
     * Portions de la forme que l'itinéraire n'a pas pu suivre, faute de voie assez
     * proche — un fleuve, un parc fermé, un grand bâtiment. Les montrer vaut mieux
     * que de laisser croire à un défaut de calcul.
     */
    val unfollowed: List<UnfollowedStretch>,
    /** De quoi comprendre ce que le moteur a réellement fait. */
    val diagnostics: RouteDiagnostics,
    /** Nombre d'allers-retours retirés du tracé rendu par le moteur. */
    val removedSpurs: Int,
    val activity: ActivityType,
    val engineName: String,
    val snappedToRoads: Boolean,
    /** Nombre d'appels au moteur de routage effectivement réalisés. */
    val attempts: Int,
    val name: String,
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val start: LatLon get() = points.first()

    /** Part de la forme laissée de côté, entre 0 et 1. */
    val unfollowedRatio: Double
        get() {
            val total = Geo.pathLength(idealShape)
            if (total <= 0.0) return 0.0
            return (unfollowed.sumOf { it.lengthMeters } / total).coerceIn(0.0, 1.0)
        }
}

/**
 * Ce que le moteur a réellement fait, par opposition à ce qu'on lui a demandé.
 *
 * Un repli — moins de points de passage qu'espéré, un autre profil que celui de
 * l'activité — change le résultat du tout au tout. Le laisser invisible, c'est
 * condamner l'utilisateur à chercher pourquoi son parcours de course contourne le
 * parc qu'il voulait traverser.
 */
data class RouteDiagnostics(
    /** Nombre de points de passage visé, avant tout repli. */
    val requestedWaypoints: Int,
    /** Nombre réellement routé par le moteur, replis compris. */
    val usedWaypoints: Int,
    /** Profil de routage effectivement employé. */
    val profileUsed: String?,
    /** Points de passage replacés parce que le moteur ne les avait pas honorés. */
    val relocatedWaypoints: Int,
    /** Points de passage écartés faute de voie praticable à proximité. */
    val discardedWaypoints: Int,
)

/**
 * Levée quand le réseau de rues autour du départ ne permet pas de boucler sans
 * refaire une part importante du parcours à l'envers.
 *
 * Ce n'est pas une panne : aucune forme, aucune orientation ni aucune distance ne
 * corrigera un quartier en impasses. Seul un autre point de départ le peut.
 */
class UnsuitableAreaException(
    message: String,
    /** Part du parcours qui aurait été parcourue deux fois. */
    val overlapRatio: Double,
) : Exception(message)

/** Avancement d'une génération, pour tenir l'interface informée. */
data class RouteProgress(
    val attempt: Int,
    val maxAttempts: Int,
    val message: String,
)
