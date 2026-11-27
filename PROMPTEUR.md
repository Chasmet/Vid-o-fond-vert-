# Prompteur local v1.14.0
Moteur MIT [whisper.cpp v1.7.6](https://github.com/ggml-org/whisper.cpp/tree/v1.7.6), modèle multilingue small-q5_1. Téléchargement unique avec contrôle SHA-1 publié. Traitement sur appareil, sans serveur ni API. Les horodatages sont des segments, pas un alignement syllabique. La qualité dépend du mix; préférer la voix isolée. Le module conserve un projet local et permet la correction des lignes et de leur début, ainsi que la lecture d'un instrumental de structure identique. Pas de séparation de pistes intégrée. Le pipeline vidéo et le mécanisme de mise à jour sont conservés. Validation CI et signature requises avant publication.

## v1.14.1
Sélecteur tous fichiers, vérification réelle de lecture, nom et durée, WAV studio via le convertisseur AAC existant, progression native Whisper, caméra avec overlay de paroles et musique d'origine, pas d'incrustation des paroles dans le MP4. Les formats restent limités aux décodeurs disponibles sur Android; les WAV sont convertis en AAC pour la compatibilité et la durée d'analyse est limitée à 15 min. La caméra réutilise le flux d'export classique.

## v1.14.2
Le tournage prompteur utilise exclusivement la chanson importée (instrumental réservé à l'entraînement). Pas de microphone, pas de texte incrusté. En cas d'échec audio, aucune vidéo silencieuse n'est publiée : la prise est conservée et une nouvelle tentative est proposée. Le test instrumenté vérifie une seule piste audio importée et une piste vidéo. Les paroles suivent l'horloge de lecture du morceau.
