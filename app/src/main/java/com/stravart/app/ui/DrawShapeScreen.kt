package com.stravart.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stravart.app.R
import com.stravart.app.ui.components.StrokePreview
import com.stravart.core.shape.Pt
import com.stravart.core.shape.ShapePath

/** Nombre de points conservés après lissage : au-delà, le tracé n'y gagne rien. */
private const val SMOOTHED_POINTS = 200

/** En deçà, le geste ressemble davantage à une pression accidentelle qu'à un dessin. */
private const val MIN_STROKE_POINTS = 12

/**
 * Dessin d'une forme au doigt.
 *
 * Le trait est refermé automatiquement : un parcours revient à son point de départ,
 * autant l'assumer dès le dessin plutôt que d'imposer à l'utilisateur de retrouver
 * exactement son point d'origine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawShapeScreen(
    onCancel: () -> Unit,
    onValidate: (ShapePath) -> Unit,
    onError: (String) -> Unit,
) {
    val points = remember { mutableStateListOf<Offset>() }
    val tooShortMessage = stringResource(R.string.draw_too_short)
    val density = LocalDensity.current
    val strokePx = with(density) { 6.dp.toPx() }
    val startDotPx = with(density) { 18.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.draw_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InstructionCard("1", stringResource(R.string.draw_instructions))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { position ->
                                points.clear()
                                points.add(position)
                            },
                            onDrag = { change, _ -> points.add(change.position) },
                        )
                    },
            ) {
                GuideDots(Modifier.fillMaxSize())
                StrokePreview(
                    points = points,
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidthPx = strokePx,
                    fill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    startDotPx = startDotPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Text(
                text = if (points.size >= MIN_STROKE_POINTS) {
                    stringResource(R.string.draw_closed, points.size)
                } else {
                    stringResource(R.string.draw_awaiting)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { points.clear() },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .width(112.dp)
                        .height(56.dp),
                ) {
                    Text(stringResource(R.string.draw_clear))
                }
                Button(
                    onClick = {
                        val shape = points.toShapePath()
                        if (shape == null) onError(tooShortMessage) else onValidate(shape)
                    },
                    enabled = points.size >= MIN_STROKE_POINTS,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Text(
                        stringResource(R.string.draw_validate),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Consigne numérotée, commune aux deux écrans de création de forme. */
@Composable
internal fun InstructionCard(step: String, text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Repères de composition, quatre par quatre.
 *
 * Une surface vide ne dit pas où placer sa forme ni quelle taille lui donner ; ces
 * points donnent l'échelle sans imposer de grille au tracé, qui reste libre.
 */
@Composable
private fun GuideDots(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val radius = 1.dp.toPx()
        for (row in 1..4) {
            for (col in 1..4) {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(size.width * col / 5f, size.height * row / 5f),
                )
            }
        }
    }
}

/** Convertit le geste en forme exploitable, ou `null` s'il est inutilisable. */
private fun SnapshotStateList<Offset>.toShapePath(): ShapePath? {
    if (size < MIN_STROKE_POINTS) return null
    val raw = map { Pt(it.x.toDouble(), it.y.toDouble()) }
    return runCatching {
        ShapePath.fromScreen(raw, closed = true).resampled(SMOOTHED_POINTS)
    }.getOrNull()
}
