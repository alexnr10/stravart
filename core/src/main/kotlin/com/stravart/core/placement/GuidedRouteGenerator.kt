package com.stravart.core.placement

import com.stravart.core.geo.LatLon
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.route.RouteGenerator
import com.stravart.core.route.RouteRequest
import com.stravart.core.routing.ActivityType

/** D'où viennent les rues d'un secteur. Séparé pour que les tests s'en passent. */
fun interface RoadSource {
    fun ways(center: LatLon, radiusMeters: Double, activity: ActivityType): List<RoadWay>
}

/** Ce qu'a donné la recherche guidée. */
data class GuidedRoute(
    val route: GeneratedRoute,
    val placement: Placement,
    /** Le parcours qu'aurait donné le placement demandé, s'il a pu être calculé. */
    val asked: GeneratedRoute?,
    /** Vrai quand la recherche a trouvé mieux que ce qui était demandé. */
    val improved: Boolean,
    val candidatesRouted: Int,
    /** Renseigné quand la recherche n'a pas pu avoir lieu ; le parcours reste valide. */
    val unavailableReason: String? = null,
)

/**
 * Cherche le meilleur placement d'une forme, puis le confronte au vrai routage.
 *
 * L'ordre compte : la note de [PlacementSearch] désigne des candidats prometteurs
 * sans rien garantir — elle ignore la connexité, une impasse bien orientée y note
 * aussi bien qu'une rue traversante. Seul le moteur tranche.
 *
 * **Le placement demandé reste toujours en lice.** Sans cette précaution la recherche
 * dégraderait le pire cas de dix-sept pour cent, mesuré ; avec elle, elle ne peut par
 * construction rien rendre de pire que le parcours d'aujourd'hui.
 *
 * [RouteGenerator] n'est pas modifié : cette classe l'appelle, plusieurs fois.
 */
class GuidedRouteGenerator(
    private val generator: RouteGenerator,
    private val roads: RoadSource,
) {

    /** Étapes rapportées à l'appelant, pour qu'une attente de plusieurs secondes s'explique. */
    enum class Step { FETCHING_ROADS, SCORING, ROUTING }

    fun generate(
        request: RouteRequest,
        options: PlacementSearchOptions = PlacementSearchOptions(),
        onProgress: (Step, Int, Int) -> Unit = { _, _, _ -> },
    ): GuidedRoute {
        val radius = networkRadius(request, options)
        val network = if (radius == null) {
            null
        } else {
            onProgress(Step.FETCHING_ROADS, 0, 1)
            runCatching { RoadNetwork.of(roads.ways(request.start, radius, request.activity)) }
                .getOrNull()
        }

        if (network == null || network.segmentCount == 0) {
            val reason = if (radius == null) {
                "Parcours trop long pour explorer les rues alentour : au-delà de " +
                    "${(OVERPASS_MAX_RADIUS_METERS / 1000).toInt()} km de rayon, le secteur à " +
                    "télécharger devient déraisonnable."
            } else {
                "Rues alentour indisponibles ; le parcours a été calculé au placement demandé."
            }
            val fallback = generator.generate(request)
            return GuidedRoute(
                route = fallback,
                placement = asked(request),
                asked = fallback,
                improved = false,
                candidatesRouted = 1,
                unavailableReason = reason,
            )
        }

        onProgress(Step.SCORING, 0, 1)
        val suggestions = PlacementSearch.search(
            network = network,
            request = PlacementRequest(
                shape = request.shape,
                anchor = request.start,
                distanceMeters = request.distanceMeters,
                mode = request.anchorMode,
                mirrored = request.mirrored,
            ),
            options = options,
        ).map { it.placement }

        // Le placement demandé en tête : s'il fait jeu égal, c'est lui qui l'emporte,
        // et l'on ne propose pas de bouger pour rien.
        // La recherche peut proposer exactement le placement demandé ; le router deux
        // fois coûterait une requête pour rien.
        val askedPlacement = asked(request)
        val runOff = listOf(askedPlacement) + suggestions.filter { it != askedPlacement }
        var best: Pair<Placement, GeneratedRoute>? = null
        var askedRoute: GeneratedRoute? = null
        var routed = 0
        var lastFailure: Exception? = null

        runOff.forEachIndexed { index, placement ->
            onProgress(Step.ROUTING, index, runOff.size)
            val outcome = runCatching { generator.generate(request.withPlacement(placement)) }
            routed++
            val route = outcome.getOrElse {
                // Un candidat qui ne boucle pas n'invalide pas les autres ; seul un
                // échec général doit remonter.
                lastFailure = it as? Exception ?: RuntimeException(it)
                return@forEachIndexed
            }
            if (index == 0) askedRoute = route
            val current = best
            if (current == null || route.fidelity.meanDeviationMeters < current.second.fidelity.meanDeviationMeters) {
                best = placement to route
            }
        }

        val winner = best ?: throw (lastFailure ?: IllegalStateException("aucun placement n'a abouti"))
        return GuidedRoute(
            route = winner.second,
            placement = winner.first,
            asked = askedRoute,
            // Se prononcer sur le placement et non sur le tracé : quand le placement
            // demandé ne boucle pas du tout, il n'y a pas de tracé auquel comparer,
            // et pourtant la recherche a bel et bien trouvé autre chose.
            improved = winner.first != askedPlacement,
            candidatesRouted = routed,
        )
    }

    private fun asked(request: RouteRequest) = Placement(
        anchor = request.start,
        rotationDeg = request.rotationDeg,
        distanceMeters = request.distanceMeters,
    )

    private fun RouteRequest.withPlacement(placement: Placement) = copy(
        start = placement.anchor,
        rotationDeg = placement.rotationDeg,
        distanceMeters = placement.distanceMeters,
    )

    companion object {
        /** Doit rester en accord avec la limite du client Overpass. */
        const val OVERPASS_MAX_RADIUS_METERS = 4_000.0

        /** De quoi couvrir les rues qui affleurent la forme, sans plus. */
        const val NETWORK_MARGIN_METERS = 250.0

        /**
         * Rayon de rues à télécharger, ou `null` si le secteur nécessaire dépasse ce
         * qu'un service partagé peut raisonnablement rendre.
         *
         * La forme, une fois posée, tient dans un cercle de `distance / longueur
         * normalisée` : c'est exact et non estimé, la longueur normalisée étant
         * précisément ce qui fixe l'échelle dans [com.stravart.core.shape.ShapeProjector].
         */
        fun networkRadius(request: RouteRequest, options: PlacementSearchOptions): Double? {
            val extent = request.distanceMeters * (1.0 + options.distanceTolerance) / request.shape.length
            val radius = options.radiusMeters + extent + NETWORK_MARGIN_METERS
            return if (radius > OVERPASS_MAX_RADIUS_METERS) null else radius
        }
    }
}
