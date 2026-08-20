package com.stravart.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.stravart.core.image.Raster
import java.io.IOException
import kotlin.math.max

/**
 * Lit une image du téléphone et la réduit à ce que la détection de contour attend.
 *
 * L'image est sous-échantillonnée dès le décodage : une photo de 12 mégapixels
 * n'apporte rien de plus qu'un aperçu de 640 pixels pour trouver une silhouette, et
 * la décoder en entier reviendrait à réserver cinquante mégaoctets pour rien.
 */
object BitmapRaster {

    /** Côté maximal conservé. Au-delà, le contour ne gagne plus en justesse. */
    private const val MAX_SIDE = 640

    @Throws(IOException::class)
    fun load(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream ?: throw IOException("image illisible"), null, bounds)
        }
        val largest = max(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) throw IOException("image illisible")

        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { largest / it <= MAX_SIDE }
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream ?: throw IOException("image illisible"), null, options)
        } ?: throw IOException("format d'image non reconnu")
    }

    /** Convertit en luminance, en conservant la transparence quand elle existe. */
    fun toRaster(bitmap: Bitmap): Raster {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(pixels.size)
        val alpha = if (bitmap.hasAlpha()) IntArray(pixels.size) else null

        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            // Pondération perceptuelle, en entiers : l'œil voit surtout le vert.
            luminance[index] = (77 * red + 150 * green + 29 * blue) shr 8
            alpha?.set(index, (pixel ushr 24) and 0xFF)
        }
        return Raster(width, height, luminance, alpha)
    }
}
