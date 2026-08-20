# StravArt

Application Android qui transforme **une forme, une distance et un point de départ**
en un itinéraire de course à pied ou de vélo suivant cette forme au plus près, puis
l'exporte en **GPX** pour Garmin Connect ou Strava.

---

## Ce que fait l'application

1. On choisit une forme — douze formes intégrées (cœur, étoile, éclair, poisson…) ou
   un dessin fait au doigt.
2. On règle la distance (1 à 60 km) et l'activité (course ou vélo).
3. On pose le point de départ : bouton « ma position », recherche d'adresse, ou appui
   long sur la carte. La forme s'affiche aussitôt sur la carte, avant tout calcul :
   inutile d'attendre un aller-retour réseau pour voir qu'elle tombe dans le fleuve.
4. « Créer le parcours » calcule un itinéraire qui **suit les rues et les chemins**,
   dessine la forme demandée et mesure la bonne distance. Les détours dans les
   impasses sont retirés ; si le quartier ne permet pas de boucler sans revenir sur
   ses pas, l'application le dit au lieu de produire un tracé bancal.
5. « Partager » ou « Enregistrer » produit un `.gpx` prêt à importer.

Le parcours est une boucle : il revient à son point de départ.

### Importer le GPX

- **Garmin Connect** : Entraînement › Parcours › Importer.
- **Strava** : Mes itinéraires › Importer un GPX.

Le fichier est un GPX 1.1 avec une trace `<trk>` sans horodatage — c'est un parcours à
suivre, pas une activité déjà réalisée — et l'altitude quand le moteur de routage la
fournit.

---

## Comment ça marche

Le problème n'est pas de dessiner une forme sur une carte : c'est de la dessiner **avec
des rues**. Trois étapes, dans `RouteGenerator`.

### 1. Poser la forme

Chaque forme est un trait unique, normalisé dans un carré de côté 1 (`ShapePath`). On
connaît donc sa longueur *relative*. Pour un parcours de 10 km, on la met à l'échelle
qui donne exactement 10 km, on lui applique la rotation et le miroir demandés, puis on
la projette autour du point de départ (`ShapeProjector`).

La projection est équirectangulaire, calée sur l'ellipsoïde WGS84 à la latitude de
départ. Sur les quelques dizaines de kilomètres qui nous concernent l'erreur est
négligeable, et surtout la projection et la mesure de distance utilisent le même
modèle : une forme projetée pour mesurer 10 km mesure bien 10 km.

### 2. Coller aux routes

On échantillonne des points de passage le long du tracé idéal et on demande au moteur
de routage de les relier.

L'échantillonnage n'est pas régulier, et c'est important. Un échantillonnage régulier
**rabote les angles** : sur un triangle, les trois sommets tombent entre deux
échantillons et disparaissent — un triangle arrondi est un cercle. `WaypointSampler`
garde donc d'abord les sommets (les changements de direction de plus de 25°), puis
répartit le budget restant entre eux au prorata des longueurs.

### 3. Retirer les allers-retours

Un point de passage qui tombe au fond d'une impasse oblige le moteur à y entrer puis
à en ressortir par le même chemin. C'est correct du point de vue du routage — il
fallait bien atteindre ce point — mais cela ruine le dessin, et personne ne court
volontairement 300 m dans un cul-de-sac pour faire demi-tour.

`SpurTrimmer` repère ces excursions à un critère simple : **elles n'enferment aucune
surface**. Un aller-retour a une aire nulle, là où un vrai tour de pâté de maisons en
enferme une comparable au carré de sa longueur. C'est ce qui les distingue, et non
leur longueur. Le tracé restant suit toujours des rues réelles : couper un
aller-retour revient à passer devant l'entrée de l'impasse sans y entrer.

### 4. Corriger la distance

Suivre la voirie oblige à des détours : l'itinéraire obtenu est systématiquement plus
long que la forme idéale, de 15 à 40 % selon le quartier. On rétrécit alors la forme
en proportion de l'écart constaté et on recommence. Deux à trois itérations suffisent
à retomber dans la tolérance de 3 %, chacune coûtant un appel réseau.

### Quand le quartier ne s'y prête pas

Certains endroits ne permettent tout simplement pas de boucler : lotissement en
peigne, presqu'île, hameau desservi par une seule route. Aucun réglage de forme n'y
changera rien.

