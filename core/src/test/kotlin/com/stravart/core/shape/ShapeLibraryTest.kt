package com.stravart.core.shape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeLibraryTest {

    @Test
    fun `every preset is a usable closed stroke`() {
        assertTrue(ShapeLibrary.presets.isNotEmpty())
        for (preset in ShapeLibrary.presets) {
            val path = preset.path
            assertTrue("${preset.id}: trop peu de points", path.points.size >= ShapePath.MIN_POINTS)
            assertTrue("${preset.id}: forme fermée attendue", path.closed)
            assertTrue("${preset.id}: longueur nulle", path.length > 0.5)
            // Une forme normalisée tient dans le carré [-0,5 ; 0,5].
            assertTrue("${preset.id}: hors du carré unité", path.bounds.width <= 1.0 + 1e-9)
            assertTrue("${preset.id}: hors du carré unité", path.bounds.height <= 1.0 + 1e-9)
            path.points.forEach {
                assertTrue("${preset.id}: point hors bornes", it.x in -0.5001..0.5001)
                assertTrue("${preset.id}: point hors bornes", it.y in -0.5001..0.5001)
            }
        }
    }

    @Test
    fun `preset ids are unique`() {
        val ids = ShapeLibrary.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a regular polygon has the expected perimeter`() {
        val square = ShapeLibrary.byId("square")!!.path
        // Carré normalisé : côté 1/sqrt(2) une fois la diagonale ramenée à 1.
        assertEquals(4 / Math.sqrt(2.0), square.length, 1e-6)
    }

    @Test
    fun `lookup by id`() {
        assertNotNull(ShapeLibrary.byId("heart"))
        assertNull(ShapeLibrary.byId("licorne"))
        assertEquals("heart", ShapeLibrary.default.id)
    }
}
