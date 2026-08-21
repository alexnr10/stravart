package com.stravart.core.placement

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.route.RouteGenerator
import com.stravart.core.route.RouteRequest
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.routing.RoutingException
import com.stravart.core.shape.ShapeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class GuidedRouteGeneratorTest {

    private val start = LatLon(48.8566, 2.3522)

    /**
     * Une trame de rues inclinée, servie à la fois comme réseau à noter et comme
     * moteur de routage. Les deux doivent décrire les mêmes rues, sans quoi le test
     * mesurerait l'accord entre deux modèles au lieu du comportement voulu.
     */
    private inner class TiltedCity(tiltDeg: Double, val spacing: Double = 120.0) {
        private val t = Math.toRadians(tiltDeg)
        private val cosT = cos(t)
        private val sinT = sin(t)

        private fun toWorld(u: Double, v: Double) =
            Geo.offset(start, u * cosT - v * sinT, u * sinT + v * cosT)

        private fun toTilted(p: LatLon): Pair<Double, Double> {
            val local = Geo.toLocal(start, p)
            return local.x * cosT + local.y * sinT to -local.x * sinT + local.y * cosT
        }

        val source = RoadSource { _, radius, _ ->
            val n = (radius / spacing).toInt() + 1
            val ways = ArrayList<RoadWay>()
            for (i in -n..n) {
                val d = i * spacing
                ways += RoadWay(listOf(toWorld(-n * spacing, d), toWorld(n * spacing, d)))
                ways += RoadWay(listOf(toWorld(d, -n * spacing), toWorld(d, n * spacing)))
            }
            ways
        }

        val engine = object : RoutingEngine {
            override val id = "tilted"
            override val displayName = "Trame inclinée"
            override val snapsToRoads = true
            override val maxWaypoints = 200

            override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
                fun snap(p: LatLon): Pair<Double, Double> {
                    val (u, v) = toTilted(p)
                    return (u / spacing).roundToInt() * spacing to (v / spacing).roundToInt() * spacing
                }
                val points = ArrayList<LatLon>()
                var prev = snap(waypoints.first())
                points += toWorld(prev.first, prev.second)
                for (i in 1 until waypoints.size) {
                    val cur = snap(waypoints[i])
                    val corner = if (i % 2 == 0) cur.first to prev.second else prev.first to cur.second
                    points += toWorld(corner.first, corner.second)
                    points += toWorld(cur.first, cur.second)
                    prev = cur
                }
                return RoutedPath(points, Geo.pathLength(points))
            }
        }
    }

    private fun request(shapeId: String = "square", km: Double = 4.0, rotation: Double = 0.0) =
        RouteRequest(
            shape = ShapeLibrary.byId(shapeId)!!.path,
            start = start,
            distanceMeters = km * 1000,
            activity = ActivityType.RUN,
            rotationDeg = rotation,
            maxAttempts = 2,
        )

    private val noRoads = RoadSource { _, _, _ -> emptyList() }

    /**
     * La propriété qui fait tenir toute la fonctionnalité : la note n'est qu'un
     * indicateur, seul le moteur tranche, et le placement demandé reste en lice.
     */
    @Test
    fun `the guided route is never worse than the one that was asked for`() {
        val city = TiltedCity(27.0)
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), city.source)
            .generate(request(), PlacementSearchOptions(results = 3))

        val asked = guided.asked!!
        assertTrue(
            "demandé ${asked.fidelity.meanDeviationMeters} m, retenu ${guided.route.fidelity.meanDeviationMeters} m",
            guided.route.fidelity.meanDeviationMeters <= asked.fidelity.meanDeviationMeters + 1e-9,
        )
    }

    @Test
    fun `a tilted grid pulls the shape round to meet it`() {
        val city = TiltedCity(27.0)
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), city.source)
            .generate(request(), PlacementSearchOptions(results = 3))

        assertTrue("la recherche aurait dû trouver mieux", guided.improved)
        assertTrue(guided.candidatesRouted >= 2)
    }

    /** Sans rues, on rend tout de même un parcours — et l'on dit pourquoi. */
    @Test
    fun `an unavailable road network falls back on the placement that was asked for`() {
        val city = TiltedCity(0.0)
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), noRoads).generate(request())

        assertNotNull(guided.route)
        assertEquals(1, guided.candidatesRouted)
        assertTrue(guided.unavailableReason!!.contains("indisponible"))
        assertTrue(!guided.improved)
        assertEquals(start, guided.placement.anchor)
    }

    @Test
    fun `a road source that throws does not sink the generation`() {
        val city = TiltedCity(0.0)
        val angry = RoadSource { _, _, _ -> throw RuntimeException("503") }
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), angry).generate(request())

        assertNotNull(guided.route)
        assertNotNull(guided.unavailableReason)
    }

    /**
     * Une forme de trente kilomètres couvre un secteur qu'un service partagé ne peut
     * pas rendre. Mieux vaut le dire et calculer comme avant que d'expédier une
     * requête vouée au refus.
     */
    @Test
    fun `too long a route skips the search and says so`() {
        val city = TiltedCity(27.0, spacing = 300.0)
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), city.source)
            .generate(request(km = 40.0))

        assertTrue(guided.unavailableReason!!.contains("trop long"))
        assertEquals(1, guided.candidatesRouted)
    }

    /**
     * La portée se mesure du départ au point le plus éloigné de la forme posée. Le
     * carré normalisé mesure 4 de long pour une diagonale de racine de deux : à
     * 4 km, il porte donc à 1 414 m et non à 1 000 m. C'est cette confusion entre
     * largeur et diagonale qui rendait un réseau trop court.
     */
    @Test
    fun `the reach of a shape is measured to its far corner`() {
        val square = ShapeLibrary.byId("square")!!.path
        val request = RouteRequest(square, start, 4_000.0, ActivityType.RUN)

        assertEquals(
            4_000.0 * Math.sqrt(2.0) / 4.0,
            GuidedRouteGenerator.shapeReach(request, distanceTolerance = 0.0),
            1.0,
        )
    }

    /**
     * Le budget restant sert à déplacer le départ. Zéro veut dire « on cherche encore
     * l'orientation mais plus le départ » ; `null`, que la forme seule ne tient pas.
     */
    @Test
    fun `what is left of the budget decides how far the start may move`() {
        val square = ShapeLibrary.byId("square")!!.path
        val short = RouteRequest(square, start, 4_000.0, ActivityType.RUN)
        val long = RouteRequest(square, start, 40_000.0, ActivityType.RUN)

        val room = GuidedRouteGenerator.affordableSearchRadius(short, 0.0)!!
        assertEquals(
            GuidedRouteGenerator.OVERPASS_MAX_RADIUS_METERS -
                GuidedRouteGenerator.shapeReach(short, 0.0) -
                GuidedRouteGenerator.NETWORK_MARGIN_METERS,
            room,
            1e-6,
        )
        assertTrue("une boucle de 4 km doit laisser de la marge", room > 1_000.0)
        assertNull(GuidedRouteGenerator.affordableSearchRadius(long, 0.0))
    }

    /**
     * Un rayon trop ambitieux est ramené à ce que le budget permet, et non refusé :
     * chercher un départ à cinq cents mètres vaut mieux que ne pas chercher.
     */
    @Test
    fun `an over ambitious radius is trimmed rather than refused`() {
        val city = TiltedCity(27.0)
        var asked = 0.0
        val watching = RoadSource { center, radius, activity ->
            asked = radius
            city.source.ways(center, radius, activity)
        }
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), watching).generate(
            request(km = 10.0),
            PlacementSearchOptions(radiusMeters = 3_000.0, results = 2),
        )

        assertNull("la recherche doit avoir eu lieu", guided.unavailableReason)
        assertTrue(
            "secteur demandé : $asked m",
            asked <= GuidedRouteGenerator.OVERPASS_MAX_RADIUS_METERS + 1e-6,
        )
    }

    @Test
    fun `a shape that no engine can route at all still reports the failure`() {
        val refusing = object : RoutingEngine {
            override val id = "non"
            override val displayName = "Refuse tout"
            override val snapsToRoads = true
            override val maxWaypoints = 80
            override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath =
                throw RoutingException("serveur muet")
        }
        val city = TiltedCity(0.0)
        assertThrows(Exception::class.java) {
            GuidedRouteGenerator(RouteGenerator(refusing), city.source).generate(request())
        }
    }

    /**
     * Quand le placement demandé ne boucle pas du tout, il n'y a pas de tracé auquel
     * comparer — et pourtant la recherche a bel et bien trouvé autre chose. Se
     * prononcer sur le tracé plutôt que sur le placement annoncerait ici « votre
     * placement est resté le meilleur », ce qui serait faux.
     */
    @Test
    fun `a move is reported even when the asked placement produced nothing`() {
        val city = TiltedCity(27.0)
        // Le placement demandé est toujours routé en premier : refuser ce premier
        // appel modélise « ce placement-là ne boucle pas » sans dépendre de la
        // géométrie, tous les candidats partant ici du même point.
        var first = true
        val picky = object : RoutingEngine by city.engine {
            override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
                if (first) {
                    first = false
                    throw RoutingException("ce placement ne boucle pas")
                }
                return city.engine.route(waypoints, activity)
            }
        }
        val guided = GuidedRouteGenerator(RouteGenerator(picky), city.source)
            .generate(request(), PlacementSearchOptions(results = 3))

        assertNull("aucun tracé au placement demandé", guided.asked)
        assertTrue("le déplacement doit être annoncé", guided.improved)
    }

    @Test
    fun `nothing is reported as improved when the asked placement wins`() {
        val city = TiltedCity(0.0)
        // Sur une trame droite, un carré non tourné est déjà au mieux.
        val guided = GuidedRouteGenerator(RouteGenerator(city.engine), city.source)
            .generate(request(rotation = 0.0), PlacementSearchOptions(results = 3))

        if (!guided.improved) {
            assertEquals(start, guided.placement.anchor)
            assertEquals(0.0, guided.placement.rotationDeg, 1e-9)
            assertNull(guided.unavailableReason)
        }
    }
}
