package com.stravart.core.placement

import com.stravart.core.geo.Geo
import com.stravart.core.geo.LatLon
import com.stravart.core.shape.ShapePath
import com.stravart.core.shape.ShapeProjector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Cherche la meilleure façon de poser une forme sur un réseau viaire.
 *
 * Le principe est de **dissocier l'évaluation du routage**. Router un candidat coûte
 * cinq à six requêtes réseau ; en évaluer cinquante de cette façon est hors de
 * question. Or ce qui fait un bon placement se lit dans la géométrie des rues : une
 * voie bien orientée sous chaque portion de la forme. Cela se mesure hors ligne, sur
 * le graphe, des milliers de fois par seconde.
 *
 * La note reste un **indicateur**, pas une fidélité. Elle ignore la connexité : une
 * impasse parfaitement orientée note aussi bien qu'une rue traversante. C'est pourquoi
 * la recherche rend plusieurs candidats distincts, à confronter ensuite au vrai
 * moteur de routage — l'ordre qu'elle propose ne se vérifie que là.
 *
 * La recherche procède en deux passes. La première balaie largement, avec peu de
 * points et des pas grossiers ; la seconde reprend finement le voisinage des
 * meilleurs. Un balayage fin d'emblée coûterait vingt fois plus pour le même résultat.
 */
object PlacementSearch {

    /**
     * @return les meilleurs placements, du plus prometteur au moins prometteur, et
     *   suffisamment distincts les uns des autres pour que les confronter au moteur
     *   ait un sens.
     */
    fun search(
        network: RoadNetwork,
        request: PlacementRequest,
        options: PlacementSearchOptions = PlacementSearchOptions(),
    ): List<ScoredPlacement> {
        val coarseProbe = request.shape.resampled(options.coarseSamples + 1)
        val fineProbe = request.shape.resampled(options.fineSamples + 1)

        val anchors = anchorGrid(request.anchor, options.radiusMeters, options.positionStepMeters)
        val scales = scaleLadder(options.distanceTolerance, options.scaleSteps)
        val coarseRotations = rotations(options.coarseRotationStepDeg)

        val coarse = ArrayList<ScoredPlacement>(anchors.size * scales.size * coarseRotations.size)
        for (anchor in anchors) {
            for (scale in scales) {
                for (rotation in coarseRotations) {
                    val placement = Placement(anchor, rotation, request.distanceMeters * scale)
                    coarse += ScoredPlacement(
                        placement,
                        score(network, request, coarseProbe, placement, options),
                    )
                }
            }
        }

        val shortlist = coarse
            .sortedBy { it.score.meanCostMeters }
            .take(options.refineCount)

        val refined = ArrayList<ScoredPlacement>()
        for (candidate in shortlist) {
            for (neighbour in neighbourhood(candidate.placement, options)) {
                refined += ScoredPlacement(
                    neighbour,
                    score(network, request, fineProbe, neighbour, options),
                )
            }
        }

        return refined
            .sortedBy { it.score.meanCostMeters }
            .distinctEnough(options)
            .take(options.results)
    }

    /** Note un placement : prix moyen de rattachement, et part de forme non desservie. */
    fun score(
        network: RoadNetwork,
        request: PlacementRequest,
        probe: ShapePath,
        placement: Placement,
        options: PlacementSearchOptions,
    ): PlacementScore {
        val projected = ShapeProjector.project(
            shape = probe,
            anchor = placement.anchor,
            distanceMeters = placement.distanceMeters,
            rotationDeg = placement.rotationDeg,
            mode = request.mode,
            mirrored = request.mirrored,
        )
        if (projected.size < 3) return PlacementScore(Double.MAX_VALUE, 1.0)

        val n = projected.size
        val xs = DoubleArray(n) { network.localX(projected[it]) }
        val ys = DoubleArray(n) { network.localY(projected[it]) }

        val cap = options.maxMatchMeters + options.bearingWeightMeters
        var total = 0.0
        var uncovered = 0
        var counted = 0

        for (i in 0 until n) {
            // L'orientation de la forme se lit sur la corde entre les points voisins :
            // celle d'un seul segment suivrait le bruit d'échantillonnage.
            val prev = if (i == 0) n - 1 else i - 1
            val next = if (i == n - 1) 0 else i + 1
            val dx = xs[next] - xs[prev]
            val dy = ys[next] - ys[prev]
            if (hypot(dx, dy) < 1e-6) continue

            val bearing = RoadNetwork.foldAngle(Math.toDegrees(atan2(dy, dx)))
            val cost = network.matchCost(
                xs[i], ys[i], bearing,
                options.maxMatchMeters, options.bearingWeightMeters,
            )
            total += cost
            if (cost >= cap - 1e-9) uncovered++
            counted++
        }

        if (counted == 0) return PlacementScore(Double.MAX_VALUE, 1.0)
        return PlacementScore(total / counted, uncovered.toDouble() / counted)
    }

