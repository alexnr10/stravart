package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import kotlin.math.hypot

/**
 * Déplace les points de passage que le moteur n'a pas su honorer.
 *
 * Quand une portion de forme tombe là où aucune voie praticable ne passe — un
 * fleuve, un jardin fermé aux vélos, une emprise ferroviaire — le moteur rattache
 * ces points à la voie de contournement la plus proche, et le tracé s'en va avec
 * eux. En **ajouter** ne sert à rien : les nouveaux se rattachent au même endroit.
 *
 * Ce qu'il faut, c'est les **déplacer**. Si l'itinéraire est parti trois cents mètres
 * au sud, c'est que la voie qu'il a trouvée est au sud ; pousser le point d'autant
 * vers le nord amène le moteur à chercher de ce côté-là, où se trouve peut-être la
 * rue qui longeait vraiment la forme.
 *
 * Le pari peut échouer — le point déplacé peut retomber au même endroit, ou pire.
 * C'est pourquoi l'appelant ne garde le résultat que s'il est meilleur.
 */
object WaypointRelocator {

    /** Au-delà de cet écart, le point de passage n'a manifestement pas été honoré. */
    const val DEFAULT_THRESHOLD_METERS = 120.0

    /** On ne projette pas un point de passage à l'autre bout de la ville. */
    private const val MAX_SHIFT_METERS = 700.0

    data class Relocation(val waypoints: List<LatLon>, val movedCount: Int)

    /**
     * @param strength fraction de l'écart constaté appliquée en sens inverse.
     * @return `null` si aucun point de passage ne méritait d'être déplacé.
     */
    fun relocate(
        waypoints: List<LatLon>,
        route: List<LatLon>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
        strength: Double = 1.0,
    ): Relocation? {
        if (waypoints.size < 3 || route.size < 2) return null

        val origin = waypoints.first()
        val offsets = ShapeDeviation.offsets(waypoints, route, origin)

        var moved = 0
        val relocated = waypoints.mapIndexed { index, waypoint ->
            // Les extrémités sont le départ et l'arrivée : elles ne bougent pas.
            if (index == 0 || index == waypoints.lastIndex) return@mapIndexed waypoint

            val offset = offsets[index]
            val distance = hypot(offset.x, offset.y)
            if (distance <= thresholdMeters) return@mapIndexed waypoint

            val shift = (distance * strength).coerceAtMost(MAX_SHIFT_METERS)
            val scale = shift / distance
            moved++
            Geo.offset(waypoint, -offset.x * scale, -offset.y * scale)
        }

        return if (moved == 0) null else Relocation(relocated, moved)
    }
}
