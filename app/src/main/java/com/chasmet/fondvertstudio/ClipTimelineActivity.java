package com.chasmet.fondvertstudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
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
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini-monteur Clip Musique : plusieurs prises dans un seul enregistrement CameraX.
 * Une pause ferme un plan, permet de changer le décor, puis la reprise ouvre le plan suivant.
 * Le rendu reste dans le cache jusqu'à l'appui explicite sur TÉLÉCHARGER LE CLIP.
 */
public final class ClipTimelineActivity extends AppCompatActivity {
    private MaskedCameraView subjectPreview;
    private ImageView backgroundImage;
    private FillVideoView backgroundVideo;
    private View previewContainer;
    private TextView previewHint;
    private TextView recordingTimer;
    private TextView transformHint;
    private TextView audioName;
    private TextView musicPosition;
    private TextView clipStatus;
    private TextView decorStatus;
    private TextView timelineStatus;
    private LinearLayout timelineTrack;
    private ProgressBar processingProgress;
    private View processingOverlay;

    private MaterialButton recordButton;
    private MaterialButton finishButton;
    private MaterialButton downloadButton;
    private MaterialButton flipCameraButton;
    private MaterialButton qualityButton;
    private MaterialButton importAudioButton;
    private MaterialButton downloadsMusicButton;
    private MaterialButton musicPlayButton;
    private MaterialButton resetSubjectButton;
    private MaterialButton importImageButton;
    private MaterialButton importVideoButton;
    private MaterialButton greenBackgroundButton;
    private SeekBar musicSeekBar;

    private int quality = 1080;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private final float threshold = 0.50f;
    private final float softness = 0.065f;

    private final BackgroundSpec backgroundSpec = new BackgroundSpec();
    private String currentDecorLabel = "Fond vert";
    private final ClipBackgroundTimeline backgroundTimeline = new ClipBackgroundTimeline();
    private final ArrayList<ClipSegment> completedSegments = new ArrayList<>();
    private long currentSegmentStartMs = -1L;
    private String currentSegmentLabel = "Fond vert";

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File activeRawFile;
    private File renderedClipFile;

    private SegmentationEngine segmenter;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Bitmap currentSource;

    private Uri musicUri;
    private MediaPlayer musicPlayer;
    private boolean musicPrepared;
    private int clipAudioStartMs;

    private boolean clipPaused;
    private long recordingAccumulatedMs;
    private long activeSegmentStartedAt;
    private long lastTransformSampleAt;
    private boolean recordingTransformActive;
    private final SubjectTransformTimeline transformTimeline = new SubjectTransformTimeline();
    private static final long TRANSFORM_SAMPLE_INTERVAL_MS = 34L;

