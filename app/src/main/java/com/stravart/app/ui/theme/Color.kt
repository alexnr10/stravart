package com.stravart.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette « Braise ».
 *
 * La couleur dynamique de Material You est délibérément écartée : l'application se
 * consulte dehors, souvent en plein soleil, et une palette accordée au fond d'écran
 * ne garantirait ni les contrastes ni la reconnaissance de la marque.
 */
val BraiseLight = lightColorScheme(
    primary = Color(0xFFA83A16), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF3A1002),
    secondary = Color(0xFF4E6B57), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D8), onSecondaryContainer = Color(0xFF1F3A2B),
    tertiary = Color(0xFF8A5A00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0B2), onTertiaryContainer = Color(0xFF3D2A00),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBF7F3), onBackground = Color(0xFF221913),
    surface = Color(0xFFFBF7F3), onSurface = Color(0xFF221913),
    surfaceVariant = Color(0xFFEDE3DA), onSurfaceVariant = Color(0xFF56483F),
    surfaceContainerLow = Color(0xFFF7F1EB), surfaceContainer = Color(0xFFF4EDE6),
    surfaceContainerHigh = Color(0xFFEEE6DE),
    outline = Color(0xFF8A7A6F), outlineVariant = Color(0xFFD8CBC1),
)

val BraiseDark = darkColorScheme(
    primary = Color(0xFFFFB59A), onPrimary = Color(0xFF5A1B04),
    primaryContainer = Color(0xFF82290C), onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFB4CDBA), onSecondary = Color(0xFF1F3A2B),
    secondaryContainer = Color(0xFF35513E), onSecondaryContainer = Color(0xFFD5E8DA),
    tertiary = Color(0xFFFFD08A), onTertiary = Color(0xFF3D2A00),
    tertiaryContainer = Color(0xFF5C4200), onTertiaryContainer = Color(0xFFFFE0B2),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF171310), onBackground = Color(0xFFEEE3DC),
    surface = Color(0xFF171310), onSurface = Color(0xFFEEE3DC),
    surfaceVariant = Color(0xFF4A3F38), onSurfaceVariant = Color(0xFFD6C6BB),
    surfaceContainerLow = Color(0xFF1D1815), surfaceContainer = Color(0xFF221C18),
    surfaceContainerHigh = Color(0xFF2C2521),
    outline = Color(0xFF9E8B80), outlineVariant = Color(0xFF4A3F38),
)

/**
 * Couleurs des tracés posés sur la carte.
 *
 * Elles répondent au **fond de carte**, pas au thème de l'application : c'est du beige,
 * du vert de parc et du gris routier qu'il faut se détacher, pas de la surface Compose.
 *
 * Deux choix méritent d'être conservés si la palette évolue. Le brun-braise est
 * nettement plus sombre que le beige d'OpenStreetMap et ne se confond ni avec l'orange
 * pastel des routes secondaires ni avec le vert des parcs. Le magenta est la seule
 * famille chromatique absente des tuiles : une portion en alerte se repère sans avoir à
 * lire la légende.
 *
 * `primary` ne convient pas pour l'itinéraire — sur tuiles sombres il tombe sous le
 * rapport de contraste de 3:1.
 */
@Immutable
data class MapColors(
    val route: Color,
    /** Liseré dessiné sous l'itinéraire : c'est lui qui le détache du fond. */
    val routeCasing: Color,
    val targetShape: Color,
    val unfollowed: Color,
    val startMarkerRing: Color,
    val startMarkerCore: Color,
)

val LightMapColors = MapColors(
    route = Color(0xFF8C3410),
    routeCasing = Color(0xFFFFFFFF),
    targetShape = Color(0xFF5C534C),
    unfollowed = Color(0xFFC81E64),
    startMarkerRing = Color(0xFFFFFFFF),
    startMarkerCore = Color(0xFF8C3410),
)

val DarkMapColors = MapColors(
    route = Color(0xFFFF8A50),
    routeCasing = Color(0xFF1A1512),
    targetShape = Color(0xFFA99789),
    unfollowed = Color(0xFFFF5C93),
    startMarkerRing = Color(0xFF171310),
    startMarkerCore = Color(0xFFFF8A50),
)

val LocalMapColors = staticCompositionLocalOf { LightMapColors }
