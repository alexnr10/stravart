package com.stravart.core.shape

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Une forme prête à l'emploi proposée dans l'application. */
data class ShapePreset(
    val id: String,
    val label: String,
    val path: ShapePath,
)

/**
 * Catalogue des formes intégrées.
 *
 * Chaque forme est un trait unique et fermé (le parcours revient à son point de
 * départ), défini analytiquement puis normalisé par [ShapePath.of].
 */
object ShapeLibrary {

    private const val SMOOTH_SAMPLES = 240

    val presets: List<ShapePreset> by lazy {
        listOf(
            ShapePreset("heart", "Cœur", heart()),
            ShapePreset("star", "Étoile", star(branches = 5, innerRatio = 0.42)),
            ShapePreset("circle", "Cercle", polygon(SMOOTH_SAMPLES)),
            ShapePreset("triangle", "Triangle", polygon(3)),
            ShapePreset("square", "Carré", polygon(4)),
            ShapePreset("hexagon", "Hexagone", polygon(6)),
            ShapePreset("lightning", "Éclair", lightning()),
            ShapePreset("arrow", "Flèche", arrow()),
            ShapePreset("crescent", "Croissant", crescent()),
            ShapePreset("infinity", "Infini", infinity()),
            ShapePreset("drop", "Goutte", drop()),
            ShapePreset("fish", "Poisson", fish()),
        )
    }

    val default: ShapePreset get() = presets.first()

    fun byId(id: String): ShapePreset? = presets.firstOrNull { it.id == id }

    // --- Définitions ---------------------------------------------------------

    /** Courbe cardioïde « cœur » classique. */
    private fun heart(): ShapePath = parametric(SMOOTH_SAMPLES) { t ->
        Pt(
            x = 16.0 * sin(t).pow(3),
            y = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t),
        )
    }

    /** Polygone régulier à [sides] côtés, premier sommet en haut. */
    private fun polygon(sides: Int): ShapePath {
        require(sides >= 3) { "un polygone demande au moins 3 côtés" }
        val pts = (0 until sides).map { i ->
            val a = PI / 2 + 2 * PI * i / sides
            Pt(cos(a), sin(a))
        }
        return ShapePath.of(pts, closed = true)
    }

    /** Étoile à [branches] pointes. */
    private fun star(branches: Int, innerRatio: Double): ShapePath {
        val pts = ArrayList<Pt>(branches * 2)
        for (i in 0 until branches * 2) {
            val r = if (i % 2 == 0) 1.0 else innerRatio
            val a = PI / 2 + PI * i / branches
            pts += Pt(r * cos(a), r * sin(a))
        }
        return ShapePath.of(pts, closed = true)
    }

    /** Contour d'un éclair. */
    private fun lightning(): ShapePath = ShapePath.of(
        listOf(
            Pt(0.55, 1.00),
            Pt(0.15, 0.45),
            Pt(0.42, 0.45),
            Pt(0.22, 0.00),
            Pt(0.85, 0.58),
            Pt(0.58, 0.58),
            Pt(0.90, 1.00),
        ),
        closed = true,
    )

    /** Contour d'une flèche pointant vers le nord. */
    private fun arrow(): ShapePath = ShapePath.of(
        listOf(
            Pt(0.50, 1.00),
            Pt(0.00, 0.50),
            Pt(0.28, 0.50),
            Pt(0.28, 0.00),
            Pt(0.72, 0.00),
            Pt(0.72, 0.50),
            Pt(1.00, 0.50),
        ),
        closed = true,
    )

    /**
     * Croissant de lune : un arc externe (cercle unité) refermé par un arc interne
     * de plus grand rayon, dont le centre est décalé de sorte que les deux arcs se
     * rejoignent exactement.
     */
    private fun crescent(): ShapePath {
        val startDeg = 70.0
        val endDeg = 290.0
        val innerCenterX = -1.0

        val ax = cos(Math.toRadians(startDeg))
        val ay = sin(Math.toRadians(startDeg))
        val innerRadius = sqrt((ax - innerCenterX).pow(2) + ay.pow(2))
        val innerHalfAngle = kotlin.math.atan2(ay, ax - innerCenterX)

        val pts = ArrayList<Pt>()
        val outerSteps = SMOOTH_SAMPLES * 2 / 3
        for (i in 0..outerSteps) {
            val a = Math.toRadians(startDeg + (endDeg - startDeg) * i / outerSteps)
            pts += Pt(cos(a), sin(a))
        }
        val innerSteps = SMOOTH_SAMPLES / 3
        // Retour de B vers A par l'arc interne, en passant par l'angle 0.
        for (i in 1 until innerSteps) {
            val a = -innerHalfAngle + 2 * innerHalfAngle * i / innerSteps
            pts += Pt(innerCenterX + innerRadius * cos(a), innerRadius * sin(a))
        }
        return ShapePath.of(pts, closed = true)
    }

    /** Lemniscate de Bernoulli (symbole de l'infini). */
    private fun infinity(): ShapePath = parametric(SMOOTH_SAMPLES) { t ->
        val d = 1.0 + sin(t).pow(2)
        Pt(x = cos(t) / d, y = sin(t) * cos(t) / d)
    }

    /** Goutte d'eau, pointe vers le haut. */
    private fun drop(): ShapePath = parametric(SMOOTH_SAMPLES) { t ->
        val x = cos(t)
        val y = sin(t) * sin(t / 2).pow(2)
        // Rotation d'un quart de tour pour orienter la pointe vers le nord.
        Pt(x = -y, y = x)
    }

    /** « Fish curve », un poisson stylisé avec sa nageoire caudale. */
    private fun fish(): ShapePath = parametric(SMOOTH_SAMPLES) { t ->
        Pt(
            x = cos(t) - sin(t).pow(2) / sqrt(2.0),
            y = cos(t) * sin(t),
        )
    }

    private inline fun parametric(samples: Int, f: (Double) -> Pt): ShapePath {
        val pts = (0 until samples).map { i -> f(2 * PI * i / samples) }
        return ShapePath.of(pts, closed = true)
    }
}
