package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.RoutingException
import com.stravart.core.shape.ShapeProjector
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Le cœur de l'application : transformer « cette forme, cette distance, ce départ »
 * en un itinéraire praticable.
 *
 * La méthode tient en trois temps :
 *
 * 1. **Poser la forme.** On projette la forme normalisée autour du point de départ,
 *    à l'échelle qui lui donne exactement la distance demandée.
 * 2. **Coller aux routes.** On échantillonne des points de passage le long de ce
 *    tracé idéal et on demande au moteur de routage de les relier par des rues et
 *    des chemins. Le résultat ressemble à la forme, mais il est *plus long* : suivre
 *    la voirie oblige à des détours.
 * 3. **Corriger l'échelle.** On rétrécit (ou agrandit) la forme proportionnellement à
 *    l'écart constaté, et on recommence. Deux ou trois itérations suffisent en
 *    général à tomber dans la tolérance demandée, chacune coûtant un appel réseau.
 */
class RouteGenerator(private val engine: RoutingEngine) {

    /** Nombre minimal de points de passage : en dessous, la forme n'est plus lisible. */
    private val minWaypoints = 8

    private val minScale = 0.25
    private val maxScale = 4.0

    fun generate(
        request: RouteRequest,
        onProgress: (RouteProgress) -> Unit = {},
    ): GeneratedRoute {
        val target = request.distanceMeters
        // Sans collage aux routes, la forme projetée fait déjà pile la bonne longueur.
        val maxAttempts = if (engine.snapsToRoads) request.maxAttempts else 1

        // Le budget de points de passage est arrêté une fois pour toutes, d'après la
        // distance demandée. Le recalculer à chaque itération ferait varier
        // l'échantillonnage en même temps que l'échelle, et la distance mesurée
        // sauterait d'une itération à l'autre au lieu de converger.
        val budget = waypointCount(target, request)

        var scale = 1.0
        // Encadrement : la plus grande échelle jugée trop courte et la plus petite
        // jugée trop longue. Dès qu'on tient les deux, on dichotomise.
        var tooShort: Double? = null
        var tooLong: Double? = null
        var best: Attempt? = null
        var failure: RoutingException? = null

        for (attempt in 1..maxAttempts) {
            onProgress(RouteProgress(attempt, maxAttempts, progressMessage(attempt, best)))

            val ideal = ShapeProjector.project(
                shape = request.shape,
                anchor = request.start,
                distanceMeters = target * scale,
                rotationDeg = request.rotationDeg,
                mode = request.anchorMode,
                mirrored = request.mirrored,
            )
            // Un moteur qui ne colle pas aux routes peut suivre la forme au point
            // près : inutile de la dégrader en la réduisant à des points de passage.
            val waypoints = if (engine.snapsToRoads) {
                WaypointSampler.sample(ideal, budget)
            } else {
                ideal
            }

            val routed = try {
                engine.route(waypoints, request.activity)
            } catch (e: RoutingException) {
                failure = e
                break
            }

            val error = abs(routed.distanceMeters - target) / target
            if (best == null || error < best.error) {
                best = Attempt(ideal, routed, error, attempt)
            }
            if (error <= request.toleranceRatio) break

            if (routed.distanceMeters < target) {
                tooShort = maxOf(tooShort ?: scale, scale)
            } else {
                tooLong = minOf(tooLong ?: scale, scale)
            }

            val low = tooShort
            val high = tooLong
            val next = if (low != null && high != null && high > low) {
                // Le réseau routier ne répond pas de façon continue : une petite
                // réduction de la forme peut ne rien changer, puis faire sauter un
                // pâté de maisons entier. La dichotomie encaisse ces marches, là où
                // une correction proportionnelle se met à osciller.
                (low + high) / 2
            } else {
                // Tant qu'on n'encadre pas la cible : si l'itinéraire fait 20 % de
                // trop, on rétrécit la forme d'autant.
                scale * (target / routed.distanceMeters).coerceIn(0.5, 2.0)
            }

            val clamped = next.coerceIn(minScale, maxScale)
            if (abs(clamped - scale) < 1e-4) break
            scale = clamped
        }

        val result = best ?: throw (failure ?: RoutingException("Aucun itinéraire n'a pu être calculé."))

        return GeneratedRoute(
            points = result.routed.points,
            elevations = result.routed.elevations,
            idealShape = result.ideal,
            distanceMeters = result.routed.distanceMeters,
            ascentMeters = result.routed.ascentMeters ?: result.routed.elevations?.let(::ascentOf),
            fidelity = ShapeFidelity.evaluate(result.ideal, result.routed.points),
            activity = request.activity,
            engineName = engine.displayName,
            snappedToRoads = engine.snapsToRoads,
            attempts = result.attempt,
            name = request.name ?: defaultName(request.distanceMeters),
        )
    }

    private fun waypointCount(idealLength: Double, request: RouteRequest): Int =
        WaypointSampler.budgetFor(
            lengthMeters = idealLength,
            spacingMeters = request.effectiveSpacingMeters,
            min = minWaypoints,
            max = engine.maxWaypoints,
        )

    private fun progressMessage(attempt: Int, best: Attempt?): String = when {
        attempt == 1 -> "Calcul de l'itinéraire…"
        best == null -> "Nouvel essai…"
        best.routed.distanceMeters > 0 ->
            "Ajustement de la distance (essai $attempt)…"
        else -> "Ajustement (essai $attempt)…"
    }

    private fun ascentOf(elevations: List<Double>): Double {
        var gain = 0.0
        for (i in 1 until elevations.size) {
            val delta = elevations[i] - elevations[i - 1]
            if (delta > 0) gain += delta
        }
        return gain
    }

    private fun defaultName(distanceMeters: Double): String =
        "StravArt ${(distanceMeters / 100).roundToInt() / 10.0} km"

    private data class Attempt(
        val ideal: List<LatLon>,
        val routed: RoutedPath,
        val error: Double,
        val attempt: Int,
    )
}
