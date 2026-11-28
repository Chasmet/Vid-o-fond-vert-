# Prompteur local v1.14.0
Moteur MIT [whisper.cpp v1.7.6](https://github.com/ggml-org/whisper.cpp/tree/v1.7.6), modèle multilingue small-q5_1. Téléchargement unique avec contrôle SHA-1 publié. Traitement sur appareil, sans serveur ni API. Les horodatages sont des segments, pas un alignement syllabique. La qualité dépend du mix; préférer la voix isolée. Le module conserve un projet local et permet la correction des lignes et de leur début, ainsi que la lecture d'un instrumental de structure identique. Pas de séparation de pistes intégrée. Le pipeline vidéo et le mécanisme de mise à jour sont conservés. Validation CI et signature requises avant publication.

## v1.14.1
Sélecteur tous fichiers, vérification réelle de lecture, nom et durée, WAV studio via le convertisseur AAC existant, progression native Whisper, caméra avec overlay de paroles et musique d'origine, pas d'incrustation des paroles dans le MP4. Les formats restent limités aux décodeurs disponibles sur Android; les WAV sont convertis en AAC pour la compatibilité et la durée d'analyse est limitée à 15 min. La caméra réutilise le flux d'export classique.

## v1.14.2
Le tournage prompteur utilise exclusivement la chanson importée (instrumental réservé à l'entraînement). Pas de microphone, pas de texte incrusté. En cas d'échec audio, aucune vidéo silencieuse n'est publiée : la prise est conservée et une nouvelle tentative est proposée. Le test instrumenté vérifie une seule piste audio importée et une piste vidéo. Les paroles suivent l'horloge de lecture du morceau.

## v1.14.3
Large V3 Turbo Q5_0, modèle officiel 547 MiB, SHA-1 e050f7970618a659205450ad97eb95a18d69c9ee. Progression réelle, chronomètre actif pendant le chargement, annulation native et piste vocale studio optionnelle (même origine temporelle que le master). Normalisation de l'audio d'analyse seulement. Pas de séparation automatique de voix; pas de garantie de précision pour le rap chanté. L'export conserve la chanson originale (AAC dans le MP4 selon compatibilité).
## v1.15.0
Qwen3-ASR 0.6B INT8 via Sherpa-ONNX Android v1.13.7 officiel. Archive modèle téléchargée et vérifiée SHA-256, extraction limitée aux six composants attendus, traitement local. Choix Qwen ou Whisper Turbo, piste vocale optionnelle. Les horaires Qwen sont approximatifs par fenêtres de 8 secondes, modifiables. Le modèle 1.7B, la séparation automatique et ForcedAligner ne sont pas intégrés dans cette version. Export vidéo original et signature inchangés.

## 1.15.1
Qwen activé à partir d'Android 8 ; Whisper conservé sur Android 5 à 7. Tests de chargement JNI sur les systèmes compatibles. Le runtime officiel échoue sur API 23 (symbole __write_chk absent).

## v1.15.2
UVR MDX-NET Voc_FT (66,762,795 octets) via l'API C officielle sherpa-onnx v1.13.7, téléchargé avec contrôle SHA-256. Isolation locale stéréo à 16 kHz par fenêtres avec contexte, utilisée uniquement pour l'analyse, pas dans l'export. En cas d'échec, le mix est utilisé et l'erreur est signalée. Qwen 0.6B et Whisper sont conservés. La qualité et l'alignement réel du chant nécessitent des essais sur appareil; pas d'alignement forcé mot à mot ni de modèle 1.7B dans cette version.

## v1.15.3 — distribution entièrement hors ligne
Les trois poids (Qwen 0.6B, UVR et Whisper Turbo) sont inclus dans l'APK de production, vérifiés par SHA-256 lors de la compilation. Pas de téléchargement runtime ni dialogue d'installation réseau. Le premier usage prépare les fichiers dans le stockage privé de l'app. L'APK debug de CI reste léger; l'APK Release est complet. Le contrôle CI interdit de publier si un poids manque ou si l'APK dépasse la limite GitHub de 2 GiB. L'installation de cet APK volumineux sur téléphone reste à vérifier.

## v1.15.4
Caméra en haut de l'écran, avant/arrière et vertical/horizontal, accessible sans transcription complète. Si une analyse est en cours, l'utilisateur peut la conserver ou l'arrêter avant d'ouvrir la caméra. Export PDF A4 des paroles sauvegardées, avec ou sans horaires, via le sélecteur Android; pagination et test de lecture multipage. La musique originale reste la seule piste de l'export vidéo. Modèles embarqués et signature historique inchangés.

## v1.15.5
Whisper Turbo sans UVR par défaut (nouveaux réglages modifiables), décodage greedy à un candidat, Qwen par fenêtres de 12 s. Les trois modèles restent embarqués. Exports PDF détaillé, TXT, SRT et WebVTT UTF-8 avec début/fin corrigibles. Partage via Android. Les horaires Qwen restent approximatifs; aucun alignement mot par mot ni gain de vitesse garanti avant mesure sur téléphone.

## v1.15.6 — éditeur de paroles
Éditeur plein écran non destructif, lecture et curseur au millième, saisie 01:04.000 / 64,000 s / 64000 ms, ajustement début/fin ±100 ms, marquage à la position de lecture, écoute du passage, ajout de passages et de couplets (option de répartition temporelle approximative par ligne), scission au curseur audio et texte, suppression et annulation. Enregistrement explicite dans le projet existant, compatible caméra/PDF/TXT/SRT/VTT. Les périodes sans paroles ne prolongent plus le passage précédent. Pas de modification des modèles ni de signature. Les horaires manuels restent à vérifier à l'écoute.

## v1.15.7
Ajout direct par saisie début/fin sans déplacer le curseur, duplication de refrain/couplet, navigation vers un horaire exact et retour automatique au panneau d'édition à la sélection. Correctifs de compatibilité API anciennes et de cycle de vie du lecteur. Les modèles inclus et l'export restent inchangés.

## v1.15.8 — MCP CHK Universal Control
Connexion volontaire au serveur HTTPS existant via invitation `/api/onboard`, pas de Render supplémentaire. Le client utilise `/api/agent/{id}/poll` et `/result` seulement quand le prompteur est au premier plan. Secrets d'association dans noBackupFilesDir, aucune clé intégrée à l'APK. Commandes `prompter_status`, `lyrics_list` (offset/limit), `lyrics_add` (start_ms,end_ms,text,revision), `lyrics_update` (index et champs modifiés, revision), `lyrics_delete` (index,revision), `lyrics_restore` (revision), `prompter_seek` (position_ms), `prompter_play` (play,position_ms), `prompter_open_camera`, `lyrics_export_pdf`. Les écritures passent par l'autorisation du serveur, vérification de révision, sauvegarde locale; caméra et PDF nécessitent validation locale. Aucun transfert audio ou vidéo; aucune capture distante. Une tâche interrompue est signalée comme incertaine. Association et test bout en bout requis sur téléphone.
