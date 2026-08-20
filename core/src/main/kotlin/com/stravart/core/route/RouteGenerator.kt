package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.RoutingException
import com.stravart.core.shape.ShapeProjector
import kotlin.math.abs
import kotlin.math.max
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
 * 3. **Nettoyer et corriger l'échelle.** On retire les allers-retours — un point de
 *    passage tombé dans une impasse fait entrer puis ressortir le tracé par le même
 *    chemin — puis on rétrécit (ou agrandit) la forme proportionnellement à l'écart
 *    de distance constaté, et on recommence. Deux ou trois itérations suffisent en
 *    général, chacune coûtant un appel réseau.
 *
 * Reste le cas où le quartier ne se prête pas à l'exercice : quand le tracé retenu
 * refait une part importante de lui-même en sens inverse, la génération échoue par
 * [UnsuitableAreaException] plutôt que de livrer un parcours dénué de sens.
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
            onProgress(RouteProgress(attempt, maxAttempts, progressMessage(attempt)))

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

            val raw = try {
                engine.route(waypoints, request.activity)
            } catch (e: RoutingException) {
                failure = e
                break
            }
            val routed = if (engine.snapsToRoads) clean(raw) else Cleaned(raw, spurs = 0)

            val error = abs(routed.path.distanceMeters - target) / target
            if (best == null || error < best.error) {
                best = Attempt(ideal, waypoints, routed, error, attempt)
            }
            if (error <= request.toleranceRatio) break

            if (routed.path.distanceMeters < target) {
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
                scale * (target / routed.path.distanceMeters).coerceIn(0.5, 2.0)
            }

            val clamped = next.coerceIn(minScale, maxScale)
            if (abs(clamped - scale) < 1e-4) break
            scale = clamped
        }

        var result = best ?: throw (failure ?: RoutingException("Aucun itinéraire n'a pu être calculé."))
        var relocated = 0

        // Une fois la distance en place : les points de passage que le moteur n'a pas
        // honorés sont replacés de l'autre côté, là où se trouve peut-être la voie
        // qui longeait vraiment la forme.
        if (engine.snapsToRoads) {
            repeat(request.relocationPasses) { pass ->
                onProgress(
                    RouteProgress(
                        result.attempt + pass + 1,
                        maxAttempts + request.relocationPasses,
                        "Recalage sur la forme…",
                    ),
                )
                val candidate = relocate(result, request) ?: return@repeat
                if (improves(candidate.first, result, request)) {
                    result = candidate.first
                    relocated += candidate.second
                }
            }
        }

        // Le nettoyage a retiré les allers-retours évitables ; ce qu'il reste de
        // parcouru deux fois est imposé par le réseau lui-même.
        val overlap = if (engine.snapsToRoads) RouteOverlap.measure(result.routed.path.points) else 0.0
        if (overlap > request.maxOverlapRatio) {
            throw UnsuitableAreaException(
                "Impossible de boucler ici sans revenir sur ses pas : " +
                    "${(overlap * 100).roundToInt()} % du parcours emprunterait deux fois les mêmes voies.",
                overlapRatio = overlap,
            )
        }

        return GeneratedRoute(
            points = result.routed.path.points,
            elevations = result.routed.path.elevations,
            idealShape = result.ideal,
            distanceMeters = result.routed.path.distanceMeters,
            ascentMeters = ascentOrNull(result.routed.path),
            fidelity = ShapeFidelity.evaluate(result.ideal, result.routed.path.points),
            overlapRatio = overlap,
            unfollowed = ShapeCoverage.analyse(result.ideal, result.routed.path.points).stretches,
            removedSpurs = result.routed.spurs,
            diagnostics = RouteDiagnostics(
                waypoints = result.waypoints,
                requestedWaypoints = budget,
                profileUsed = result.routed.path.profileUsed,
                relocatedWaypoints = relocated,
            ),
            activity = request.activity,
            engineName = engine.displayName,
            snappedToRoads = engine.snapsToRoads,
            attempts = result.attempt,
            name = request.name ?: defaultName(request.distanceMeters),
        )
    }

    /**
     * Replace les points de passage restés en travers, puis redemande un itinéraire.
     *
     * @return `null` si aucun point n'avait besoin d'être déplacé, ou si le moteur a
     *   refusé la nouvelle demande — auquel cas le tracé déjà obtenu reste valable.
     */
    private fun relocate(current: Attempt, request: RouteRequest): Pair<Attempt, Int>? {
        val relocation = WaypointRelocator.relocate(
            waypoints = current.waypoints,
            route = current.routed.path.points,
        ) ?: return null

        val raw = try {
            engine.route(relocation.waypoints, request.activity)
        } catch (e: RoutingException) {
            return null
        }
        val routed = clean(raw)
        val error = abs(routed.path.distanceMeters - request.distanceMeters) / request.distanceMeters
        return Attempt(
            current.ideal,
            relocation.waypoints,
            routed,
            error,
            current.attempt + 1,
        ) to relocation.movedCount
    }

    /**
     * Un tracé replacé n'est retenu que s'il colle mieux à la forme *sans* rien
     * abîmer d'autre : ni la distance demandée, ni la continuité du parcours.
     *
     * Déplacer un point de passage est un pari — la voie espérée de l'autre côté
     * n'existe pas toujours. Cette garde fait que le pari ne coûte jamais rien
     * d'autre qu'un appel réseau.
     */
    private fun improves(candidate: Attempt, current: Attempt, request: RouteRequest): Boolean {
        if (candidate.error > max(request.toleranceRatio, current.error)) return false

        val before = ShapeFidelity.evaluate(current.ideal, current.routed.path.points)
        val after = ShapeFidelity.evaluate(candidate.ideal, candidate.routed.path.points)
        if (after.meanDeviationMeters >= before.meanDeviationMeters) return false

        val overlapBefore = RouteOverlap.measure(current.routed.path.points)
        val overlapAfter = RouteOverlap.measure(candidate.routed.path.points)
        return overlapAfter <= overlapBefore + OVERLAP_SLACK
    }

    /**
     * Retire les allers-retours du tracé rendu par le moteur.
     *
     * La distance annoncée par le moteur ne vaut plus dès qu'on a coupé dans sa
     * géométrie : on la recalcule alors sur ce qu'il reste. Tant qu'on n'a rien
     * touché, celle du moteur fait autorité — il connaît le réseau mieux que notre
     * approximation plane.
     */
    private fun clean(raw: RoutedPath): Cleaned {
        val trim = SpurTrimmer.trim(raw.points)
        if (!trim.trimmed) return Cleaned(raw, spurs = 0)

        val points = trim.select(raw.points)
        if (points.size < 2) return Cleaned(raw, spurs = 0)
        val elevations = raw.elevations?.let { trim.select(it) }

        return Cleaned(
            RoutedPath(
                points = points,
                distanceMeters = Geo.pathLength(points),
                elevations = elevations,
                ascentMeters = elevations?.let(::ascentOf) ?: raw.ascentMeters,
            ),
            spurs = trim.spurCount,
        )
    }

    /** Dénivelé positif : celui du moteur s'il le fournit, sinon reconstitué. */
    private fun ascentOrNull(path: RoutedPath): Double? =
        path.ascentMeters ?: path.elevations?.let(::ascentOf)

    /** Un tracé débarrassé de ses allers-retours, et le nombre de ceux qui ont sauté. */
    private data class Cleaned(val path: RoutedPath, val spurs: Int)

    private fun waypointCount(idealLength: Double, request: RouteRequest): Int =
        WaypointSampler.budgetFor(
            lengthMeters = idealLength,
            spacingMeters = request.effectiveSpacingMeters,
            min = minWaypoints,
            max = engine.maxWaypoints,
        )

    private fun progressMessage(attempt: Int): String =
        if (attempt == 1) "Calcul de l'itinéraire…" else "Ajustement de la distance (essai $attempt)…"

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

    private class Attempt(
        val ideal: List<LatLon>,
        val waypoints: List<LatLon>,
        val routed: Cleaned,
        val error: Double,
        val attempt: Int,
    )

    private companion object {
        /** Marge tolérée sur le retour sur ses pas, pour ne pas rejeter du bruit. */
        const val OVERLAP_SLACK = 0.01
    }
}
