package com.stravart.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stravart.core.gpx.GpxWriter
import com.stravart.core.route.GeneratedRoute
import java.io.File

/** Écrit le parcours sur le téléphone et le remet aux autres applications. */
object GpxExporter {

    /** Type MIME reconnu par Garmin Connect, Strava et les gestionnaires de fichiers. */
    const val MIME_TYPE = "application/gpx+xml"

    fun render(route: GeneratedRoute): String = GpxWriter.write(route)

    fun fileName(route: GeneratedRoute): String = GpxWriter.fileName(route.name)

    /**
     * Écrit le GPX dans le cache privé et renvoie une URI partageable.
     *
     * Passer par [FileProvider] plutôt que par un chemin de fichier est ce qui permet
     * à l'application destinataire de lire le fichier sans autorisation de stockage.
     */
    fun shareableUri(context: Context, route: GeneratedRoute): Uri {
        val directory = File(context.cacheDir, "gpx").apply { mkdirs() }
        // On ne garde qu'un export à la fois : le cache n'a pas vocation à s'accumuler.
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, fileName(route))
        file.writeText(render(route))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(context: Context, route: GeneratedRoute): Intent {
        val uri = shareableUri(context, route)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, route.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Le ClipData est ce qui fait réellement suivre l'autorisation de lecture
            // jusqu'à l'application choisie dans le sélecteur.
            clipData = ClipData.newRawUri(route.name, uri)
        }
        return Intent.createChooser(send, route.name)
    }

    /** Écrit le parcours vers l'emplacement choisi par l'utilisateur (SAF). */
    fun writeTo(context: Context, target: Uri, route: GeneratedRoute) {
        context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
            stream.write(render(route).toByteArray())
        } ?: error("emplacement inaccessible")
    }
}
