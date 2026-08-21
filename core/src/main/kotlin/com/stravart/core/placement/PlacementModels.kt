package com.stravart.core.placement

import com.stravart.core.geo.LatLon
import com.stravart.core.shape.AnchorMode
import com.stravart.core.shape.ShapePath

/** Une façon de poser la forme : où, tournée comment, à quelle longueur. */
data class Placement(
    val anchor: LatLon,
    val rotationDeg: Double,
    val distanceMeters: Double,
)

/**
 * Ce que vaut un placement, avant tout calcul d'itinéraire.
 *
 * @param meanCostMeters prix moyen de rattachement au réseau. C'est une *borne
 *   inférieure* de l'écart que fera le tracé réel : le moteur ne pourra jamais coller
 *   à la forme de plus près que la rue la plus proche.
 * @param uncoveredRatio part de la forme qu'aucune voie exploitable ne longe —
 *   fleuve, emprise ferroviaire, grand domaine fermé.
 */
data class PlacementScore(
    val meanCostMeters: Double,
    val uncoveredRatio: Double,
)

data class ScoredPlacement(val placement: Placement, val score: PlacementScore)

/** Ce que l'utilisateur a demandé, et autour de quoi la recherche s'organise. */
data class PlacementRequest(
    val shape: ShapePath,
    val anchor: LatLon,
    val distanceMeters: Double,
    val mode: AnchorMode = AnchorMode.START,
    val mirrored: Boolean = false,
)

/**
 * Étendue et finesse de la recherche.
 *
 * @param radiusMeters rayon dans lequel déplacer le départ ; zéro laisse le départ
 *   imposé et ne cherche que l'orientation et la longueur.
 * @param distanceTolerance latitude accordée sur la longueur demandée. Étirer un peu
 *   la forme peut lui faire accrocher une rue bien orientée ; au-delà de quelques
 *   pour cent, ce n'est plus le parcours demandé.
 * @param bearingWeightMeters ce que coûte un désaccord d'orientation complet. Le
 *   réglage central : il dit combien de mètres d'éloignement on échange contre une
 *   rue qui va dans le bon sens. Quatre-vingt-dix mètres est la valeur mesurée sur
 *   ville simulée ; en dessous de quarante la recherche se laisse séduire par des
 *   rues proches mais mal orientées, au-dessus de cent quarante elle s'éloigne trop.
 * @param maxMatchMeters au-delà de cette distance, la portion est comptée comme non
 *   desservie. **Ce réglage n'est pas mesuré** : le réseau simulé est trop dense pour
 *   qu'un point y soit jamais hors de portée, et le faire varier de 80 à 200 m n'y
 *   change rien. Il attend une vérification sur réseau réel.
 */
data class PlacementSearchOptions(
    val radiusMeters: Double = 0.0,
    val positionStepMeters: Double = 300.0,
    val coarseRotationStepDeg: Double = 30.0,
    val fineRotationStepDeg: Double = 5.0,
    val distanceTolerance: Double = 0.10,
    val scaleSteps: Int = 2,
    val coarseSamples: Int = 48,
    val fineSamples: Int = 120,
    val refineCount: Int = 24,
    val results: Int = 5,
    val bearingWeightMeters: Double = 90.0,
    val maxMatchMeters: Double = 120.0,
) {
    init {
        require(radiusMeters >= 0) { "le rayon ne peut être négatif" }
        require(positionStepMeters > 0) { "le pas de position doit être positif" }
        require(coarseRotationStepDeg > 0 && fineRotationStepDeg > 0) { "pas de rotation invalide" }
        require(distanceTolerance in 0.0..0.5) { "tolérance de distance déraisonnable" }
        require(scaleSteps >= 0) { "nombre de paliers d'échelle négatif" }
        require(coarseSamples >= 8 && fineSamples >= coarseSamples) { "échantillonnage invalide" }
        require(results >= 1 && refineCount >= results) { "nombre de résultats invalide" }
    }
}
