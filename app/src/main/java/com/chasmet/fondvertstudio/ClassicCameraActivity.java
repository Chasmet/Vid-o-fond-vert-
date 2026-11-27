package com.chasmet.fondvertstudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mode classique type TikTok :
 * - caméra normale sans détourage ;
 * - micro de l'application toujours coupé ;
 * - seule la musique importée est intégrée au MP4 final.
 */
public final class ClassicCameraActivity extends AppCompatActivity {
    private enum CaptureState {
        IDLE,
        RECORDING,
        INTERRUPTED,
        FINALIZING
    }

    private PreviewView previewView;
    private MaterialButton recordButton;
    private MaterialButton flipButton;
    private MaterialButton qualityButton;
    private MaterialButton importAudioButton;
    private MaterialButton musicPlayButton;
    private MaterialButton audioMinusButton;
    private MaterialButton audioAutoButton;
    private MaterialButton audioPlusButton;
    private TextView timerView;
    private TextView audioName;
    private TextView audioStatus;
    private AudioTimelineView audioTimeline;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private int quality = 1080;
    private long recordingStartedAt;
    private long recordingDurationMs;
    private boolean cameraReady;
    private CaptureState captureState = CaptureState.IDLE;
    private boolean stopRequested;
    private boolean interruptedByLifecycle;

