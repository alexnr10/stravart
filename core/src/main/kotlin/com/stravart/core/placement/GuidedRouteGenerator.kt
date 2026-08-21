package com.stravart.core.placement

import com.stravart.core.geo.LatLon
import com.stravart.core.route.GeneratedRoute
import com.stravart.core.route.RouteGenerator
import com.stravart.core.route.RouteRequest
import com.stravart.core.routing.ActivityType
import com.stravart.core.shape.ShapePath
import kotlin.math.hypot

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
        // Le rayon demandé est ramené à ce que le budget permet plutôt que refusé :
        // chercher un départ à cinq cents mètres vaut mieux que ne pas chercher.
        val affordable = affordableSearchRadius(request, options.distanceTolerance)
        val effective = if (affordable == null) {
            options
        } else {
            options.copy(radiusMeters = minOf(options.radiusMeters, affordable))
        }
        val radius = affordable?.let { shapeReach(request, options.distanceTolerance) + effective.radiusMeters + NETWORK_MARGIN_METERS }

        val network = if (radius == null) {
            null
        } else {
            onProgress(Step.FETCHING_ROADS, 0, 1)
            runCatching { RoadNetwork.of(roads.ways(request.start, radius, request.activity)) }
                .getOrNull()
        }

        if (network == null || network.segmentCount == 0) {
            val reason = if (radius == null) {
                "Parcours trop long pour explorer les rues alentour : la forme couvre à elle " +
                    "seule plus que le secteur qu'un service de données cartographiques " +
                    "partagé peut rendre d'un coup."
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
            options = effective,
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
        /**
         * Rayon maximal de rues téléchargeables.
         *
         * Cinq kilomètres et demi font un carré de onze sur onze, soit à peu près
         * Paris intra-muros et sa première couronne. C'est ce qui laisse une boucle
         * de dix kilomètres tenir dans le secteur, quelle que soit sa forme.
         *
         * **Cette valeur est une estimation, non une mesure** : le service Overpass
         * n'est pas joignable depuis l'environnement de développement. Elle est
         * choisie du côté optimiste parce que l'échec est sans gravité — un secteur
         * refusé ou trop lent fait simplement retomber sur le placement demandé.
         */
        const val OVERPASS_MAX_RADIUS_METERS = 5_500.0

        /** De quoi couvrir les rues qui affleurent la forme, sans plus. */
        const val NETWORK_MARGIN_METERS = 250.0

        /**
         * Distance maximale entre le départ et un point de la forme posée.
         *
         * C'est la **diagonale** de l'emprise et non sa largeur : la forme est ancrée
         * par un point de son contour, si bien que le point opposé peut se trouver
         * dans le coin le plus éloigné du rectangle englobant. Mesurer la largeur
         * seule rendait un réseau qui ne couvrait pas toute la forme, et la note se
         * dégradait en silence à l'extrémité la plus lointaine.
         */
        fun shapeReach(request: RouteRequest, distanceTolerance: Double): Double =
            shapeReach(request.shape, request.distanceMeters, distanceTolerance)

        fun shapeReach(shape: ShapePath, distanceMeters: Double, distanceTolerance: Double): Double {
            val diagonal = hypot(shape.bounds.width, shape.bounds.height)
            return distanceMeters * (1.0 + distanceTolerance) * diagonal / shape.length
        }

        /**
         * Ce qui reste du budget pour déplacer le départ, une fois la forme couverte.
         *
         * Zéro signifie « on peut encore chercher l'orientation, mais pas le départ » ;
         * `null`, que même la forme seule ne tient pas dans le secteur.
         */
        fun affordableSearchRadius(request: RouteRequest, distanceTolerance: Double): Double? =
            affordableSearchRadius(request.shape, request.distanceMeters, distanceTolerance)

        fun affordableSearchRadius(
            shape: ShapePath,
            distanceMeters: Double,
            distanceTolerance: Double,
        ): Double? {
            val rest = OVERPASS_MAX_RADIUS_METERS -
                shapeReach(shape, distanceMeters, distanceTolerance) - NETWORK_MARGIN_METERS
            return if (rest < 0.0) null else rest
        }
    }
}
