package com.stravart.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Thème de l'application.
 *
 * La couleur dynamique n'est pas proposée : elle ferait varier les contrastes d'un
 * appareil à l'autre, alors que l'écran se lit dehors et parfois en plein soleil.
 * Les couleurs des tracés, elles, suivent le jeu de tuiles et non ce thème — d'où leur
 * passage par un [LocalMapColors] séparé.
 */
@Composable
fun StravArtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMapColors provides if (darkTheme) DarkMapColors else LightMapColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) BraiseDark else BraiseLight,
            typography = StravArtTypography,
            content = content,
        )
    }
}
