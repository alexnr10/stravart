package com.stravart.core.placement

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNetworkTest {

    private val origin = LatLon(48.8566, 2.3522)

    private fun way(vararg xy: Pair<Double, Double>) =
        RoadWay(xy.map { (e, n) -> Geo.offset(origin, e, n) })

    @Test
    fun `folding maps every angle onto a half turn`() {
        assertEquals(30.0, RoadNetwork.foldAngle(30.0), 1e-9)
        assertEquals(30.0, RoadNetwork.foldAngle(210.0), 1e-9)
        assertEquals(30.0, RoadNetwork.foldAngle(-150.0), 1e-9)
        assertEquals(0.0, RoadNetwork.foldAngle(180.0), 1e-9)
    }

    @Test
    fun `misalignment runs from parallel to perpendicular`() {
        assertEquals(0.0, RoadNetwork.misalignment(10.0, 10.0), 1e-9)
        assertEquals(1.0, RoadNetwork.misalignment(0.0, 90.0), 1e-9)
        // Le repliement doit faire de 179° et 1° des orientations quasi identiques.
        assertEquals(0.0, RoadNetwork.misalignment(179.0, 1.0), 0.03)
    }

    @Test
    fun `a point on a road costs nothing when the bearings agree`() {
        val net = RoadNetwork.of(listOf(way(-500.0 to 0.0, 500.0 to 0.0)))
        val cost = net.matchCost(0.0, 0.0, bearingDeg = 0.0, maxRadius = 120.0, bearingWeight = 60.0)
        assertEquals(0.0, cost, 1e-6)
    }

    /** Une rue perpendiculaire ne permet pas de suivre la forme, si proche soit-elle. */
    @Test
    fun `a perpendicular road costs the full bearing penalty`() {
        val net = RoadNetwork.of(listOf(way(-500.0 to 0.0, 500.0 to 0.0)))
        val cost = net.matchCost(0.0, 0.0, bearingDeg = 90.0, maxRadius = 120.0, bearingWeight = 60.0)
        assertEquals(60.0, cost, 1e-6)
    }

    @Test
    fun `distance adds to the bearing penalty`() {
        val net = RoadNetwork.of(listOf(way(-500.0 to 0.0, 500.0 to 0.0)))
        val cost = net.matchCost(0.0, 40.0, bearingDeg = 0.0, maxRadius = 120.0, bearingWeight = 60.0)
        assertEquals(40.0, cost, 0.5)
    }

    @Test
    fun `an out of reach point is capped rather than counted far`() {
        val net = RoadNetwork.of(listOf(way(-500.0 to 0.0, 500.0 to 0.0)))
        val cost = net.matchCost(0.0, 5000.0, bearingDeg = 0.0, maxRadius = 120.0, bearingWeight = 60.0)
        assertEquals(180.0, cost, 1e-6)
    }

    @Test
    fun `an empty network answers the cap without failing`() {
        val net = RoadNetwork.of(emptyList())
        assertEquals(0, net.segmentCount)
        assertEquals(180.0, net.matchCost(0.0, 0.0, 0.0, 120.0, 60.0), 1e-9)
    }

    /**
     * L'index découpe le plan en cellules ; un segment qui n'affleure qu'un coin doit
     * rester trouvable. On compare donc, sur une grille dense, la réponse indexée à
     * celle d'un balayage exhaustif.
     */
    @Test
    fun `the index finds exactly what an exhaustive sweep would`() {
        val ways = ArrayList<RoadWay>()
        for (i in 0..10) {
            val d = i * 100.0 - 500.0
            ways += way(-500.0 to d, 500.0 to d)
            ways += way(d to -500.0, d to 500.0)
        }
        // Une diagonale, pour que des segments traversent les cellules de biais.
        ways += way(-500.0 to -500.0, 500.0 to 500.0)

        val indexed = RoadNetwork.of(ways, cellMeters = 120.0)
        val flat = RoadNetwork.of(ways, cellMeters = 100_000.0) // une seule cellule

        val rnd = java.util.Random(7)
        repeat(400) {
            val x = rnd.nextDouble() * 1000 - 500
            val y = rnd.nextDouble() * 1000 - 500
            val b = rnd.nextDouble() * 180
            assertEquals(
                flat.matchCost(x, y, b, 120.0, 60.0),
                indexed.matchCost(x, y, b, 120.0, 60.0),
                1e-6,
            )
        }
    }

    @Test
    fun `zero length segments are dropped rather than given a bearing`() {
        val net = RoadNetwork.of(listOf(way(0.0 to 0.0, 0.0 to 0.0, 100.0 to 0.0)))
        assertTrue(net.segmentCount == 1)
    }
}
