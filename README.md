# Fond Vert Studio

Application Android native pour filmer une personne détourée devant un décor image ou vidéo, sans serveur payant.

## Fonctions

- caméra avant ou arrière en Full HD par défaut avec détourage IA en direct ;
- les images et vidéos importées servent exclusivement de décor et ne sont jamais détourées ;
- aperçu du montage final en direct ;
- décors sans fond, vert pur, noir, blanc, image ou vidéo ;
- trois profils de contour : HD net, cheveux et doux ;
- gros bouton d’enregistrement avec chronomètre et audio ;
- export MP4 H.264 jusqu’en 1080p à 30 images/s et haut débit ;
- export PNG réellement transparent ;
- traitement local : aucun compte, aucune clé API et aucun envoi vers un serveur.

## Technologie

- Java 17 ;
- minSdk 21, targetSdk 34 et compileSdk 34 ;
- CameraX pour la caméra et l’enregistrement ;
- ML Kit Selfie Segmentation en mode flux, inférence optimisée et pixels Full HD conservés ;
- masque brut redimensionné en bilinéaire, bords renforcés et détails caméra accentués ;
- MediaCodec et MediaMuxer pour l’encodage vidéo local ;
- WorkManager pour les exports longs.

ML Kit nécessite Android 6.0 ou plus récent pour le détourage. L’application reste installable à partir d’Android 5.0 et affiche une information claire sur Android 5.x.

## Fonctionnement

1. Choisir une image ou une vidéo de décor (elle reste intacte).
2. Se placer devant la caméra : seul le flux caméra est détouré.
3. Appuyer sur **ENREGISTRER**, puis **ARRÊTER**. Le montage final est automatiquement ajouté à la galerie.

## Sans fond dans une vidéo

Le format MP4/H.264 Android ne conserve pas de canal alpha. Un projet vidéo choisi comme « transparent » est donc exporté sur un vert pur `#00FF00`, prêt pour la suppression chromatique dans CapCut ou un autre éditeur. Les photos PNG conservent une vraie transparence.

## Compilation

```bash
./gradlew assembleDebug
```

L’APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Le workflow GitHub Actions **Compiler APK Android** compile également le projet et publie un artefact nommé **Fond-Vert-Studio-APK**.
