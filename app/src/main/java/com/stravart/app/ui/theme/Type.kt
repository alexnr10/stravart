package com.stravart.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stravart.app.R

/**
 * Archivo, empaquetée dans l'application.
 *
 * Embarquer les quatre graisses plutôt que de passer par les polices téléchargeables
 * évite de dépendre des services Google Play : l'application garde son identité sur un
 * appareil dégooglisé comme au premier lancement sans réseau.
 */
private val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
)

/**
 * Échelle typographique de la refonte, posée sur les emplacements Material 3.
 *
 * Aucun texte porteur d'information ne descend sous 12,5 sp : l'écran se lit debout,
 * dehors, souvent avant de partir courir.
 */
val StravArtTypography = Typography(
    // « 10,0 km » — la valeur que l'on règle du pouce et que l'on lit d'un coup d'œil.
    displaySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.68).sp,
    ),
    // Chiffres de la carte de résultat.
    headlineSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 26.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    // « StravArt » dans la barre supérieure.
    titleLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    ),
    // « Parcours généré », « Dessiner la forme ».
    titleMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // « Point de départ », « Forme », « Distance ».
    titleSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // Champs et valeurs saisies.
    bodyMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    ),
    // Libellé du lieu, ligne technique.
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    // Texte des boutons.
    labelLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Libellés sous les vignettes, légende, bornes du curseur.
    labelSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

/** Adresse de serveur : la seule chaîne où l'alignement des caractères aide à relire. */
val MonospaceFieldStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp,
    lineHeight = 18.sp,
)
