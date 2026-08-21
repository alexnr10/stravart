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

    @Test
    fun `the network radius covers the whole shape and the search around it`() {
        val square = ShapeLibrary.byId("square")!!.path
        val options = PlacementSearchOptions(radiusMeters = 500.0, distanceTolerance = 0.10)
        val radius = GuidedRouteGenerator.networkRadius(
            RouteRequest(square, start, 4_000.0, ActivityType.RUN), options,
        )!!
        // Le carré normalisé mesure 4 de long : à 4 km, il tient dans 1 100 m.
        assertEquals(500.0 + 1_100.0 + 250.0, radius, 1.0)
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
