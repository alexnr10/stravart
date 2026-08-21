package com.stravart.core.placement

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PlacementSearchTest {

    private val origin = LatLon(48.8566, 2.3522)

    /**
     * Une trame de rues orientée à [tiltDeg], de maille [spacing], sur [half] mètres
     * de part et d'autre du centre. C'est le réseau le plus simple qui pose la bonne
     * question : à quelle orientation la forme s'aligne-t-elle sur les rues ?
     */
    private fun grid(tiltDeg: Double, spacing: Double = 120.0, half: Double = 4000.0): RoadNetwork {
        val t = Math.toRadians(tiltDeg)
        val cosT = cos(t)
        val sinT = sin(t)
        fun rotated(u: Double, v: Double) =
            Geo.offset(origin, u * cosT - v * sinT, u * sinT + v * cosT)

        val ways = ArrayList<RoadWay>()
        var d = -half
        while (d <= half) {
            ways += RoadWay(listOf(rotated(-half, d), rotated(half, d)))
            ways += RoadWay(listOf(rotated(d, -half), rotated(d, half)))
            d += spacing
        }
        return RoadNetwork.of(ways)
    }

    /** Écart angulaire modulo 90° : une trame carrée se répète tous les quarts de tour. */
    private fun gapModQuarter(a: Double, b: Double): Double {
        val d = ((a - b) % 90.0 + 90.0) % 90.0
        return min(d, 90.0 - d)
    }

    /**
     * `ShapeProjector` tourne la forme dans le sens horaire, tandis qu'un cap se
     * mesure dans le sens trigonométrique : pour épouser une trame inclinée de t, la
     * forme doit être tournée de −t. La recherche rend une valeur qui part telle
     * quelle dans le projecteur, c'est donc bien cette convention-là qu'on vérifie.
     */
    @Test
    fun `a square finds the tilt of the streets it must follow`() {
        val square = ShapeLibrary.byId("square")!!.path
        for (tilt in listOf(0.0, 17.0, 31.0, 58.0)) {
            val best = PlacementSearch.search(
                network = grid(tilt),
                request = PlacementRequest(square, origin, distanceMeters = 4000.0),
                options = PlacementSearchOptions(results = 1),
            ).single()
            assertTrue(
                "trame à $tilt° : orientation retenue ${best.placement.rotationDeg}°",
                gapModQuarter(best.placement.rotationDeg, -tilt) <= 6.0,
            )
        }
    }

    /**
     * Le losange est le carré tourné d'un huitième de tour. Sur une trame droite, il
     * doit donc être redressé de 45° — c'est la preuve que la recherche suit la forme
     * et non un réglage par défaut.
     */
    @Test
    fun `a diamond is turned an eighth of a turn to meet a straight grid`() {
        val diamond = ShapeLibrary.byId("diamond")!!.path
        val best = PlacementSearch.search(
            network = grid(0.0),
            request = PlacementRequest(diamond, origin, distanceMeters = 4000.0),
            options = PlacementSearchOptions(results = 1),
        ).single()
        assertTrue(
            "orientation retenue ${best.placement.rotationDeg}°",
            gapModQuarter(best.placement.rotationDeg, 45.0) <= 6.0,
        )
    }

    @Test
    fun `an aligned square scores far better than a misaligned one`() {
        val square = ShapeLibrary.byId("square")!!.path
        val net = grid(0.0)
        val request = PlacementRequest(square, origin, distanceMeters = 4000.0)
        val options = PlacementSearchOptions()
        val probe = square.resampled(options.fineSamples + 1)

        val aligned = PlacementSearch.score(
            net, request, probe, Placement(origin, 0.0, 4000.0), options,
        )
        val askew = PlacementSearch.score(
            net, request, probe, Placement(origin, 45.0, 4000.0), options,
        )
        assertTrue(
            "aligné ${aligned.meanCostMeters} / de biais ${askew.meanCostMeters}",
            aligned.meanCostMeters < askew.meanCostMeters / 2,
        )
    }

    /**
     * Là où aucune voie ne passe, la note doit le dire plutôt que de compter un
     * éloignement énorme qui écraserait tout le reste.
     */
    @Test
    fun `a shape laid over a void is reported as uncovered`() {
        val net = grid(0.0, half = 300.0)
        val square = ShapeLibrary.byId("square")!!.path
        val options = PlacementSearchOptions()
        val request = PlacementRequest(square, origin, distanceMeters = 20_000.0)
        val score = PlacementSearch.score(
            net, request, square.resampled(options.fineSamples + 1),
            Placement(origin, 0.0, 20_000.0), options,
        )
        assertTrue("part non desservie ${score.uncoveredRatio}", score.uncoveredRatio > 0.7)
    }

    /**
     * Un réseau réduit à un seul anneau de rues : la forme ne peut bien tomber qu'à
     * la longueur de cet anneau. C'est ce qui vérifie que jouer sur la taille sert à
     * quelque chose, et non qu'on ramène toujours la valeur demandée.
     */
    @Test
    fun `stretching the shape lets it catch a ring that the asked length missed`() {
        val ring = RoadWay(
            listOf(
                Geo.offset(origin, 0.0, 0.0),
                Geo.offset(origin, 900.0, 0.0),
                Geo.offset(origin, 900.0, 900.0),
                Geo.offset(origin, 0.0, 900.0),
                Geo.offset(origin, 0.0, 0.0),
            ),
        )
        val net = RoadNetwork.of(listOf(ring))
        val square = ShapeLibrary.byId("square")!!.path

        // Le tour de l'anneau fait 3 600 m ; on en demande 3 300, soit 8 % de moins.
        // La forme est centrée sur l'anneau : ancrée par son départ, elle pendrait à
        // côté et la longueur ne pourrait rien y faire.
        val best = PlacementSearch.search(
            network = net,
            request = PlacementRequest(
                shape = square,
                anchor = Geo.offset(origin, 450.0, 450.0),
                distanceMeters = 3_300.0,
                mode = AnchorMode.CENTER,
            ),
            options = PlacementSearchOptions(
                distanceTolerance = 0.12,
                scaleSteps = 3,
                results = 1,
            ),
        ).single()

        assertTrue(
            "longueur retenue ${best.placement.distanceMeters} m",
            best.placement.distanceMeters > 3_400.0,
        )
        assertTrue("prix moyen ${best.score.meanCostMeters} m", best.score.meanCostMeters < 25.0)
    }

    @Test
    fun `a zero radius leaves the chosen start untouched`() {
        val square = ShapeLibrary.byId("square")!!.path
        val results = PlacementSearch.search(
            network = grid(20.0),
            request = PlacementRequest(square, origin, 4000.0),
            options = PlacementSearchOptions(radiusMeters = 0.0, results = 3),
        )
        assertTrue(results.isNotEmpty())
        results.forEach {
            assertEquals(0.0, Geo.distance(it.placement.anchor, origin), 1e-6)
        }
    }

    @Test
    fun `a radius moves the start and always keeps the chosen one in the running`() {
        val anchors = PlacementSearch.anchorGrid(origin, radiusMeters = 900.0, stepMeters = 300.0)
        assertEquals("le départ choisi doit venir en tête", origin, anchors.first())
        assertTrue("candidats : ${anchors.size}", anchors.size > 20)
        anchors.forEach {
            // La projection locale n'est pas exacte au micron : un mètre de marge.
            val d = Geo.distance(origin, it)
            assertTrue("candidat à $d m du centre", d <= 901.0)
        }
    }

    @Test
    fun `the length ladder always contains the length that was asked for`() {
        val ladder = PlacementSearch.scaleLadder(0.10, 2)
        assertTrue(ladder.any { abs(it - 1.0) < 1e-9 })
        assertEquals(0.90, ladder.min(), 1e-9)
        assertEquals(1.10, ladder.max(), 1e-9)
        assertEquals(listOf(1.0), PlacementSearch.scaleLadder(0.0, 2))
    }

    @Test
    fun `the results are distinct enough to be worth routing separately`() {
        val results = PlacementSearch.search(
            network = grid(0.0),
            request = PlacementRequest(ShapeLibrary.byId("heart")!!.path, origin, 5000.0),
            options = PlacementSearchOptions(radiusMeters = 900.0, positionStepMeters = 300.0),
        )
        assertTrue(results.size >= 2)
        for (i in results.indices) {
            for (j in i + 1 until results.size) {
                val far = Geo.distance(results[i].placement.anchor, results[j].placement.anchor) >= 150.0
                val turned = abs(results[i].placement.rotationDeg - results[j].placement.rotationDeg) >= 20.0
                assertTrue("candidats $i et $j trop semblables", far || turned)
            }
        }
    }

    @Test
    fun `results come back best first`() {
        val results = PlacementSearch.search(
            network = grid(35.0),
            request = PlacementRequest(ShapeLibrary.byId("star")!!.path, origin, 6000.0),
            options = PlacementSearchOptions(radiusMeters = 600.0),
        )
        val scores = results.map { it.score.meanCostMeters }
        assertEquals(scores.sorted(), scores)
    }
}
