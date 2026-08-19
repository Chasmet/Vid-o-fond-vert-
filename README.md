# Fond Vert Studio

Application Android native pour filmer une personne détourée devant un décor image ou vidéo, sans serveur payant.

## Fonctions

- enregistrement caméra avant ou arrière en Full HD, avec aperçu léger et fluide ;
- les images et vidéos importées servent exclusivement de décor et ne sont jamais détourées ;
- aperçu du montage final en direct ;
- sujet déplaçable à un doigt et redimensionnable avec un pincement, même pendant la prise ;
- trajectoire et zoom tactiles interpolés puis reproduits dans la vidéo finale ;
- décors sans fond, vert pur, noir, blanc, image ou vidéo ;
- trois profils de contour : HD net, cheveux et doux, avec stabilisation temporelle adaptative ;
- gros bouton d’enregistrement avec chronomètre et audio ;
- export MP4 H.264 jusqu’en 1080p à 30 images/s et haut débit ;
- export PNG réellement transparent ;
- traitement local : aucun compte, aucune clé API et aucun envoi vers un serveur.

## Technologie

- Java 17 ;
- minSdk 21, targetSdk 34 et compileSdk 34 ;
- CameraX pour la caméra et l’enregistrement ;
- ML Kit Selfie Segmentation en mode flux non bloquant, avec rendu du masque accéléré par le GPU ;
- image caméra continue à 30 i/s et masque IA mis à jour séparément, sans figer l'aperçu ;
- masque stabilisé entre les images : contours calmes à l'arrêt, réponse rapide pendant les mouvements et absence de traîne prolongée ;
- masque d'export haute définition distinct du masque brut allégé utilisé pour l'aperçu ;
- décodage séquentiel par lots sur Android 9+ pour accélérer fortement la création vidéo ;
- masque brut redimensionné en bilinéaire, bords renforcés et détails caméra accentués ;
- MediaCodec et MediaMuxer pour l’encodage vidéo local ;
- WorkManager pour les exports longs.

ML Kit nécessite Android 6.0 ou plus récent pour le détourage. L’application reste installable à partir d’Android 5.0 et affiche une information claire sur Android 5.x.

## Fonctionnement

1. Choisir une image ou une vidéo de décor (elle reste intacte).
2. Se placer devant la caméra : seul le flux caméra est détouré.
3. Glisser le sujet pour le placer et pincer avec deux doigts pour changer sa taille.
4. Appuyer sur **ENREGISTRER**, continuer à déplacer ou redimensionner le sujet si nécessaire, puis **ARRÊTER**. Le montage final et tous les mouvements tactiles sont automatiquement ajoutés à la galerie.

## Sans fond dans une vidéo

Le format MP4/H.264 Android ne conserve pas de canal alpha. Un projet vidéo choisi comme « transparent » est donc exporté sur un vert pur `#00FF00`, prêt pour la suppression chromatique dans CapCut ou un autre éditeur. Les photos PNG conservent une vraie transparence.

## Compilation

```bash
./gradlew assembleDebug
```

L’APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

Le workflow GitHub Actions **Compiler APK Android** compile également le projet et publie un artefact nommé **Fond-Vert-Studio-APK**.
