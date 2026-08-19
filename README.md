# Fond Vert Studio

Application Android native pour détourer une personne en direct, remplacer son arrière-plan et exporter le résultat sans serveur payant.

## Fonctions

- caméra avant ou arrière avec détourage IA en direct ;
- import d’une vidéo ou d’une photo ;
- aperçu transparent sur damier ;
- fonds vert pur, noir, blanc, image ou vidéo ;
- réglages du seuil et de la douceur des contours ;
- enregistrement caméra avec audio ;
- export MP4 H.264 en 720p ou 1080p ;
- export PNG réellement transparent ;
- traitement local : aucun compte, aucune clé API et aucun envoi vers un serveur.

## Technologie

- Java 17 ;
- minSdk 21, targetSdk 34 et compileSdk 34 ;
- CameraX pour la caméra et l’enregistrement ;
- ML Kit Selfie Segmentation en mode flux ;
- MediaCodec et MediaMuxer pour l’encodage vidéo local ;
- WorkManager pour les exports longs.

ML Kit nécessite Android 6.0 ou plus récent pour le détourage. L’application reste installable à partir d’Android 5.0 et affiche une information claire sur Android 5.x.

## Transparence vidéo

Le format MP4/H.264 Android ne conserve pas de canal alpha. Un projet vidéo choisi comme « transparent » est donc exporté sur un vert pur `#00FF00`, prêt pour la suppression chromatique dans CapCut ou un autre éditeur. Les photos PNG conservent une vraie transparence.

## Compilation

```bash
./gradlew assembleDebug
```

L’APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Le workflow GitHub Actions **Compiler APK Android** compile également le projet et publie un artefact nommé **Fond-Vert-Studio-APK**.
