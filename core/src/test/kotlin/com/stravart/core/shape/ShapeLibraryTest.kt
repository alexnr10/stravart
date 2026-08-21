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

    /**
     * Carré et losange sont le même polygone à un huitième de tour près : c'est le
     * seul écart entre eux, et il se lit dans le périmètre. Le carré est normalisé
     * sur son côté, le losange sur sa diagonale, d'où le rapport de racine de deux.
     */
    @Test
    fun `a regular polygon has the expected perimeter`() {
        val square = ShapeLibrary.byId("square")!!.path
        assertEquals(4.0, square.length, 1e-6)

        val diamond = ShapeLibrary.byId("diamond")!!.path
        assertEquals(4 / Math.sqrt(2.0), diamond.length, 1e-6)
    }

    /**
     * Une croix grecque a exactement le périmètre du carré qui l'englobe : ce que
     * les rentrants retirent aux bras, ils le rendent en longueur de rentrant. La
     * propriété ne dépend pas de la largeur des bras, elle vérifie donc la fermeture
     * et l'équerrage de la forme sans figer un réglage esthétique.
     */
    @Test
    fun `the cross has the perimeter of its bounding square`() {
        val cross = ShapeLibrary.byId("cross")!!.path
        assertEquals(4.0, cross.length, 1e-6)
        assertEquals(1.0, cross.bounds.width, 1e-9)
        assertEquals(1.0, cross.bounds.height, 1e-9)
    }

    /**
     * La vague est un ruban : l'aller et le retour doivent rester assez écartés pour
     * emprunter deux rues distinctes, faute de quoi le générateur y verrait un
     * retour sur ses pas et refuserait la zone.
     *
     * Le seuil est exprimé en mètres sur une boucle de dix kilomètres, seule échelle
     * où il veut dire quelque chose. Trois cents mètres laissent une marge large :
     * la vague en tient quatre cent soixante, là où le cœur descend à cinquante au
     * creux de son échancrure sans que cela pose problème.
     */
    @Test
    fun `the two sides of the wave stay apart`() {
        val wave = ShapeLibrary.byId("wave")!!.path
        val pts = wave.points
        val half = pts.size / 2

        val closestUnits = pts.take(half).minOf { a ->
            pts.drop(half).minOf { b -> Math.hypot(a.x - b.x, a.y - b.y) }
        }
        val closestMeters = closestUnits * 10_000.0 / wave.length
        assertTrue("aller et retour à %.0f m seulement".format(closestMeters), closestMeters > 300.0)
    }

    @Test
    fun `lookup by id`() {
        assertNotNull(ShapeLibrary.byId("heart"))
        assertNull(ShapeLibrary.byId("licorne"))
        assertEquals("heart", ShapeLibrary.default.id)
    }
}