Une fois les allers-retours évitables retirés, `RouteOverlap` mesure ce qu'il reste
de parcouru deux fois. Au-delà de 30 % du parcours, l'application **refuse de
générer** et le dit, en suggérant un départ mieux desservi — plutôt que de livrer un
tracé où l'on refait le même kilomètre à l'envers. Le seuil se règle par
`RouteRequest.maxOverlapRatio`.

### Et la ressemblance ?

`ShapeFidelity` compare le tracé obtenu à la forme visée : écart moyen dans les deux
sens (les points de la forme qui s'éloignent de l'itinéraire *et* les détours de
l'itinéraire hors de la forme), rapporté à la taille de la forme. Le résultat est une
note sur 100 affichée avec le parcours — si le quartier ne s'y prête pas, autant le
savoir avant de partir courir.

---

## Moteurs de routage

| Moteur | Clé d'API | Remarques |
| --- | --- | --- |
| **BRouter** (défaut) | non | Serveur communautaire, pensé pour le vélo et la randonnée. Fournit l'altitude. |
| **OSRM** | non | Le serveur de démonstration public ne sert que le profil voiture ; renseignez l'adresse de votre propre instance dans les options avancées. |
| **À vol d'oiseau** | — | Hors ligne. Forme parfaite, distance exacte, mais le tracé ignore les rues. Utile pour prévisualiser. |

Les serveurs publics de BRouter et de Nominatim (recherche d'adresse) sont tenus par
des bénévoles. L'application les ménage : User-Agent identifiable, saisie temporisée
avant recherche, et cinq appels au maximum par génération.

---

## Installer l'application

Chaque build publie l'APK de débogage dans une **pre-release GitHub**, une par
branche, dont l'adresse ne change pas d'un build à l'autre :

<https://github.com/alexnr10/stravart/releases>

Ouvrez la page depuis le téléphone, touchez `stravart-debug.apk`, autorisez
l'installation depuis le navigateur, et c'est fait. Android 8.0 minimum.

L'APK est signé avec la clé de débogage : c'est ce qu'il faut pour une
installation manuelle, mais une version signée autrement ne pourra pas
s'installer par-dessus — il faudra désinstaller d'abord.

---

## Construire le projet

Prérequis : JDK 17 et le SDK Android (API 35). Android Studio les installe pour vous.

```bash
./gradlew :core:test          # tests unitaires — quelques secondes, aucun SDK Android requis
./gradlew :app:assembleDebug  # APK de débogage dans app/build/outputs/apk/debug/
./gradlew installDebug        # installation sur un appareil branché
```

L'application vise `minSdk 26` (Android 8.0) et `targetSdk 35`.

### Organisation

```
core/   Kotlin pur, aucune dépendance Android — géométrie, formes, routage, GPX.
        Testable sur la JVM, donc testé : 65 tests unitaires.
app/    Interface Jetpack Compose, carte osmdroid, localisation, export.
```

La séparation est délibérée : toute la logique qui mérite d'être vérifiée vit dans un
module qu'on peut exercer sans émulateur.

---

## Permissions

| Permission | Usage |
| --- | --- |
| `INTERNET` | fond de carte, calcul d'itinéraire, recherche d'adresse |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | **facultatif** — uniquement le bouton « ma position » |

Aucune permission de stockage : les fichiers passent par le cache privé et le
sélecteur de documents du système.

---

## Limites connues

- **La forme ne rend pas partout.** Un cœur de 5 km dans un lotissement en impasse
  n'existe pas. La note de ressemblance le dit ; changer l'orientation, le départ ou
  la distance aide souvent plus que d'insister.
- **Les grandes distances demandent des formes simples.** Au-delà de 30 km, le budget
  de 40 points de passage laisse plus de 700 m entre deux jalons : les détails se
  perdent.
- **Le dénivelé** n'est renseigné que par BRouter.
- **L'interface est en français** uniquement pour l'instant.
- **Aucun test instrumenté** : l'interface n'est pas couverte, seule l'algorithmique
  l'est.

---

## Licence et données

Les fonds de carte proviennent d'OpenStreetMap (© les contributeurs OpenStreetMap,
sous [ODbL](https://www.openstreetmap.org/copyright)) et sont soumis à la
[politique d'usage des tuiles](https://operations.osmfoundation.org/policies/tiles/) —
à respecter avant toute distribution large de l'application.
