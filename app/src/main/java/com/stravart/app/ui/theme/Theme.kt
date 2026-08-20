package com.stravart.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Vert d'eau : le tracé de l'itinéraire, sur la carte comme dans l'interface. */
val RouteGreen = Color(0xFF0F8C7A)

/** Orange : la forme visée, en surimpression discrète. */
val ShapeOrange = Color(0xFFE07A3F)

private val LightColors = lightColorScheme(
    primary = RouteGreen,
    onPrimary = Color.White,
    secondary = ShapeOrange,
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FE3C4),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFF4A259),
    onSecondary = Color(0xFF4A2600),
)

@Composable
fun StravArtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // À partir d'Android 12, on s'accorde au fond d'écran de l'utilisateur.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