    // --- Espace de recherche -----------------------------------------------------

    /**
     * Points de départ candidats : une trame carrée inscrite dans le rayon demandé.
     *
     * Le départ choisi par l'utilisateur en fait toujours partie, et vient en premier.
     * Il ne doit pas être un candidat comme les autres — c'est celui qu'il faut battre
     * nettement pour justifier qu'on propose de bouger.
     */
    internal fun anchorGrid(center: LatLon, radiusMeters: Double, stepMeters: Double): List<LatLon> {
        if (radiusMeters <= 0.0) return listOf(center)
        val rings = ceil(radiusMeters / stepMeters).toInt()
        val out = ArrayList<LatLon>()
        out += center
        for (iy in -rings..rings) {
            for (ix in -rings..rings) {
                if (ix == 0 && iy == 0) continue
                val east = ix * stepMeters
                val north = iy * stepMeters
                if (hypot(east, north) > radiusMeters) continue
                out += Geo.offset(center, east, north)
            }
        }
        return out
    }

    /** Facteurs de longueur essayés, la valeur demandée toujours comprise. */
    internal fun scaleLadder(tolerance: Double, steps: Int): List<Double> {
        if (tolerance <= 0.0 || steps <= 0) return listOf(1.0)
        val out = ArrayList<Double>(2 * steps + 1)
        for (i in -steps..steps) out += 1.0 + tolerance * i / steps
        return out
    }

    private fun rotations(stepDeg: Double): List<Double> {
        val count = (360.0 / stepDeg).toInt().coerceAtLeast(1)
        return List(count) { it * stepDeg }
    }

    /**
     * Le voisinage fin d'un candidat retenu : quelques orientations, quelques
     * longueurs, quelques décalages de départ autour de lui. Le candidat lui-même en
     * fait partie, sans quoi la passe fine pourrait le perdre.
     */
    private fun neighbourhood(
        placement: Placement,
        options: PlacementSearchOptions,
    ): List<Placement> {
        val halfSpan = options.coarseRotationStepDeg / 2.0
        val rotationOffsets = ArrayList<Double>()
        var r = -halfSpan
        while (r <= halfSpan + 1e-9) {
            rotationOffsets += r
            r += options.fineRotationStepDeg
        }
        if (rotationOffsets.none { abs(it) < 1e-9 }) rotationOffsets += 0.0

        val scaleOffsets = if (options.distanceTolerance <= 0.0 || options.scaleSteps <= 0) {
            listOf(1.0)
        } else {
            val half = options.distanceTolerance / (2.0 * options.scaleSteps)
            listOf(1.0 - half, 1.0, 1.0 + half)
        }

        val anchorOffsets = if (options.radiusMeters <= 0.0) {
            listOf(0.0 to 0.0)
        } else {
            val d = options.positionStepMeters / 2.0
            listOf(0.0 to 0.0, d to 0.0, (-d) to 0.0, 0.0 to d, 0.0 to (-d))
        }

        val out = ArrayList<Placement>(rotationOffsets.size * scaleOffsets.size * anchorOffsets.size)
        for ((east, north) in anchorOffsets) {
            val anchor = if (east == 0.0 && north == 0.0) {
                placement.anchor
            } else {
                Geo.offset(placement.anchor, east, north)
            }
            for (scale in scaleOffsets) {
                for (offset in rotationOffsets) {
                    out += Placement(
                        anchor = anchor,
                        rotationDeg = ((placement.rotationDeg + offset) % 360.0 + 360.0) % 360.0,
                        distanceMeters = placement.distanceMeters * scale,
                    )
                }
            }
        }
        return out
    }

    /**
     * Ne garde que des candidats franchement différents.
     *
     * Sans cela les cinq propositions seraient cinq variantes du même placement à
     * deux degrés près, et les router toutes n'apprendrait rien.
     */
    private fun List<ScoredPlacement>.distinctEnough(
        options: PlacementSearchOptions,
    ): List<ScoredPlacement> {
        val minGap = (options.positionStepMeters / 2.0).coerceAtLeast(100.0)
        val kept = ArrayList<ScoredPlacement>()
        for (candidate in this) {
            val tooClose = kept.any { other ->
                Geo.distance(other.placement.anchor, candidate.placement.anchor) < minGap &&
                    angularGap(other.placement.rotationDeg, candidate.placement.rotationDeg) < 20.0
            }
            if (!tooClose) kept += candidate
        }
        return kept
    }

    private fun angularGap(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