    private Uri musicUri;
    private Uri preparedMusicUri;
    private File preparedAudioFile;
    private MediaPlayer musicPlayer;
    private boolean musicPrepared;
    private boolean audioPreparing;
    private int audioStartMs;
    private int detectedAudioStartMs;
    private int audioLoadGeneration;
    private boolean audioStartEdited;
    private boolean horizontalFormat;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioAnalysisExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String[]> audioPicker;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (activeRecording == null || stopRequested) return;
            long elapsed = SystemClock.elapsedRealtime() - recordingStartedAt;
            String value = formatDuration(elapsed);
            timerView.setText(value);
            recordButton.setText("■  ARRÊTER   " + value);
            if (musicPrepared && musicPlayer != null) {
                int progress = (int) Math.min(Math.max(0, musicPlayer.getDuration()),
                        Math.max(0L, (long) audioStartMs + elapsed));
                audioTimeline.setPlaybackPositionMs(progress);
                updateAudioPosition(progress);
                if (musicPlayer.getDuration() > 0
                        && progress >= musicPlayer.getDuration() - 50) {
                    stopRecording();
                    return;
                }
            }
            uiHandler.postDelayed(this, 200L);
        }
    };

    private final Runnable musicProgressTick = new Runnable() {
        @Override
        public void run() {
            MediaPlayer player = musicPlayer;
            if (!musicPrepared || player == null) return;
            try {
                if (!player.isPlaying()) {
                    audioTimeline.setPlaybackActive(false);
                    return;
                }
                int position = Math.max(0, player.getCurrentPosition());
                audioTimeline.setPlaybackPositionMs(position);
                updateAudioPosition(position);
                uiHandler.postDelayed(this, 50L);
            } catch (Exception ignored) {
                audioTimeline.setPlaybackActive(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CaptureFormat.applyRequestedOrientation(this, getIntent());
        super.onCreate(savedInstanceState);
        horizontalFormat = CaptureFormat.isHorizontal(getIntent());
        setContentView(R.layout.activity_classic_camera);

        previewView = findViewById(R.id.classicPreview);
        recordButton = findViewById(R.id.classicRecordButton);
        flipButton = findViewById(R.id.classicFlipButton);
        qualityButton = findViewById(R.id.classicQualityButton);
        importAudioButton = findViewById(R.id.classicImportAudioButton);
        musicPlayButton = findViewById(R.id.classicMusicPlayButton);
        audioMinusButton = findViewById(R.id.classicAudioMinusButton);
        audioAutoButton = findViewById(R.id.classicAudioAutoButton);
        audioPlusButton = findViewById(R.id.classicAudioPlusButton);
        timerView = findViewById(R.id.classicTimer);
        audioName = findViewById(R.id.classicAudioName);
        audioStatus = findViewById(R.id.classicAudioStatus);
        audioTimeline = findViewById(R.id.classicAudioTimeline);

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startCamera();
                    else Toast.makeText(this, "La caméra est nécessaire pour filmer",
                            Toast.LENGTH_LONG).show();
                });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    persist(uri);
                    loadMusic(uri);
                });

        findViewById(R.id.classicBackButton).setOnClickListener(v -> finish());
        importAudioButton.setOnClickListener(v -> {
            if (activeRecording == null && !audioPreparing) {
                audioPicker.launch(new String[]{"audio/*"});
            }
        });
        musicPlayButton.setOnClickListener(v -> toggleMusic());
        audioMinusButton.setOnClickListener(v -> adjustAudioStart(-100));
        audioAutoButton.setOnClickListener(v -> applyDetectedAudioStart());
        audioPlusButton.setOnClickListener(v -> adjustAudioStart(100));
        recordButton.setOnClickListener(v -> {
            if (activeRecording == null) startRecording();
            else stopRecording();
        });
        flipButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        qualityButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });
        audioTimeline.setOnPositionChangeListener(
                new AudioTimelineView.OnPositionChangeListener() {
            @Override
            public void onPositionChanged(int positionMs, boolean fromUser) {
                audioStartMs = positionMs;
                if (fromUser) audioStartEdited = true;
                updateAudioPosition(positionMs);
                if (fromUser && musicPrepared && musicPlayer != null
                        && activeRecording == null) {
                    try { musicPlayer.seekTo(positionMs); } catch (Exception ignored) { }
                }
            }

            @Override
            public void onScrubFinished(int positionMs) {
                audioStartMs = positionMs;
                audioStartEdited = true;
                audioStatus.setText("DÉPART MANUEL · " + formatPrecise(positionMs));
            }
        });

        recordButton.setEnabled(false);
        musicPlayButton.setEnabled(false);
        audioTimeline.setEnabled(false);
        audioMinusButton.setEnabled(false);
        audioAutoButton.setEnabled(false);
        audioPlusButton.setEnabled(false);
        audioStatus.setText(CaptureFormat.label(horizontalFormat)
                + " · MICRO COUPÉ · IMPORTE UNE MUSIQUE");
        previewView.post(this::requestCamera);
    }

    private void requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        if (activeRecording != null) return;
        cameraReady = false;
        updateRecordState();

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Quality preferred = quality == 1080 ? Quality.FHD : Quality.HD;
                QualitySelector selector = QualitySelector.from(
                        preferred,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(selector)
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                int targetRotation = CaptureFormat.cameraTargetRotation(
                        this, horizontalFormat);
                videoCapture.setTargetRotation(targetRotation);

                Preview preview = new Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture);
                cameraReady = true;
                updateRecordState();
            } catch (Exception error) {
                cameraReady = false;
                updateRecordState();
                Toast.makeText(this, "Caméra indisponible",
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void loadMusic(Uri uri) {
        final int generation = ++audioLoadGeneration;
        releaseMusic();
        deletePreparedAudio();
        musicUri = uri;
        audioStartEdited = false;
        detectedAudioStartMs = 0;
        audioName.setText(displayName(uri));
        audioStatus.setText("CHARGEMENT MUSIQUE…");
        audioTimeline.setDurationMs(0);
        audioTimeline.setWaveform(null);
        audioTimeline.setDetectedStartMs(-1);
        audioPreparing = true;
        updateRecordState();

        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(this, uri);
            musicPlayer.setOnErrorListener((player, what, extra) -> {
                if (generation != audioLoadGeneration || player != musicPlayer) return true;
                audioPreparing = false;
                musicPrepared = false;
                preparedMusicUri = null;
                audioStatus.setText("MUSIQUE ILLISIBLE");
                audioTimeline.setDurationMs(0);
                updateRecordState();
                Toast.makeText(this, "Musique illisible", Toast.LENGTH_LONG).show();
                return true;
            });
            musicPlayer.setOnPreparedListener(player -> {
                if (generation != audioLoadGeneration || player != musicPlayer) return;
                musicPrepared = true;
                preparedMusicUri = uri;
                int duration = Math.max(1, player.getDuration());
                audioTimeline.setDurationMs(duration);
                audioTimeline.setPositionMs(0);
                audioTimeline.setDetectedStartMs(-1);
                audioTimeline.setWaveform(null);
                audioStartMs = 0;
                audioTimeline.setEnabled(true);
                musicPlayButton.setEnabled(true);
                audioMinusButton.setEnabled(true);
                audioPlusButton.setEnabled(true);
                audioAutoButton.setEnabled(false);
                updateAudioPosition(0);
                audioStatus.setText("MUSIQUE PRÊTE · analyse rapide en arrière-plan");
                updateRecordState();
                prepareAudio(uri, duration, generation);
            });
            musicPlayer.setOnCompletionListener(player -> {
                if (generation != audioLoadGeneration || player != musicPlayer) return;
                uiHandler.removeCallbacks(musicProgressTick);
                audioTimeline.setPlaybackPositionMs(Math.max(0, player.getDuration()));
                audioTimeline.setPlaybackActive(false);
                musicPlayButton.setText("▶ ÉCOUTER");
                audioStatus.setText("FIN DE LA MUSIQUE · arrêt automatique");
                if (activeRecording != null && !stopRequested) stopRecording();
            });
            musicPlayer.prepareAsync();
        } catch (Exception error) {
            audioPreparing = false;
            musicPrepared = false;
            updateRecordState();
            Toast.makeText(this, "Musique illisible", Toast.LENGTH_LONG).show();
        }
    }

    private void prepareAudio(Uri source, int durationMs, int generation) {
        audioAnalysisExecutor.execute(() -> {
            try {
                AudioWaveformExtractor.Analysis analysis =
                        AudioWaveformExtractor.analyze(this, source, durationMs, 1500);
                int finalDetected = Math.max(0,
                        Math.min(durationMs - 1, analysis.detectedStartMs));
                runOnUiThread(() -> {
                    if (generation != audioLoadGeneration || !source.equals(musicUri)) return;
                    audioPreparing = false;
                    detectedAudioStartMs = finalDetected;
                    audioTimeline.setWaveform(analysis.waveform);
                    audioTimeline.setDetectedStartMs(finalDetected);
                    if (!audioStartEdited && captureState == CaptureState.IDLE
                            && activeRecording == null) {
                        audioStartMs = finalDetected;
                        audioTimeline.setPositionMs(finalDetected);
                    }
                    audioAutoButton.setEnabled(activeRecording == null
                            && captureState == CaptureState.IDLE);
                    if (activeRecording == null && captureState == CaptureState.IDLE) {
                        audioStatus.setText("DÉBUT DÉTECTÉ · "
                                + formatPrecise(finalDetected)
                                + " · musique prête");
                    }
                    updateRecordState();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != audioLoadGeneration) return;
                    audioPreparing = false;
                    if (captureState == CaptureState.IDLE) {
                        audioStatus.setText("MUSIQUE PRÊTE · forme d'onde indisponible");
                    }
                    updateRecordState();
                });
            }
        });
    }

    private void updateRecordState() {
        if (activeRecording != null || captureState == CaptureState.FINALIZING
                || captureState == CaptureState.INTERRUPTED) return;
        if (!cameraReady) {
            recordButton.setEnabled(false);
            recordButton.setText("CAMÉRA…");
            return;
        }
        if (preparedMusicUri == null) {
            boolean loading = audioPreparing && musicUri != null;
            recordButton.setEnabled(!loading);
            recordButton.setText(loading ? "CHARGEMENT MUSIQUE…"
                    : "♪  IMPORTER UNE MUSIQUE");
        } else {
            recordButton.setEnabled(true);
            recordButton.setText("●  FILMER");
        }
    }

    private void startRecording() {
        if (!cameraReady || videoCapture == null) {
            Toast.makeText(this, "Caméra en préparation", Toast.LENGTH_SHORT).show();
            startCamera();
            return;
        }
        if (preparedMusicUri == null || !musicPrepared) {
            Toast.makeText(this,
                    "Importe une musique : le micro reste toujours coupé",
                    Toast.LENGTH_LONG).show();
            audioPicker.launch(new String[]{"audio/*"});
            return;
        }
        interruptedByLifecycle = false;
        stopRequested = false;
        captureState = CaptureState.IDLE;

        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) directory = getCacheDir();
        File temporary = new File(directory,
                "classic_video_" + System.currentTimeMillis() + ".mp4");

        PendingRecording pending = videoCapture.getOutput().prepareRecording(
                this, new FileOutputOptions.Builder(temporary).build());
        // Volontairement AUCUN withAudioEnabled() : le micro ne peut pas entrer dans la vidéo.

        setRecordingControls(false);
        try {
            activeRecording = pending.start(
                    ContextCompat.getMainExecutor(this),
                    event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            captureState = CaptureState.RECORDING;
                            stopRequested = false;
                            recordingStartedAt = SystemClock.elapsedRealtime();
                            recordingDurationMs = 0L;
                            timerView.setVisibility(View.VISIBLE);
                            recordButton.setEnabled(true);
                            seekMusicAndPlay();
                            uiHandler.removeCallbacks(timerTick);
                            uiHandler.post(timerTick);
                        } else if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalize =
                                    (VideoRecordEvent.Finalize) event;
                            activeRecording = null;
                            captureState = CaptureState.FINALIZING;
                            stopRequested = false;
                            recordingDurationMs = Math.max(1L,
                                    readVideoDuration(temporary));
                            pauseMusic();
                            uiHandler.removeCallbacks(timerTick);
                            timerView.setVisibility(View.GONE);
                            if (temporary.isFile() && temporary.length() > 0L) {
                                finalizeWithImportedAudio(
                                        temporary,
                                        recordingDurationMs,
                                        finalize.getError());
                            } else {
                                restoreIdleControls();
                                Toast.makeText(this,
                                        "La vidéo n'a pas pu être enregistrée",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
        } catch (Exception error) {
            activeRecording = null;
            captureState = CaptureState.IDLE;
            stopRequested = false;
            pauseMusic();
            restoreIdleControls();
            Toast.makeText(this,
                    "Impossible de démarrer l'enregistrement",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        if (activeRecording == null || stopRequested) return;
        stopRequested = true;
        captureState = interruptedByLifecycle
                ? CaptureState.INTERRUPTED : CaptureState.FINALIZING;
        recordButton.setEnabled(false);
        recordButton.setText(interruptedByLifecycle
                ? "INTERRUPTION · SAUVEGARDE…" : "FINALISATION…");
        pauseMusic();
        activeRecording.stop();
    }

    private void finalizeWithImportedAudio(File videoOnly, long durationMs, int cameraError) {
        recordButton.setEnabled(false);
        recordButton.setText("AJOUT AUDIO…");
        File ready = new File(getCacheDir(),
                "classic_ready_" + System.currentTimeMillis() + ".mp4");

        ioExecutor.execute(() -> {
            Exception firstError = null;
            boolean audioOk = false;
            try {
                MuxerUtils.addAudio(
                        this,
                        videoOnly,
                        preparedMusicUri,
                        ready,
                        Math.max(1L, durationMs) * 1000L,
                        Math.max(0L, audioStartMs) * 1000L);
                audioOk = MuxerUtils.hasAudioTrack(ready);
            } catch (Exception error) {
                firstError = error;
            }

            if (!audioOk && musicUri != null) {
                try {
                    if (ready.exists()) ready.delete();
                    MuxerUtils.addAudio(
                            this,
                            videoOnly,
                            musicUri,
                            ready,
                            Math.max(1L, durationMs) * 1000L,
                            Math.max(0L, audioStartMs) * 1000L);
                    audioOk = MuxerUtils.hasAudioTrack(ready);
                } catch (Exception ignored) {
                }
            }

            File sourceToSave = audioOk ? ready : videoOnly;
            try {
                Uri saved = MediaStoreSaver.saveVideo(
                        this,
                        sourceToSave,
                        "VideoClassique_" + System.currentTimeMillis() + ".mp4");
                if (videoOnly.exists()) videoOnly.delete();
                if (ready.exists()) ready.delete();
                boolean finalAudioOk = audioOk;
                Exception finalError = firstError;
                runOnUiThread(() -> {
                    captureState = CaptureState.IDLE;
                    restoreIdleControls();
                    if (finalAudioOk) {
                        Snackbar.make(recordButton,
                                "Vidéo enregistrée · audio importé intégré",
                                Snackbar.LENGTH_LONG)
                                .setAction("OUVRIR", v -> {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(saved, "video/mp4")
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                                    } catch (Exception ignored) {
                                    }
                                }).show();
                    } else {
                        Toast.makeText(this,
                                "Vidéo récupérée, mais l'audio n'a pas pu être intégré"
                                        + (finalError == null ? "" : " : " + finalError.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                    if (cameraError != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Toast.makeText(this,
                                "La caméra a signalé une anomalie mais la vidéo a été récupérée",
                                Toast.LENGTH_SHORT).show();
                    }
                    if (interruptedByLifecycle) {
                        audioStatus.setText(
                                "ENREGISTREMENT INTERROMPU · vidéo finalisée et sauvegardée");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    captureState = CaptureState.IDLE;
                    restoreIdleControls();
                    Toast.makeText(this,
                            "Vidéo créée mais copie galerie impossible : " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setRecordingControls(boolean enabled) {
        importAudioButton.setEnabled(enabled);
        musicPlayButton.setEnabled(enabled && musicPrepared);
        audioTimeline.setEnabled(enabled && musicPrepared);
        audioMinusButton.setEnabled(enabled && musicPrepared && preparedMusicUri != null);
        audioAutoButton.setEnabled(enabled && musicPrepared && preparedMusicUri != null);
        audioPlusButton.setEnabled(enabled && musicPrepared && preparedMusicUri != null);
        flipButton.setEnabled(enabled);
        qualityButton.setEnabled(enabled);
        recordButton.setEnabled(enabled);
    }

    private void restoreIdleControls() {
        stopRequested = false;
        if (captureState != CaptureState.INTERRUPTED) captureState = CaptureState.IDLE;
        importAudioButton.setEnabled(true);
        musicPlayButton.setEnabled(musicPrepared);
        audioTimeline.setEnabled(musicPrepared);
        boolean audioReady = musicPrepared && preparedMusicUri != null && !audioPreparing;
        audioMinusButton.setEnabled(musicPrepared && preparedMusicUri != null);
        audioAutoButton.setEnabled(audioReady);
        audioPlusButton.setEnabled(musicPrepared && preparedMusicUri != null);
        flipButton.setEnabled(true);
        qualityButton.setEnabled(true);
        if (musicPrepared) audioTimeline.setPositionMs(audioStartMs);
        updateRecordState();
    }

    private void adjustAudioStart(int deltaMs) {
        if (!musicPrepared || musicPlayer == null || activeRecording != null) return;
        int max = Math.max(0, musicPlayer.getDuration() - 1);
        audioStartMs = Math.max(0, Math.min(max, audioStartMs + deltaMs));
        audioStartEdited = true;
        audioTimeline.setPositionMs(audioStartMs);
        try { musicPlayer.seekTo(audioStartMs); } catch (Exception ignored) { }
        audioStatus.setText("DÉPART AJUSTÉ · " + formatPrecise(audioStartMs));
    }

    private void applyDetectedAudioStart() {
        if (!musicPrepared || musicPlayer == null || activeRecording != null) return;
        int max = Math.max(0, musicPlayer.getDuration() - 1);
        audioStartMs = Math.max(0, Math.min(max, detectedAudioStartMs));
        audioStartEdited = true;
        audioTimeline.setPositionMs(audioStartMs);
        try { musicPlayer.seekTo(audioStartMs); } catch (Exception ignored) { }
        audioStatus.setText("DÉBUT AUTO · " + formatPrecise(audioStartMs));
    }

    private void toggleMusic() {
        if (!musicPrepared || musicPlayer == null || activeRecording != null) return;
        try {
            if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
                uiHandler.removeCallbacks(musicProgressTick);
                audioTimeline.setPlaybackActive(false);
                musicPlayButton.setText("▶ ÉCOUTER");
            } else {
                musicPlayer.seekTo(Math.min(audioStartMs,
                        Math.max(0, musicPlayer.getDuration() - 1)));
                musicPlayer.start();
                startMusicProgress();
                musicPlayButton.setText("Ⅱ PAUSE");
            }
        } catch (Exception ignored) {
        }
    }

    private void seekMusicAndPlay() {
        if (!musicPrepared || musicPlayer == null) return;
        try {
            int target = Math.min(audioStartMs,
                    Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(target);
            musicPlayer.start();
            startMusicProgress();
            musicPlayButton.setText("Ⅱ PAUSE");
        } catch (Exception ignored) {
        }
    }

    private void pauseMusic() {
        uiHandler.removeCallbacks(musicProgressTick);
        if (audioTimeline != null) audioTimeline.setPlaybackActive(false);
        if (musicPlayer == null) return;
        try {
            if (musicPlayer.isPlaying()) musicPlayer.pause();
            musicPlayButton.setText("▶ ÉCOUTER");
        } catch (Exception ignored) {
        }
    }

    private void startMusicProgress() {
        uiHandler.removeCallbacks(musicProgressTick);
        audioTimeline.setPlaybackActive(true);
        uiHandler.postDelayed(musicProgressTick, 30L);
    }

    private void updateAudioPosition(int positionMs) {
        int duration = musicPrepared && musicPlayer != null
                ? musicPlayer.getDuration() : 0;
        audioStatus.setText(formatDuration(positionMs)
                + " / " + formatDuration(duration)
                + " · MICRO COUPÉ");
    }

    private long readVideoDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? Math.max(1L,
                    SystemClock.elapsedRealtime() - recordingStartedAt)
                    : Long.parseLong(value);
        } catch (Exception ignored) {
            return Math.max(1L,
                    SystemClock.elapsedRealtime() - recordingStartedAt);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "Musique sélectionnée";
    }

    private void persist(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void releaseMusic() {
        uiHandler.removeCallbacks(musicProgressTick);
        if (audioTimeline != null) audioTimeline.setPlaybackActive(false);
        musicPrepared = false;
        if (musicPlayer != null) {
            try {
                musicPlayer.release();
            } catch (Exception ignored) {
            }
            musicPlayer = null;
        }
    }

    private void deletePreparedAudio() {
        if (preparedAudioFile != null && preparedAudioFile.exists()) {
            preparedAudioFile.delete();
        }
        preparedAudioFile = null;
        preparedMusicUri = null;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String formatPrecise(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        long seconds = (safe / 1000L) % 60L;
        long tenths = (safe % 1000L) / 100L;
        return String.format(Locale.FRANCE, "%02d:%02d.%d",
                minutes, seconds, tenths);
    }

    @Override
    protected void onPause() {
        if (activeRecording != null && !stopRequested) {
            interruptedByLifecycle = true;
            captureState = CaptureState.INTERRUPTED;
            stopRecording();
        } else {
            pauseMusic();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (captureState == CaptureState.INTERRUPTED) {
            audioStatus.setText("INTERRUPTION DÉTECTÉE · finalisation sécurisée en cours");
        }
    }

    @Override
    protected void onDestroy() {
        audioLoadGeneration++;
        uiHandler.removeCallbacks(timerTick);
        pauseMusic();
        if (activeRecording != null) {
            try {
                activeRecording.stop();
            } catch (Exception ignored) {
            }
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        releaseMusic();
        deletePreparedAudio();
        ioExecutor.shutdown();
        audioAnalysisExecutor.shutdownNow();
        super.onDestroy();
    }
}
