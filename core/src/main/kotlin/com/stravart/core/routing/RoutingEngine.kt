package com.stravart.core.routing

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon

/** Type d'activité : influe sur le profil de routage et le contenu du GPX. */
enum class ActivityType(val id: String, val label: String, val gpxType: String) {
    RUN("run", "Course à pied", "running"),
    BIKE("bike", "Vélo", "cycling"),
}

/** Résultat brut d'un calcul d'itinéraire. */
data class RoutedPath(
    val points: List<LatLon>,
    val distanceMeters: Double,
    /** Altitude en mètres pour chaque point, si le moteur la fournit. */
    val elevations: List<Double>? = null,
    /** Dénivelé positif cumulé, si le moteur le fournit. */
    val ascentMeters: Double? = null,
    /**
     * Profil réellement employé.
     *
     * Un moteur peut se rabattre sur un autre profil que celui demandé — et passer
     * ainsi d'un profil piéton à un profil vélo, qui n'emprunte pas les mêmes voies.
     * Le taire reviendrait à laisser l'utilisateur chercher pourquoi son parcours
     * contourne le parc qu'il voulait traverser.
     */
    val profileUsed: String? = null,
    /**
     * Points de passage effectivement soumis. Peut être inférieur au nombre demandé
     * si le moteur a exigé une requête plus courte.
     */
    val waypointsUsed: Int? = null,
) {
    init {
        require(points.size >= 2) { "un itinéraire demande au moins 2 points" }
        require(elevations == null || elevations.size == points.size) {
            "le nombre d'altitudes doit correspondre au nombre de points"
        }
    }
}

/** Erreur remontée par un moteur de routage (réseau, réponse invalide, aucun itinéraire). */
class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Un moteur capable de relier une suite de points de passage en suivant le réseau
 * routier ou les chemins.
 */
interface RoutingEngine {
    val id: String
    val displayName: String

    /** `false` pour le moteur « à vol d'oiseau », qui ne colle pas aux routes. */
    val snapsToRoads: Boolean

    /** Nombre maximal de points de passage acceptés par requête. */
    val maxWaypoints: Int

    /** @throws RoutingException si l'itinéraire ne peut pas être calculé. */
    fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath
}

/**
 * Moteur de repli : relie les points de passage en ligne droite.
 *
 * Il ne demande aucun réseau et produit exactement la forme voulue — mais le tracé
 * traverse allègrement les immeubles. Utile hors connexion, pour prévisualiser, ou
 * quand le tracé sert de simple guide.
 */
object StraightLineEngine : RoutingEngine {
    override val id = "straight"
    override val displayName = "À vol d'oiseau (hors ligne)"
    override val snapsToRoads = false
    override val maxWaypoints = 10_000

    override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
        if (waypoints.size < 2) throw RoutingException("Il faut au moins deux points de passage.")
        return RoutedPath(
            points = waypoints,
            distanceMeters = Geo.pathLength(waypoints),
            profileUsed = "direct",
            waypointsUsed = waypoints.size,
        )
    }
}
