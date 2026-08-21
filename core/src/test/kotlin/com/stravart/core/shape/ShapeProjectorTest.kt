package com.stravart.core.shape

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShapeProjectorTest {

    private val start = LatLon(45.7640, 4.8357) // Lyon

    @Test
    fun `the projected shape measures exactly the requested distance`() {
        for (preset in ShapeLibrary.presets) {
            val projected = ShapeProjector.project(preset.path, start, 10_000.0)
            val measured = Geo.pathLength(projected)
            assertTrue(
                "${preset.id}: ${measured.toInt()} m au lieu de 10 000 m",
                abs(measured - 10_000.0) / 10_000.0 < 0.005,
            )
        }
    }

    @Test
    fun `anchor START puts the first point on the chosen location`() {
        val projected = ShapeProjector.project(ShapeLibrary.default.path, start, 5_000.0)
        assertEquals(0.0, Geo.distance(start, projected.first()), 0.5)
        // Forme fermée : on revient au point de départ.
        assertEquals(0.0, Geo.distance(start, projected.last()), 0.5)
    }

    @Test
    fun `anchor CENTER centers the shape on the chosen location`() {
        val projected = ShapeProjector.project(
            ShapeLibrary.byId("square")!!.path,
            start,
            8_000.0,
            mode = AnchorMode.CENTER,
        )
        assertEquals(0.0, Geo.distance(start, Geo.boundsCenter(projected)), 1.0)
    }

    @Test
    fun `rotation turns the shape clockwise without changing its length`() {
        val shape = ShapeLibrary.byId("arrow")!!.path
        val north = ShapeProjector.project(shape, start, 6_000.0)
        val turned = ShapeProjector.project(shape, start, 6_000.0, rotationDeg = 90.0)

        assertEquals(Geo.pathLength(north), Geo.pathLength(turned), 1.0)

        // La pointe de la flèche part vers le nord ; après 90° horaires, vers l'est.
        val tipNorth = Geo.toLocal(start, north[north.size / 2])
        val tipEast = Geo.toLocal(start, turned[turned.size / 2])
        assertEquals(tipNorth.y, tipEast.x, 1.0)
        assertEquals(-tipNorth.x, tipEast.y, 1.0)
    }

    @Test
    fun `mirroring flips the shape east-west`() {
        val shape = ShapeLibrary.byId("fish")!!.path
        val normal = ShapeProjector.project(shape, start, 4_000.0)
        val mirrored = ShapeProjector.project(shape, start, 4_000.0, mirrored = true)
        assertEquals(Geo.pathLength(normal), Geo.pathLength(mirrored), 1.0)

        val index = normal.size / 3
        assertEquals(
            -Geo.toLocal(start, normal[index]).x,
            Geo.toLocal(start, mirrored[index]).x,
            1.0,
        )
    }

    @Test
    fun `distance scaling is linear`() {
        val shape = ShapeLibrary.default.path
        val small = Geo.pathLength(ShapeProjector.project(shape, start, 2_000.0))
        val big = Geo.pathLength(ShapeProjector.project(shape, start, 20_000.0))
        assertEquals(10.0, big / small, 0.01)
    }
}