    private ActivityResultLauncher<String[]> audioPicker;
    private ActivityResultLauncher<Intent> downloadsAudioPicker;
    private ActivityResultLauncher<String[]> backgroundImagePicker;
    private ActivityResultLauncher<String[]> backgroundVideoPicker;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private static final class ClipSegment {
        final long startMs;
        final long endMs;
        final String label;

        ClipSegment(long startMs, long endMs, String label) {
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs, endMs);
            this.label = label == null ? "Décor" : label;
        }
    }

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (activeRecording == null) return;
            long duration = currentClipDurationMs();
            recordingTimer.setText(formatDuration(duration));
            if (!clipPaused) {
                recordButton.setText("Ⅱ  PAUSE   " + formatDuration(duration));
            }
            timelineStatus.setText((completedSegments.size() + (clipPaused ? 0 : 1))
                    + " plan(s) · " + formatDuration(duration));
            uiHandler.postDelayed(this, 150L);
        }
    };

    private final Runnable audioProgressTick = new Runnable() {
        @Override
        public void run() {
            if (musicPrepared && musicPlayer != null) {
                try {
                    int position = musicPlayer.getCurrentPosition();
                    if (!musicSeekBar.isPressed()) musicSeekBar.setProgress(position);
                    updateMusicPosition(position);
                    if (activeRecording == null && !musicPlayer.isPlaying()) {
                        musicPlayButton.setText("▶ ÉCOUTER");
                    }
                } catch (IllegalStateException ignored) {
                }
            }
            uiHandler.postDelayed(this, 120L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clip_timeline);
        bindViews();
        registerLaunchers();
        setupControls();

        backgroundSpec.setColor(Color.rgb(0, 255, 0));
        showGreenBackground();
        renderTimeline();

        segmenter = new SegmentationEngine(this);
        segmenter.setEdgeSettings(threshold, softness);
        requestCameraPermission();
        uiHandler.post(audioProgressTick);
    }

    private void bindViews() {
        subjectPreview = findViewById(R.id.subjectPreview);
        backgroundImage = findViewById(R.id.backgroundImage);
        backgroundVideo = findViewById(R.id.backgroundVideo);
        previewContainer = findViewById(R.id.previewContainer);
        previewHint = findViewById(R.id.previewHint);
        recordingTimer = findViewById(R.id.recordingTimer);
        transformHint = findViewById(R.id.transformHint);
        audioName = findViewById(R.id.audioName);
        musicPosition = findViewById(R.id.musicPosition);
        clipStatus = findViewById(R.id.clipStatus);
        decorStatus = findViewById(R.id.decorStatus);
        timelineStatus = findViewById(R.id.timelineStatus);
        timelineTrack = findViewById(R.id.timelineTrack);
        processingProgress = findViewById(R.id.processingProgress);
        processingOverlay = findViewById(R.id.processingOverlay);
        recordButton = findViewById(R.id.recordButton);
        finishButton = findViewById(R.id.finishButton);
        downloadButton = findViewById(R.id.downloadButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        qualityButton = findViewById(R.id.qualityButton);
        importAudioButton = findViewById(R.id.importAudioButton);
        downloadsMusicButton = findViewById(R.id.downloadsMusicButton);
        musicPlayButton = findViewById(R.id.musicPlayButton);
        resetSubjectButton = findViewById(R.id.resetSubjectButton);
        importImageButton = findViewById(R.id.importImageButton);
        importVideoButton = findViewById(R.id.importVideoButton);
        greenBackgroundButton = findViewById(R.id.greenBackgroundButton);
        musicSeekBar = findViewById(R.id.musicSeekBar);
    }

    private void registerLaunchers() {
        audioPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            persistReadPermission(uri);
            loadMusic(uri);
        });

        downloadsAudioPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    persistReadPermission(uri);
                    loadMusic(uri);
                });

        backgroundImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    persistReadPermission(uri);
                    backgroundSpec.setImage(uri);
                    currentDecorLabel = "Image · " + readDisplayName(uri);
                    showImageBackground(uri);
                    decorStatus.setText(currentDecorLabel);
                    registerBackgroundChangeIfPaused();
                });

        backgroundVideoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    persistReadPermission(uri);
                    backgroundSpec.setVideo(uri);
                    currentDecorLabel = "Vidéo · " + readDisplayName(uri);
                    showVideoBackground(uri);
                    decorStatus.setText(currentDecorLabel);
                    registerBackgroundChangeIfPaused();
                });

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startCamera();
                    else Toast.makeText(this, "La caméra est nécessaire pour tourner le clip",
                            Toast.LENGTH_LONG).show();
                });
    }

    private void setupControls() {
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        importImageButton.setOnClickListener(v -> {
            if (canChangeDecor()) backgroundImagePicker.launch(new String[]{"image/*"});
        });
        importVideoButton.setOnClickListener(v -> {
            if (canChangeDecor()) backgroundVideoPicker.launch(new String[]{"video/*"});
        });
        greenBackgroundButton.setOnClickListener(v -> {
            if (!canChangeDecor()) return;
            backgroundSpec.setColor(Color.rgb(0, 255, 0));
            currentDecorLabel = "Fond vert";
            showGreenBackground();
            decorStatus.setText(currentDecorLabel);
            registerBackgroundChangeIfPaused();
        });

        importAudioButton.setOnClickListener(v -> {
            if (activeRecording == null && renderedClipFile == null) {
                audioPicker.launch(new String[]{"audio/*"});
            }
        });
        downloadsMusicButton.setOnClickListener(v -> {
            if (activeRecording == null && renderedClipFile == null) openDownloadsForMusic();
        });
        musicPlayButton.setOnClickListener(v -> toggleMusicPreview());

        qualityButton.setOnClickListener(v -> {
            if (activeRecording != null || renderedClipFile != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });

        flipCameraButton.setOnClickListener(v -> {
            if (activeRecording != null || renderedClipFile != null) return;
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });

        resetSubjectButton.setOnClickListener(v -> subjectPreview.resetSubjectTransform());
        subjectPreview.setTransformListener((scale, centerX, centerY, gestureFinished) -> {
            int percent = Math.round(scale * 100f);
            resetSubjectButton.setText("↺ " + percent + " %");
            transformHint.setText(gestureFinished
                    ? "Glisse · pince pour zoomer" : "SUJET · " + percent + " %");
            recordSubjectTransform(gestureFinished);
        });

        musicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMusicPosition(progress);
                if (fromUser && musicPrepared && musicPlayer != null && activeRecording == null
                        && renderedClipFile == null) {
                    try { musicPlayer.seekTo(progress); } catch (IllegalStateException ignored) { }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (musicPrepared && musicPlayer != null && activeRecording == null
                        && renderedClipFile == null) {
                    try {
                        musicPlayer.start();
                        musicPlayButton.setText("Ⅱ PAUSE SON");
                    } catch (IllegalStateException ignored) { }
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (musicPrepared) {
                    clipStatus.setText("Départ choisi · " + formatDuration(seekBar.getProgress()));
                }
            }
        });

        recordButton.setOnClickListener(v -> {
            if (activeRecording == null) startClipRecording();
            else if (clipPaused) resumeClipRecording();
            else pauseClipRecording();
        });
        finishButton.setOnClickListener(v -> finishClipRecording());
        downloadButton.setOnClickListener(v -> saveRenderedClip());
    }

    private boolean canChangeDecor() {
        return renderedClipFile == null && (activeRecording == null || clipPaused);
    }

    private void openDownloadsForMusic() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.providers.downloads.documents/root/downloads"));
        }
        downloadsAudioPicker.launch(intent);
    }

    private void registerBackgroundChangeIfPaused() {
        if (activeRecording != null && clipPaused) {
            long cutUs = Math.max(0L, recordingAccumulatedMs) * 1000L;
            backgroundTimeline.addOrReplace(cutUs, backgroundSpec, currentDecorLabel);
            clipStatus.setText("Nouveau décor prêt · appuie sur REPRENDRE");
            renderTimeline();
        }
    }

    private void showGreenBackground() {
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setImageDrawable(null);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.rgb(0, 255, 0));
    }

    private void showImageBackground(Uri uri) {
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        backgroundImage.setImageURI(null);
        backgroundImage.setImageURI(uri);
        backgroundImage.setVisibility(View.VISIBLE);
    }

    private void showVideoBackground(Uri uri) {
        backgroundImage.setImageDrawable(null);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        backgroundVideo.setVisibility(View.VISIBLE);
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVideoURI(uri);
        backgroundVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            player.setVolume(0f, 0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
            if (activeRecording == null) {
                backgroundVideo.start();
            } else if (clipPaused) {
                backgroundVideo.seekTo(1);
            } else {
                backgroundVideo.start();
            }
        });
    }

    private void loadMusic(Uri uri) {
        releaseMusicPlayer();
        musicUri = uri;
        musicPrepared = false;
        audioName.setText(readDisplayName(uri));
        importAudioButton.setText("✓ MUSIQUE IMPORTÉE");
        clipStatus.setText("Chargement du son…");
        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnPreparedListener(mp -> {
                musicPrepared = true;
                int duration = Math.max(1, mp.getDuration());
                musicSeekBar.setMax(duration);
                musicSeekBar.setProgress(0);
                updateMusicPosition(0);
                clipStatus.setText("Glisse la barre pour choisir le départ");
                musicPlayButton.setEnabled(true);
            });
            player.setOnCompletionListener(mp -> {
                if (activeRecording != null && !clipPaused) finishClipRecording();
                else musicPlayButton.setText("▶ ÉCOUTER");
            });
            player.prepareAsync();
            musicPlayer = player;
        } catch (Exception error) {
            musicUri = null;
            importAudioButton.setText("2 · IMPORTER MUSIQUE");
            audioName.setText("Audio illisible");
            clipStatus.setText("Choisis un autre fichier audio");
            Toast.makeText(this, "Impossible de lire cette musique", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleMusicPreview() {
        if (!musicPrepared || musicPlayer == null || activeRecording != null
                || renderedClipFile != null) return;
        try {
            if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
                musicPlayButton.setText("▶ ÉCOUTER");
            } else {
                if (musicPlayer.getCurrentPosition() >= musicPlayer.getDuration() - 150) {
                    musicPlayer.seekTo(0);
                }
                musicPlayer.start();
                musicPlayButton.setText("Ⅱ PAUSE SON");
            }
        } catch (IllegalStateException ignored) { }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void startCamera() {
        if (segmenter == null || activeRecording != null
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception error) {
                showError("Caméra indisponible : " + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || activeRecording != null) return;
        cameraProvider.unbindAll();
        segmenter.resetStreamHistory();
        Quality preferred = quality == 1080 ? Quality.FHD : Quality.HD;
        QualitySelector selector = QualitySelector.from(preferred,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
        Recorder recorder = new Recorder.Builder().setQualitySelector(selector).build();
        videoCapture = VideoCapture.withOutput(recorder);
        CameraSelector selectorCamera = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        try {
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(480, 854))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build();
            analysis.setAnalyzer(cameraExecutor, this::analyzeCameraFrame);
            cameraProvider.bindToLifecycle(this, selectorCamera, analysis, videoCapture);
        } catch (Exception error) {
            showError("Cette caméra ne permet pas le mode sélectionné");
        }
    }

    private void analyzeCameraFrame(@NonNull ImageProxy imageProxy) {
        if (segmenter == null) {
            imageProxy.close();
            return;
        }
        Bitmap bitmap;
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        try {
            bitmap = BitmapUtils.fromRgbaImageProxy(imageProxy);
        } catch (Exception ignored) {
            return;
        } finally {
            imageProxy.close();
        }
        bitmap = BitmapUtils.rotateAndMirror(bitmap, rotation,
                lensFacing == CameraSelector.LENS_FACING_FRONT);
        Bitmap inference = null;
        if (!segmenter.isStreamBusy()) {
            inference = BitmapUtils.scaleDown(bitmap, 512);
            if (inference == bitmap) inference = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap displayFrame = bitmap;
        uiHandler.post(() -> acceptSourceFrame(displayFrame));
        if (inference == null) return;
        segmenter.processStream(inference, new SegmentationEngine.Callback() {
            @Override
            public void onResult(SegmentationEngine.Result result) {
                acceptMaskResult(result);
            }

            @Override
            public void onError(Exception error) { }
        });
    }

    private void startClipRecording() {
        if (!musicPrepared || musicPlayer == null || musicUri == null) {
            Toast.makeText(this, "Importe d’abord ta musique", Toast.LENGTH_SHORT).show();
            return;
        }
        if (videoCapture == null) {
            Toast.makeText(this, "Caméra en préparation…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (renderedClipFile != null) {
            deleteFile(renderedClipFile);
            renderedClipFile = null;
        }
        downloadButton.setVisibility(View.GONE);
        recordButton.setVisibility(View.VISIBLE);

        try {
            musicPlayer.pause();
            clipAudioStartMs = Math.min(musicSeekBar.getProgress(),
                    Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(clipAudioStartMs);
        } catch (IllegalStateException error) {
            showError("La musique n’est pas prête");
            return;
        }

        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) directory = getCacheDir();
        activeRawFile = new File(directory,
                "clip_timeline_source_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(activeRawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        recordButton.setEnabled(false);
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    beginClipSession();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                    File transformFile = finishSubjectTransformTimeline();
                    File backgroundFile = finishBackgroundTimeline();
                    activeRecording = null;
                    clipPaused = false;
                    uiHandler.removeCallbacks(timerTick);
                    pauseMusicAndBackground();
                    if (finalized.getError() == VideoRecordEvent.Finalize.ERROR_NONE
                            && activeRawFile != null && activeRawFile.exists()) {
                        enqueueClipRender(Uri.fromFile(activeRawFile), transformFile, backgroundFile);
                    } else {
                        deleteFile(transformFile);
                        deleteFile(backgroundFile);
                        showError("Enregistrement interrompu");
                        showIdleUi();
                    }
                }
            });
        } catch (Exception error) {
            activeRecording = null;
            showIdleUi();
            showError("Impossible de démarrer le tournage");
        }
    }

    private void beginClipSession() {
        recordingAccumulatedMs = 0L;
        activeSegmentStartedAt = SystemClock.elapsedRealtime();
        clipPaused = false;
        completedSegments.clear();
        currentSegmentStartMs = 0L;
        currentSegmentLabel = currentDecorLabel;
        backgroundTimeline.clear();
        backgroundTimeline.addOrReplace(0L, backgroundSpec, currentDecorLabel);
        beginSubjectTransformTimeline();
        renderTimeline();

        recordingTimer.setText("00:00");
        recordingTimer.setVisibility(View.VISIBLE);
        recordButton.setText("Ⅱ  PAUSE   00:00");
        recordButton.setEnabled(true);
        finishButton.setVisibility(View.VISIBLE);
        finishButton.setEnabled(true);
        downloadButton.setVisibility(View.GONE);
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
        clipStatus.setText("PLAN 1 · tourne puis appuie sur PAUSE");
        try {
            musicPlayer.seekTo(clipAudioStartMs);
            musicPlayer.start();
        } catch (IllegalStateException ignored) { }
        restartVideoBackgroundForPlan();
        uiHandler.removeCallbacks(timerTick);
        uiHandler.post(timerTick);
    }

    private void pauseClipRecording() {
        if (activeRecording == null || clipPaused) return;
        recordingAccumulatedMs = currentClipDurationMs();
        closeCurrentSegment(recordingAccumulatedMs);
        activeSegmentStartedAt = 0L;
        clipPaused = true;
        recordSubjectTransform(true);
        try { activeRecording.pause(); } catch (Exception ignored) { }
        pauseMusicAndBackground();
        setDecorControlsEnabled(true);
        recordButton.setText("▶  REPRENDRE   " + formatDuration(recordingAccumulatedMs));
        clipStatus.setText("PAUSE · change IMAGE/VIDÉO puis REPRENDRE");
        renderTimeline();
    }

    private void resumeClipRecording() {
        if (activeRecording == null || !clipPaused) return;
        try { activeRecording.resume(); } catch (Exception ignored) { }
        activeSegmentStartedAt = SystemClock.elapsedRealtime();
        currentSegmentStartMs = recordingAccumulatedMs;
        currentSegmentLabel = currentDecorLabel;
        clipPaused = false;
        setDecorControlsEnabled(false);
        if (musicPrepared && musicPlayer != null) {
            try { musicPlayer.start(); } catch (IllegalStateException ignored) { }
        }
        restartVideoBackgroundForPlan();
        recordButton.setText("Ⅱ  PAUSE   " + formatDuration(recordingAccumulatedMs));
        clipStatus.setText("PLAN " + (completedSegments.size() + 1)
                + " · reprise au même point musical");
        renderTimeline();
    }

    private void finishClipRecording() {
        if (activeRecording == null) return;
        if (!clipPaused) {
            recordingAccumulatedMs = currentClipDurationMs();
            closeCurrentSegment(recordingAccumulatedMs);
        }
        activeSegmentStartedAt = 0L;
        clipPaused = true;
        recordSubjectTransform(true);
        pauseMusicAndBackground();
        setDecorControlsEnabled(false);
        recordButton.setEnabled(false);
        finishButton.setEnabled(false);
        recordButton.setText("Préparation du montage…");
        renderTimeline();
        activeRecording.stop();
    }

    private void closeCurrentSegment(long endMs) {
        if (currentSegmentStartMs < 0L) return;
        if (endMs > currentSegmentStartMs + 50L) {
            completedSegments.add(new ClipSegment(currentSegmentStartMs, endMs,
                    currentSegmentLabel));
        }
        currentSegmentStartMs = -1L;
    }

    private void restartVideoBackgroundForPlan() {
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try {
                backgroundVideo.seekTo(0);
                backgroundVideo.start();
            } catch (Exception ignored) { }
        }
    }

    private void pauseMusicAndBackground() {
        if (musicPrepared && musicPlayer != null) {
            try { musicPlayer.pause(); } catch (IllegalStateException ignored) { }
        }
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO) {
            try {
                if (backgroundVideo.isPlaying()) backgroundVideo.pause();
            } catch (Exception ignored) { }
        }
    }

    private long currentClipDurationMs() {
        if (activeRecording == null) return recordingAccumulatedMs;
        if (clipPaused || activeSegmentStartedAt <= 0L) return recordingAccumulatedMs;
        return recordingAccumulatedMs
                + Math.max(0L, SystemClock.elapsedRealtime() - activeSegmentStartedAt);
    }

    private File finishBackgroundTimeline() {
        File directory = new File(getCacheDir(), "clip_background_timelines");
        File file = new File(directory,
                "backgrounds_" + System.currentTimeMillis() + ".json");
        try {
            backgroundTimeline.write(file);
            return file;
        } catch (Exception error) {
            deleteFile(file);
            return null;
        }
    }

    private void enqueueClipRender(Uri sourceUri, File transformFile, File backgroundFile) {
        if (backgroundFile == null) {
            showError("Timeline des décors introuvable");
            showIdleUi();
            return;
        }
        Data.Builder input = new Data.Builder()
                .putString(ClipTimelineExportWorker.KEY_SOURCE_URI, sourceUri.toString())
                .putString(ClipTimelineExportWorker.KEY_EXTERNAL_AUDIO_URI, musicUri.toString())
                .putLong(ClipTimelineExportWorker.KEY_EXTERNAL_AUDIO_START_MS, clipAudioStartMs)
                .putString(ClipTimelineExportWorker.KEY_BACKGROUND_TIMELINE_PATH,
                        backgroundFile.getAbsolutePath())
                .putFloat(ClipTimelineExportWorker.KEY_THRESHOLD, threshold)
                .putFloat(ClipTimelineExportWorker.KEY_SOFTNESS, softness)
                .putInt(ClipTimelineExportWorker.KEY_QUALITY, quality)
                .putBoolean(ClipTimelineExportWorker.KEY_MIRROR_SOURCE,
                        lensFacing == CameraSelector.LENS_FACING_FRONT)
                .putFloat(ClipTimelineExportWorker.KEY_TRANSFORM_SCALE,
                        subjectPreview.getSubjectScale())
                .putFloat(ClipTimelineExportWorker.KEY_TRANSFORM_CENTER_X,
                        subjectPreview.getSubjectCenterX())
                .putFloat(ClipTimelineExportWorker.KEY_TRANSFORM_CENTER_Y,
                        subjectPreview.getSubjectCenterY());
        if (transformFile != null) {
            input.putString(ClipTimelineExportWorker.KEY_TRANSFORM_PATH,
                    transformFile.getAbsolutePath());
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                ClipTimelineExportWorker.class).setInputData(input.build()).build();
        WorkManager.getInstance(this).enqueue(request);
        observeRender(request);
    }

    private void observeRender(OneTimeWorkRequest request) {
        showBusy(0, "Montage des plans…");
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.getId())
                .observe(this, info -> {
                    if (info == null) return;
                    int progress = info.getProgress().getInt(
                            ClipTimelineExportWorker.KEY_PROGRESS, 0);
                    processingProgress.setProgress(progress);
                    ((TextView) findViewById(R.id.processingText)).setText(
                            "Création du montage… " + progress + "%");
                    if (info.getState() == WorkInfo.State.SUCCEEDED) {
                        String output = info.getOutputData().getString(
                                ClipTimelineExportWorker.KEY_OUTPUT_FILE);
                        deleteRawRecording();
                        if (output == null) {
                            showError("Montage final introuvable");
                            showIdleUi();
                            return;
                        }
                        renderedClipFile = new File(output);
                        showReadyToDownload();
                    } else if (info.getState() == WorkInfo.State.FAILED
                            || info.getState() == WorkInfo.State.CANCELLED) {
                        String error = info.getOutputData().getString(
                                ClipTimelineExportWorker.KEY_ERROR);
                        deleteRawRecording();
                        showError(error == null ? "Échec du montage" : error);
                        showIdleUi();
                    }
                });
    }

    private void showReadyToDownload() {
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText("↓  TÉLÉCHARGER LE CLIP");
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
        clipStatus.setText("MONTAGE PRÊT · rien n’est encore enregistré");
        timelineStatus.setText(completedSegments.size() + " plan(s) · montage prêt");
        renderTimeline();
    }

    private void saveRenderedClip() {
        if (renderedClipFile == null || !renderedClipFile.isFile()) {
            showError("Le montage n’est plus disponible");
            return;
        }
        downloadButton.setEnabled(false);
        downloadButton.setText("Téléchargement…");
        File source = renderedClipFile;
        ioExecutor.execute(() -> {
            try {
                Uri uri = MediaStoreSaver.saveVideo(this, source,
                        "ClipMusique_Timeline_" + System.currentTimeMillis() + ".mp4");
                deleteFile(source);
                runOnUiThread(() -> {
                    renderedClipFile = null;
                    downloadButton.setText("✓  CLIP TÉLÉCHARGÉ");
                    clipStatus.setText("Enregistré dans la galerie · montage terminé");
                    showSavedSnackbar(uri);
                    recordButton.setVisibility(View.VISIBLE);
                    recordButton.setEnabled(true);
                    recordButton.setText("●  NOUVEAU TOURNAGE");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadButton.setEnabled(true);
                    downloadButton.setText("↓  TÉLÉCHARGER LE CLIP");
                    showError("Téléchargement impossible : " + error.getMessage());
                });
            }
        });
    }

    private void renderTimeline() {
        if (timelineTrack == null) return;
        timelineTrack.removeAllViews();
        int index = 1;
        for (ClipSegment segment : completedSegments) {
            addTimelineChip("PLAN " + index,
                    formatDuration(segment.startMs) + " → " + formatDuration(segment.endMs),
                    shortLabel(segment.label), false);
            index++;
        }
        if (activeRecording != null && clipPaused) {
            addTimelineChip("+ PROCHAIN",
                    "à " + formatDuration(recordingAccumulatedMs),
                    shortLabel(currentDecorLabel), true);
        } else if (activeRecording != null && !clipPaused && currentSegmentStartMs >= 0L) {
            addTimelineChip("PLAN " + index, "EN COURS",
                    shortLabel(currentSegmentLabel), true);
        }
        if (completedSegments.isEmpty() && activeRecording == null) {
            addTimelineChip("TIMELINE", "Les plans apparaîtront ici",
                    "PAUSE = nouveau plan", true);
            timelineStatus.setText("0 plan · prêt à tourner");
        }
    }

    private void addTimelineChip(String title, String time, String label, boolean active) {
        TextView chip = new TextView(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setText(title + "\n" + time + "\n" + label);
        chip.setTextColor(ContextCompat.getColor(this, R.color.ink));
        chip.setTextSize(9f);
        chip.setMaxLines(3);
        chip.setBackgroundResource(active ? R.drawable.bg_record_badge : R.drawable.bg_badge);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(116), dp(58));
        params.setMarginEnd(dp(6));
        timelineTrack.addView(chip, params);
    }

    private static String shortLabel(String label) {
        if (label == null) return "Décor";
        return label.length() <= 22 ? label : label.substring(0, 20) + "…";
    }

    private void beginSubjectTransformTimeline() {
        transformTimeline.clear();
        recordingTransformActive = true;
        lastTransformSampleAt = Long.MIN_VALUE;
        transformTimeline.add(0L, subjectPreview.getSubjectScale(),
                subjectPreview.getSubjectCenterX(), subjectPreview.getSubjectCenterY());
    }

    private void recordSubjectTransform(boolean force) {
        if (!recordingTransformActive) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastTransformSampleAt < TRANSFORM_SAMPLE_INTERVAL_MS) return;
        long elapsedUs = Math.max(0L, currentClipDurationMs()) * 1000L;
        transformTimeline.add(elapsedUs, subjectPreview.getSubjectScale(),
                subjectPreview.getSubjectCenterX(), subjectPreview.getSubjectCenterY());
        lastTransformSampleAt = now;
    }

    private File finishSubjectTransformTimeline() {
        if (!recordingTransformActive) return null;
        recordSubjectTransform(true);
        recordingTransformActive = false;
        File directory = new File(getCacheDir(), "subject_transforms");
        File file = new File(directory,
                "clip_timeline_movement_" + System.currentTimeMillis() + ".csv");
        try {
            transformTimeline.write(file);
            return file;
        } catch (Exception error) {
            deleteFile(file);
            return null;
        }
    }

    private void acceptSourceFrame(Bitmap source) {
        Bitmap oldSource = currentSource;
        currentSource = source;
        subjectPreview.setSource(source);
        previewHint.setVisibility(View.GONE);
        if (oldSource != null && oldSource != source && !oldSource.isRecycled()) oldSource.recycle();
    }

    private void acceptMaskResult(SegmentationEngine.Result result) {
        subjectPreview.setMask(result.alphaMask, result.mask, result.maskWidth,
                result.maskHeight, threshold, softness);
        if (result.source != null && !result.source.isRecycled()) result.source.recycle();
        if (result.cutout != null && !result.cutout.isRecycled()) result.cutout.recycle();
    }

    private void setDecorControlsEnabled(boolean enabled) {
        importImageButton.setEnabled(enabled);
        importVideoButton.setEnabled(enabled);
        greenBackgroundButton.setEnabled(enabled);
    }

    private void setMusicControlsEnabled(boolean enabled) {
        importAudioButton.setEnabled(enabled);
        downloadsMusicButton.setEnabled(enabled);
        musicPlayButton.setEnabled(enabled && musicPrepared);
        musicSeekBar.setEnabled(enabled && musicPrepared);
    }

    private void showIdleUi() {
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setVisibility(View.VISIBLE);
        recordButton.setText("●  TOURNER");
        recordButton.setEnabled(true);
        finishButton.setVisibility(View.GONE);
        finishButton.setEnabled(true);
        downloadButton.setVisibility(View.GONE);
        setDecorControlsEnabled(true);
        setMusicControlsEnabled(true);
        flipCameraButton.setEnabled(true);
        qualityButton.setEnabled(true);
        clipStatus.setText("Clip prêt · PAUSE crée un nouveau plan");
    }

    private void showBusy(int progress, String text) {
        processingProgress.setProgress(progress);
        ((TextView) findViewById(R.id.processingText)).setText(text);
        processingOverlay.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
        finishButton.setEnabled(false);
        downloadButton.setEnabled(false);
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
    }

    private void showError(String message) {
        processingOverlay.setVisibility(View.GONE);
        Toast.makeText(this, message == null ? "Une erreur est survenue" : message,
                Toast.LENGTH_LONG).show();
    }

    private void showSavedSnackbar(Uri uri) {
        Snackbar.make(downloadButton, "Clip enregistré dans la galerie", Snackbar.LENGTH_LONG)
                .setAction("Ouvrir", v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(intent); }
                    catch (Exception error) {
                        Toast.makeText(this, "Clip : " + uri, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void updateMusicPosition(int positionMs) {
        int duration = musicPrepared && musicPlayer != null ? musicPlayer.getDuration() : 0;
        musicPosition.setText(formatDuration(positionMs) + " / " + formatDuration(duration));
    }

    private String readDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "Fichier sélectionné";
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    private void releaseMusicPlayer() {
        musicPrepared = false;
        if (musicPlayer != null) {
            try { musicPlayer.release(); } catch (Exception ignored) { }
            musicPlayer = null;
        }
    }

    private void deleteRawRecording() {
        if (activeRawFile != null && activeRawFile.exists()) activeRawFile.delete();
        activeRawFile = null;
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Une prise mise en pause reste en pause. L'utilisateur choisit explicitement REPRENDRE.
        if (activeRecording == null && renderedClipFile == null
                && backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try { backgroundVideo.start(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onPause() {
        // Ne finalise plus automatiquement : le sélecteur de fichier doit pouvoir s'ouvrir
        // pendant une pause sans perdre les plans déjà tournés.
        if (activeRecording != null && !clipPaused) pauseClipRecording();
        pauseMusicAndBackground();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(timerTick);
        uiHandler.removeCallbacks(audioProgressTick);
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (segmenter != null) segmenter.close();
        subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) currentSource.recycle();
        currentSource = null;
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundImage.setImageDrawable(null);
        releaseMusicPlayer();
        if (renderedClipFile != null && renderedClipFile.exists()) renderedClipFile.delete();
        super.onDestroy();
    }
}
