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
 *
 * Au-delà d'un certain écart, on ne déplace plus : on **écarte**. Un point tombé au
 * milieu d'un fleuve n'est sur aucune voie ; le moteur le rattache tout de même à une
 * berge, et ce point imposé décide alors de l'endroit où l'on traverse — parfois un
 * pont bien plus loin que celui qui longeait la forme. Le retirer rend au moteur la
 * liberté de traverser au moins cher, c'est-à-dire en général au plus près.
 */
object WaypointRelocator {

    /** Au-delà de cet écart, le point de passage n'a manifestement pas été honoré. */
    const val DEFAULT_THRESHOLD_METERS = 120.0

    /**
     * Au-delà, le point n'est manifestement sur aucune voie : le déplacer serait un
     * pari sur du vide, mieux vaut cesser de l'imposer.
     */
    const val DEFAULT_DROP_THRESHOLD_METERS = 300.0

    /** On ne projette pas un point de passage à l'autre bout de la ville. */
    private const val MAX_SHIFT_METERS = 700.0

    data class Relocation(
        val waypoints: List<LatLon>,
        val movedCount: Int,
        val droppedCount: Int,
    )

    /**
     * @param strength fraction de l'écart constaté appliquée en sens inverse.
     * @return `null` si aucun point de passage ne méritait d'être corrigé.
     */
    fun relocate(
        waypoints: List<LatLon>,
        route: List<LatLon>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS,
        dropThresholdMeters: Double = DEFAULT_DROP_THRESHOLD_METERS,
        strength: Double = 1.0,
    ): Relocation? {
        if (waypoints.size < 3 || route.size < 2) return null

        val origin = waypoints.first()
        val offsets = ShapeDeviation.offsets(waypoints, route, origin)

        val kept = ArrayList<LatLon>(waypoints.size)
        var moved = 0
        var dropped = 0
        var justDropped = false

        waypoints.forEachIndexed { index, waypoint ->
            // Les extrémités sont le départ et l'arrivée : elles ne bougent jamais.
            if (index == 0 || index == waypoints.lastIndex) {
                kept += waypoint
                justDropped = false
                return@forEachIndexed
            }

            val offset = offsets[index]
            val distance = hypot(offset.x, offset.y)
            when {
                distance <= thresholdMeters -> {
                    kept += waypoint
                    justDropped = false
                }
                // Jamais deux d'affilée : sans point de passage sur une longue
                // portion, le moteur reprendrait toute sa liberté et s'égarerait.
                distance >= dropThresholdMeters && !justDropped -> {
                    dropped++
                    justDropped = true
                }
                else -> {
                    val shift = (distance * strength).coerceAtMost(MAX_SHIFT_METERS)
                    val scale = shift / distance
                    kept += Geo.offset(waypoint, -offset.x * scale, -offset.y * scale)
                    moved++
                    justDropped = false
                }
            }
        }

        if (moved == 0 && dropped == 0) return null
        return Relocation(kept, moved, dropped)
    }
}
