package com.stravart.app

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Prépare osmdroid au démarrage.
 *
 * Deux points comptent : un User-Agent identifiable — la politique d'usage des
 * tuiles OpenStreetMap l'exige, et le trafic anonyme finit bloqué — et un cache
 * rangé dans le stockage privé de l'application, pour ne réclamer aucune
 * autorisation de stockage.
 */
class StravArtApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid(this)
    }

    private fun configureOsmdroid(context: Context) {
        val preferences = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().apply {
            load(context, preferences)
            userAgentValue = "${context.packageName}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
        }
    }
}
