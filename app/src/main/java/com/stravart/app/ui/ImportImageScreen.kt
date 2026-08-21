package com.stravart.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stravart.app.R
import com.stravart.app.image.BitmapRaster
import com.stravart.core.image.ExtractedShape
import com.stravart.core.image.ImageShapeExtractor
import com.stravart.core.shape.ShapePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tire une forme de parcours d'une image du téléphone.
 *
 * La détection de contour est une affaire de jugement : selon l'éclairage et le fond,
 * le sujet se détache plus ou moins bien. L'écran montre donc le contour trouvé
 * par-dessus l'image, avec de quoi le corriger, plutôt que d'imposer un résultat que
 * l'utilisateur ne découvrirait qu'une fois posé sur la carte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportImageScreen(
    onCancel: () -> Unit,
    onValidate: (ShapePath) -> Unit,
) {
    val context = LocalContext.current

    var source by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sensitivity by remember { mutableStateOf(0f) }
    var inverted by remember { mutableStateOf(false) }
    var extracted by remember { mutableStateOf<ExtractedShape?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val decodeFailed = stringResource(R.string.image_unreadable)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) source = uri }

    LaunchedEffect(Unit) {
        if (source == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Décodage puis détection : les deux hors du fil principal, l'un comme l'autre
    // parcourant quelques centaines de milliers de pixels.
    LaunchedEffect(source, sensitivity, inverted) {
        val uri = source ?: return@LaunchedEffect
        working = true
        failure = null
        val outcome = withContext(Dispatchers.Default) {
            runCatching {
                val image = bitmap?.takeIf { source == uri } ?: BitmapRaster.load(context, uri)
                val raster = BitmapRaster.toRaster(image)
                image to ImageShapeExtractor.extract(
                    raster = raster,
                    sensitivity = sensitivity.toDouble(),
                    invert = inverted,
                )
            }
        }
        working = false
        outcome
            .onSuccess { (image, shape) ->
                bitmap = image
                extracted = shape
            }
            .onFailure { error ->
                extracted = null
                failure = error.message ?: decodeFailed
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.image_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image == null) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ContourPreview(image, extracted, Modifier.fillMaxSize())
                }
                if (working) CircularProgressIndicator()
            }

            failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            extracted?.let { shape ->
                Text(
                    text = stringResource(
                        R.string.image_coverage,
                        (shape.coverage * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (bitmap != null) {
                Text(stringResource(R.string.image_sensitivity), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = -1f..1f,
                    steps = 19,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.image_invert), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = inverted, onCheckedChange = { inverted = it })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.image_pick))
                }
                Button(
                    onClick = { extracted?.let { onValidate(it.path) } },
                    enabled = extracted != null && !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.image_use))
                }
            }
        }
    }
}

/**
 * Superpose le contour détecté à l'image.
 *
 * Les deux sont dessinés dans le même repère plutôt que l'un sur l'autre : c'est le
 * seul moyen d'être certain qu'ils s'alignent, quelle que soit la forme du cadre.
 */
@Composable
private fun ContourPreview(
    bitmap: Bitmap,
    extracted: ExtractedShape?,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // Lue ici et non dans le Canvas : le bloc de dessin n'est pas un contexte
    // composable, il ne peut pas consulter le thème lui-même.
    val contourColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale = min(size.width / image.width, size.height / image.height)
        val drawnWidth = image.width * scale
        val drawnHeight = image.height * scale
        val left = (size.width - drawnWidth) / 2
        val top = (size.height - drawnHeight) / 2

        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawnWidth.roundToInt(), drawnHeight.roundToInt()),
        )

        val contour = extracted?.contour ?: return@Canvas
        if (contour.size < 2) return@Canvas

        val path = Path()
        contour.forEachIndexed { index, point ->
            val x = left + (point.x * scale).toFloat()
            val y = top + (point.y * scale).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = contourColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
