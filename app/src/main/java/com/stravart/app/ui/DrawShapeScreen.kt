package com.stravart.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.draw_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.draw_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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
                StrokePreview(
                    points = points,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { points.clear() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.draw_clear))
                }
                Button(
                    onClick = {
                        val shape = points.toShapePath()
                        if (shape == null) onError(tooShortMessage) else onValidate(shape)
                    },
                    enabled = points.size >= MIN_STROKE_POINTS,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.draw_validate))
                }
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
