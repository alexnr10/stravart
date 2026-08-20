package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.routing.ActivityType
import com.stravart.core.routing.RoutedPath
import com.stravart.core.routing.RoutingEngine
import com.stravart.core.shape.ShapeLibrary
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vérifie la chaîne complète contre un réseau de rues simulé.
 *
 * Le moteur [CityGridEngine] impose un quadrillage de 100 m parcouru en angle droit :
 * c'est le pire cas raisonnable pour nous, puisqu'une diagonale y coûte 41 % de plus
 * qu'à vol d'oiseau. Si la distance finale et la ressemblance tiennent là, elles
 * tiendront sur une vraie ville.
 */
class CityGridSimulationTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun request(shapeId: String, km: Double, activity: ActivityType = ActivityType.RUN) =
        RouteRequest(
            shape = ShapeLibrary.byId(shapeId)!!.path,
            start = start,
            distanceMeters = km * 1000,
            activity = activity,
        )

    @Test
    fun `distance lands within tolerance on a street grid`() {
        for (shapeId in listOf("heart", "star", "circle", "triangle", "lightning", "infinity")) {
            for (km in listOf(5.0, 10.0, 21.1)) {
                val route = RouteGenerator(CityGridEngine()).generate(request(shapeId, km))
                val error = abs(route.distanceMeters - km * 1000) / (km * 1000)
                assertTrue(
                    "$shapeId ${km} km : ${route.distanceMeters.roundToInt()} m " +
                        "(${(error * 100).roundToInt()} % d'écart)",
                    error <= 0.05,
                )
            }
        }
    }

    @Test
    fun `the shape stays recognisable through the grid`() {
        for (shapeId in listOf("heart", "star", "circle", "square")) {
            val route = RouteGenerator(CityGridEngine()).generate(request(shapeId, 12.0))
            assertTrue(
                "$shapeId : ressemblance ${route.fidelity.score} %, " +
                    "écart moyen ${route.fidelity.meanDeviationMeters.roundToInt()} m",
                route.fidelity.score >= 55,
            )
        }
    }

    @Test
    fun `the loop comes back to the starting point`() {
        val route = RouteGenerator(CityGridEngine()).generate(request("heart", 8.0))
        // Le départ est calé sur une intersection : l'écart reste celui d'une maille.
        assertTrue(Geo.distance(start, route.points.first()) <= 150.0)
        assertTrue(Geo.distance(route.points.first(), route.points.last()) <= 150.0)
    }

    @Test
    fun `a bike route keeps its shape with fewer waypoints`() {
        val route = RouteGenerator(CityGridEngine()).generate(request("star", 30.0, ActivityType.BIKE))
        val error = abs(route.distanceMeters - 30_000.0) / 30_000.0
        assertTrue("écart ${(error * 100).roundToInt()} %", error <= 0.05)
        assertTrue("ressemblance ${route.fidelity.score} %", route.fidelity.score >= 55)
    }

    /**
     * Réseau en damier : chaque point de passage est ramené à l'intersection la plus
     * proche, et deux intersections se rejoignent en deux tronçons à angle droit.
     */
    private class CityGridEngine(private val cellMeters: Double = 100.0) : RoutingEngine {
        override val id = "grid"
        override val displayName = "Damier simulé"
        override val snapsToRoads = true
        override val maxWaypoints = 40

        override fun route(waypoints: List<LatLon>, activity: ActivityType): RoutedPath {
            val origin = waypoints.first()

            fun snap(p: LatLon): Pair<Double, Double> {
                val local = Geo.toLocal(origin, p)
                return (local.x / cellMeters).roundToInt() * cellMeters to
                    (local.y / cellMeters).roundToInt() * cellMeters
            }

            val points = ArrayList<LatLon>()
            var previous = snap(waypoints.first())
            points += Geo.offset(origin, previous.first, previous.second)

            for (i in 1 until waypoints.size) {
                val current = snap(waypoints[i])
                // On alterne « rue d'abord » et « avenue d'abord » pour ne pas biaiser
                // systématiquement le tracé dans la même direction.
                val corner = if (i % 2 == 0) {
                    current.first to previous.second
                } else {
                    previous.first to current.second
                }
                points += Geo.offset(origin, corner.first, corner.second)
                points += Geo.offset(origin, current.first, current.second)
                previous = current
            }

            return RoutedPath(points, Geo.pathLength(points))
        }
    }
}
