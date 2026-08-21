package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.shape.ShapeLibrary
import com.stravart.core.shape.ShapeProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WaypointSamplerTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun corner(a: Double, b: Double) = Geo.offset(start, a, b)

    @Test
    fun `the corners of a triangle survive sampling`() {
        val triangle = ShapeProjector.project(ShapeLibrary.byId("triangle")!!.path, start, 6_000.0)
        val sampled = WaypointSampler.sample(triangle, 20)

        // Chaque sommet du triangle doit se retrouver quasiment à l'identique.
        for (vertex in triangle) {
            val nearest = sampled.minOf { Geo.distance(it, vertex) }
            assertTrue("sommet perdu à ${nearest.toInt()} m", nearest < 1.0)
        }
    }

    @Test
    fun `sampling preserves the length of an angular shape`() {
        for (shapeId in listOf("triangle", "square", "star", "lightning", "arrow")) {
            val path = ShapeProjector.project(ShapeLibrary.byId(shapeId)!!.path, start, 8_000.0)
            val sampled = WaypointSampler.sample(path, 24)
            val loss = abs(Geo.pathLength(path) - Geo.pathLength(sampled)) / Geo.pathLength(path)
            assertTrue("$shapeId: ${(loss * 100).toInt()} % de longueur perdue", loss < 0.01)
        }
    }

    @Test
    fun `the budget is never exceeded`() {
        val star = ShapeProjector.project(ShapeLibrary.byId("star")!!.path, start, 12_000.0)
        for (budget in listOf(8, 12, 25, 40)) {
            val sampled = WaypointSampler.sample(star, budget)
            assertTrue("budget $budget dépassé (${sampled.size})", sampled.size <= budget)
        }
    }

    @Test
    fun `when the budget is tight the sharpest corners win`() {
        val star = ShapeProjector.project(ShapeLibrary.byId("star")!!.path, start, 12_000.0)
        val sampled = WaypointSampler.sample(star, 8)
        // L'étoile a 10 sommets ; avec 8 points on garde les extrémités et les 6 plus francs.
        assertTrue(sampled.size <= 8)
        assertEquals(0.0, Geo.distance(star.first(), sampled.first()), 1.0)
        assertEquals(0.0, Geo.distance(star.last(), sampled.last()), 1.0)
    }

    @Test
    fun `a smooth curve is sampled evenly`() {
        val circle = ShapeProjector.project(ShapeLibrary.byId("circle")!!.path, start, 10_000.0)
        val sampled = WaypointSampler.sample(circle, 26)
        val steps = (1 until sampled.size).map { Geo.distance(sampled[it - 1], sampled[it]) }
        val mean = steps.average()
        steps.forEach { assertEquals(mean, it, mean * 0.05) }
    }

    @Test
    fun `long straight legs receive intermediate points`() {
        // Deux points distants de 4 km : le routeur a besoin de jalons entre les deux.
        val line = listOf(start, corner(4000.0, 0.0))
        val sampled = WaypointSampler.sample(line, 9)
        assertEquals(9, sampled.size)
        assertEquals(0.0, Geo.distance(start, sampled.first()), 0.5)
        assertEquals(500.0, Geo.distance(sampled[0], sampled[1]), 5.0)
    }

    @Test
    fun `points closer than the minimum spacing are dropped`() {
        val tiny = listOf(start, corner(20.0, 0.0), corner(40.0, 0.0))
        val sampled = WaypointSampler.sample(tiny, 20)
        val steps = (1 until sampled.size).map { Geo.distance(sampled[it - 1], sampled[it]) }
        steps.forEach { assertTrue("points trop rapprochés: $it m", it >= 14.0) }
    }

    @Test
    fun `budgetFor stays within bounds`() {
        assertEquals(8, WaypointSampler.budgetFor(600.0, 300.0, min = 8, max = 40))
        assertEquals(21, WaypointSampler.budgetFor(6_000.0, 300.0, min = 8, max = 40))
        assertEquals(40, WaypointSampler.budgetFor(60_000.0, 300.0, min = 8, max = 40))
    }
}
