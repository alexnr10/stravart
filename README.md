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

**La densité décide de la fidélité.** Entre deux points de passage, le moteur est
libre : il préférera la belle avenue à la petite rue qui longeait la forme, et il ne
s'en prive pas. Les resserrer le ramène sur la ligne. Un point tous les 120 m en
course à pied (200 m à vélo) réduit d'un quart l'écart moyen à la forme par rapport à
un point tous les 300 m — et resserrer davantage n'apporte presque plus rien : au-delà
de ce palier, c'est la maille du réseau qui limite, pas l'échantillonnage. On ne peut
pas passer plus près qu'à la rue la plus proche.

Cela ne coûte d'ailleurs pas plus cher au serveur : découper en tronçons courts lui
épargne les longues recherches de chemin. Le plafond est de 80 points par requête. Si
une requête est refusée, `BRouterEngine` réessaie avec deux fois moins de points —
moins fidèle, mais un parcours plutôt qu'un échec. **Ce repli est affiché** : un
essai à 150 points s'est soldé par une dégradation silencieuse jusqu'à 38 points, et
c'est de ne pas l'avoir vu qui a coûté cher.

### Quand la forme ne passe nulle part

Resserrer les points de passage ne sert que là où le moteur avait le choix. Là où il
n'en a pas — un fleuve dont les ponts sont espacés, un jardin fermé aux vélos, une
emprise ferroviaire — les points de passage d'une même portion se rattachent tous à
la même voie de contournement, et le tracé s'en va avec eux. En **ajouter** n'y change
rien : les nouveaux se rattachent au même endroit. C'est mesuré : concentrer les
points là où l'itinéraire dérive n'a rien donné du tout.

Ce qui reste à essayer, c'est de les **déplacer**, ou de cesser de les imposer. Si l'itinéraire est parti trois
cents mètres au sud, c'est que la voie qu'il a trouvée est au sud ; `WaypointRelocator`
pousse alors le point d'autant vers le nord, pour que le moteur cherche de ce côté-là.
Au-delà de 300 m d'écart, il ne déplace plus : il **écarte** le point. Un point tombé
au milieu d'un fleuve n'est sur aucune voie ; le moteur le rattache tout de même à une
berge, et ce point imposé décide alors de l'endroit où l'on traverse — parfois un pont
bien plus loin que celui qui longeait la forme. Le retirer rend au moteur la liberté de
traverser au moins cher, c'est-à-dire en général au plus près. Jamais deux points
d'affilée, pour ne pas lui rendre trop de liberté d'un coup.
Le pari peut échouer — la voie espérée n'existe pas toujours — aussi le résultat n'est
retenu que s'il rapproche vraiment le tracé de la forme, sans allonger la distance ni
ajouter de retour sur ses pas. Il ne coûte donc jamais qu'un appel réseau.

Honnêtement : le simulateur n'a pas su confirmer ce gain, sa barrière étant symétrique
— les deux rives y sont à égale distance de la forme, et déplacer d'un côté ou de
l'autre revient au même. Sur une ville réelle, où la forme longe rarement le milieu
exact d'un obstacle, l'issue peut différer. Le nombre de points replacés est affiché
avec le résultat, précisément pour qu'on puisse en juger.

`ShapeCoverage` identifie par ailleurs les portions restées inaccessibles — plus de
100 m d'écart, sur au moins 150 m de forme — et l'application les trace **en rouge sur
la carte**. Mieux vaut dire « ici, aucune voie ne suivait le dessin » que de laisser
soupçonner un défaut de calcul.

### Dire ce que le moteur a réellement fait

Deux replis peuvent transformer le résultat sans rien dire : un profil de routage
différent de celui de l'activité — passer d'un profil piéton à un profil vélo exclut
d'un coup les allées de parc — et une requête raccourcie faute de points acceptés. La
carte de résultat affiche donc le profil employé, le nombre de points de passage
réellement soumis, et combien ont été replacés.

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
- **Les grandes distances demandent des formes simples.** Au-delà de 10 km, le
  plafond de 80 points de passage fait remonter l'espacement au-dessus de 120 m et
  les détails commencent à se perdre.
- **Les obstacles ne se contournent pas.** Là où aucune voie ne longe la forme, le
  tracé s'en écarte ; l'application le signale en rouge plutôt que de faire semblant.
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
