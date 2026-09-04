# Fond Vert Studio

Application Android native pour filmer une personne détourée devant un décor image ou vidéo, sans serveur payant.

Version **1.12.8** : choix obligatoire 9:16/16:9, détection automatique du début de chanson et timeline audio réglable.

## Nouveautés v1.12.8

- choix obligatoire du format avant la caméra : **Vertical 9:16** ou **Horizontal 16:9** ;
- interface paysage dédiée pour Classique et Fond Vert ;
- rendu Fond Vert réellement encodé dans le format sélectionné (1080×1920 / 1920×1080 ou 720p équivalent) ;
- analyse automatique de la musique après import pour détecter le premier passage réellement audible ;
- le silence placé avant la chanson est ignoré automatiquement ;
- le départ détecté est placé directement sur la timeline ;
- réglage manuel conservé avec la SeekBar ;
- boutons **−0,1 s / AUTO / +0,1 s** pour ajuster précisément le départ ;
- la musique reste l'unique piste audio et le micro reste coupé ;
- versionCode 27 / versionName 1.12.8.

## Fonctions

- enregistrement caméra avant ou arrière en Full HD, avec aperçu léger et fluide ;
- les images et vidéos importées servent exclusivement de décor et ne sont jamais détourées ;
- aperçu du montage final en direct ;
- sujet déplaçable à un doigt et redimensionnable avec un pincement, même pendant la prise ;
- trajectoire et zoom tactiles interpolés puis reproduits dans la vidéo finale ;
- décors sans fond, vert pur, noir, blanc, image ou vidéo ;
- profils de contour et stabilisation temporelle du masque ;
- gros bouton d'enregistrement avec chronomètre et audio ;
- mode **Clip Musique** avec musique maître, prises successives et timeline ;
- export MP4 H.264 jusqu'en 1080p ;
- export PNG réellement transparent ;
- traitement local : aucun compte, aucune clé API et aucun envoi du contenu vidéo vers un serveur.

## Réglages et mises à jour intégrées

L'écran d'accueil contient **Réglages · Mises à jour**.

Le menu affiche :

- la version actuellement installée ;
- la dernière version disponible sur GitHub Releases ;
- la date de la dernière vérification ;
- les notes de version ;
- un contrôle automatique des nouvelles versions ;
- la progression du téléchargement en pourcentage ;
- les Mo téléchargés / taille totale ;
- la vitesse de téléchargement ;
- la validation de l'APK avant installation ;
- l'ouverture directe de l'installateur Android.

Avant installation, l'application vérifie que l'APK téléchargé :

1. est un APK Android valide ;
2. possède le même `applicationId` ;
3. possède la même signature que l'application installée ;
4. possède un `versionCode` supérieur.

Une mise à jour Android normale conserve les données privées de l'application. Les exports finaux sont enregistrés dans les collections multimédia publiques `Movies/FondVertStudio` et `Pictures/FondVertStudio`.

> **Migration depuis les anciennes APK debug** : les anciennes builds GitHub Actions n'utilisaient pas une signature de publication stable. Si Android refuse la toute première installation de la v1.11.0 ou supérieure par-dessus une ancienne APK debug, une désinstallation/réinstallation unique peut être nécessaire. Les exports déjà présents dans Movies/Pictures restent indépendants de l'application. À partir de la nouvelle chaîne de publication signée, les mises à jour suivantes utilisent la même signature.

## Technologie

- Java 17 ;
- minSdk 21, targetSdk 34 et compileSdk 34 ;
- CameraX pour la caméra et l'enregistrement ;
- ML Kit Selfie Segmentation pour le détourage ;
- MediaMetadataRetriever avec décodage par lots sur Android 9+ ;
- MediaCodec et MediaMuxer pour l'encodage vidéo local ;
- WorkManager pour les exports longs ;
- GitHub Releases comme source des mises à jour intégrées.

ML Kit nécessite Android 6.0 ou plus récent pour le détourage. L'application reste installable à partir d'Android 5.0 et affiche une information claire sur Android 5.x.

## Sans fond dans une vidéo

Le format MP4/H.264 Android ne conserve pas de canal alpha. Un projet vidéo choisi comme « transparent » est donc exporté sur un vert pur `#00FF00`, prêt pour la suppression chromatique dans CapCut ou un autre éditeur. Les photos PNG conservent une vraie transparence.

## Compilation locale

```bash
./gradlew assembleDebug
```

APK debug : `app/build/outputs/apk/debug/app-debug.apk`.

Pour contrôler la version distribuée :

```bash
./gradlew clean assembleDebug assembleRelease lintDebug lintRelease
```

## GitHub Actions et publication

Le workflow **Compiler APK Android** :

1. compile les APK debug et release ;
2. exécute Android Lint ;
3. vérifie que l'ancien moteur expérimental n'est pas réintroduit ;
4. signe l'APK release avec la clé stable du pipeline ;
5. vérifie la signature avec `apksigner` ;
6. publie l'APK installable dans GitHub Actions ;
7. sur `main`, crée automatiquement la Release `vX.Y.Z` utilisée par l'application pour les mises à jour.

Pour chaque nouvelle version, il faut incrémenter **`versionCode`** et **`versionName`** dans `app/build.gradle`. Le workflow refuse de remplacer silencieusement une clé de signature déjà utilisée pour une Release.
