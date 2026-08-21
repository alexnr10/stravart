package com.stravart.core.shape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCodecTest {

    @Test
    fun `a preset survives a round trip`() {
        for (preset in ShapeLibrary.presets) {
            val restored = ShapeCodec.decode(ShapeCodec.encode(preset.path))!!
            assertEquals(preset.id, preset.path.points.size, restored.points.size)
            assertEquals(preset.id, preset.path.length, restored.length, 1e-4)
            assertEquals(preset.id, preset.path.closed, restored.closed)
        }
    }

    @Test
    fun `an open stroke stays open`() {
        val open = ShapePath.of(listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0)), closed = false)
        val restored = ShapeCodec.decode(ShapeCodec.encode(open))!!
        assertTrue(!restored.closed)
        assertEquals(3, restored.points.size)
    }

    @Test
    fun `coordinates are preserved to five decimals`() {
        val shape = ShapeLibrary.byId("heart")!!.path
        val restored = ShapeCodec.decode(ShapeCodec.encode(shape))!!
        shape.points.forEachIndexed { index, point ->
            assertEquals(point.x, restored.points[index].x, 1e-4)
            assertEquals(point.y, restored.points[index].y, 1e-4)
        }
    }

    @Test
    fun `garbage decodes to null instead of crashing`() {
        assertNull(ShapeCodec.decode(null))
        assertNull(ShapeCodec.decode(""))
        assertNull(ShapeCodec.decode("pas une forme"))
        assertNull(ShapeCodec.decode("c:"))
        assertNull(ShapeCodec.decode("c:0.1,0.1"))
        assertNull(ShapeCodec.decode("c:0.5,abc;0.2,0.3"))
    }
}
