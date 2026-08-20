package com.stravart.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stravart.core.shape.ShapePath
import kotlin.math.min

/** Aperçu d'une forme, tel qu'il apparaîtra une fois posé sur la carte. */
@Composable
fun ShapeThumbnail(
    shape: ShapePath,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidthPx: Float = 4f,
) {
    Canvas(modifier) {
        val extent = min(size.width, size.height)
        // Une marge de 12 % évite que les pointes touchent le bord de la vignette.
        val scale = extent * 0.88f
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        val path = Path()
        shape.renderedPoints.forEachIndexed { index, point ->
            // Le plan de la forme a le nord vers le haut ; l'écran, l'inverse.
            val x = centerX + (point.x * scale).toFloat()
            val y = centerY - (point.y * scale).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (shape.closed) path.close()

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Variante prenant des points déjà projetés à l'écran (utilisée par l'écran de dessin). */
@Composable
fun StrokePreview(
    points: List<Offset>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidthPx: Float = 8f,
) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
