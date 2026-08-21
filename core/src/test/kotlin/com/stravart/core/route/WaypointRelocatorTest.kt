package com.stravart.core.route

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointRelocatorTest {

    private val start = LatLon(48.8566, 2.3522)

    private fun at(east: Double, north: Double) = Geo.offset(start, east, north)

    private val waypoints = (0..10).map { at(it * 200.0, 0.0) }

    @Test
    fun `a route that honours the waypoints leaves them alone`() {
        val route = waypoints.map { Geo.offset(it, 0.0, 30.0) }
        assertNull(WaypointRelocator.relocate(waypoints, route))
    }

    @Test
    fun `a waypoint dragged aside is pushed the other way`() {
        // L'itinéraire passe 300 m au sud : la voie trouvée est au sud, donc on
        // envoie le point de passage chercher au nord.
        val route = waypoints.map { Geo.offset(it, 0.0, -250.0) }
        val relocation = WaypointRelocator.relocate(waypoints, route)!!

        assertEquals(waypoints.size - 2, relocation.movedCount)
        assertEquals(0, relocation.droppedCount)
        val moved = Geo.toLocal(start, relocation.waypoints[5])
        assertEquals(250.0, moved.y, 15.0)
        assertEquals(1000.0, moved.x, 15.0)
    }

    @Test
    fun `the start and the finish never move`() {
        val route = waypoints.map { Geo.offset(it, 0.0, -250.0) }
        val relocation = WaypointRelocator.relocate(waypoints, route)!!
        assertEquals(0.0, Geo.distance(waypoints.first(), relocation.waypoints.first()), 0.5)
        assertEquals(0.0, Geo.distance(waypoints.last(), relocation.waypoints.last()), 0.5)
    }

    @Test
    fun `only the waypoints left behind are moved`() {
        // Seul le point du milieu est ignoré par l'itinéraire.
        val route = waypoints.mapIndexed { index, w ->
            if (index == 5) Geo.offset(w, 0.0, -400.0) else w
        }
        val relocation = WaypointRelocator.relocate(waypoints, route)!!
        assertEquals(1, relocation.movedCount)
        waypoints.forEachIndexed { index, original ->
            if (index != 5) {
                assertEquals(0.0, Geo.distance(original, relocation.waypoints[index]), 1.0)
            }
        }
    }

    @Test
    fun `the displacement stays within reason`() {
        // Écartement neutralisé, sans quoi les indices ne se correspondraient plus.
        val route = waypoints.map { Geo.offset(it, 0.0, -4000.0) }
        val relocation = WaypointRelocator.relocate(
            waypoints,
            route,
            dropThresholdMeters = Double.MAX_VALUE,
        )!!
        val shift = Geo.distance(waypoints[5], relocation.waypoints[5])
        assertTrue("déplacement de $shift m", shift <= 720.0)
    }

    @Test
    fun `the threshold governs what counts as ignored`() {
        val route = waypoints.map { Geo.offset(it, 0.0, -150.0) }
        assertNull(WaypointRelocator.relocate(waypoints, route, thresholdMeters = 200.0))
        assertEquals(9, WaypointRelocator.relocate(waypoints, route, thresholdMeters = 100.0)!!.movedCount)
    }

    @Test
    fun `waypoints far from any road are discarded rather than moved`() {
        // L'itinéraire s'écarte de 500 m sur toute la partie centrale : ces points
        // ne sont sur aucune voie. Les imposer dicterait l'endroit de la traversée.
        val route = waypoints.mapIndexed { index, w ->
            if (index in 3..7) Geo.offset(w, 0.0, -500.0) else w
        }
        val relocation = WaypointRelocator.relocate(waypoints, route)!!

        assertTrue("aucun point écarté", relocation.droppedCount >= 1)
        assertEquals(waypoints.size - relocation.droppedCount, relocation.waypoints.size)
        // Le plus enfoncé des points est retiré, pas simplement déplacé.
        assertTrue(relocation.waypoints.none { Geo.distance(it, waypoints[5]) < 1.0 })
    }

    @Test
    fun `two neighbours are never discarded in a row`() {
        // Toute une portion hors de portée : en retirer un sur deux suffit, sinon le
        // moteur n'aurait plus aucun repère sur la traversée.
        val route = waypoints.map { Geo.offset(it, 0.0, -500.0) }
        val relocation = WaypointRelocator.relocate(waypoints, route)!!

        assertTrue("écartés: ${relocation.droppedCount}", relocation.droppedCount <= 5)
        assertTrue("déplacés: ${relocation.movedCount}", relocation.movedCount >= 4)
        assertEquals(waypoints.size - relocation.droppedCount, relocation.waypoints.size)
    }

    @Test
    fun `the discard threshold can be pushed back`() {
        val route = waypoints.map { Geo.offset(it, 0.0, -500.0) }
        val relocation = WaypointRelocator.relocate(
            waypoints,
            route,
            dropThresholdMeters = 900.0,
        )!!
        assertEquals(0, relocation.droppedCount)
        assertEquals(waypoints.size - 2, relocation.movedCount)
    }

    @Test
    fun `degenerate inputs are refused quietly`() {
        assertNull(WaypointRelocator.relocate(waypoints.take(2), waypoints))
        assertNull(WaypointRelocator.relocate(waypoints, emptyList()))
    }
}
