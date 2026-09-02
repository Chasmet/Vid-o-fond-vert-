package com.chasmet.fondvertstudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clip Musique v2 : le montage détouré est encodé pendant le tournage.
 * TERMINER ne recalcule plus la vidéo image par image : il ferme l'encodeur puis muxe l'audio.
 * Les prises CameraX brutes restent conservées jusqu'à la fin comme filet de sécurité.
 */
public final class ClipTimelineFastActivity extends AppCompatActivity {
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
    private final float threshold = 0.52f;
    private final float softness = 0.040f;

    private final BackgroundSpec backgroundSpec = new BackgroundSpec();
    private String currentDecorLabel = "Fond vert";
    private final ClipSourceTimeline sourceTimeline = new ClipSourceTimeline();
    private final ArrayList<ClipSegmentUi> completedSegments = new ArrayList<>();

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File currentSegmentFile;
    private File renderedClipFile;
    private RealtimeClipRecorder realtimeRecorder;

    private SegmentationEngine segmenter;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Bitmap currentSource;

    private final Object maskLock = new Object();
    private float[] latestMask;
    private int latestMaskWidth;
    private int latestMaskHeight;

    private Uri musicUri;
    private Uri preparedMusicUri;
    private File preparedAudioFile;
    private MediaPlayer musicPlayer;
    private boolean musicPrepared;
    private volatile boolean audioPreparing;
    private int clipAudioStartMs;

    private boolean sessionActive;
    private boolean clipPaused;
    private boolean finishRequested;
    private boolean segmentStopRequested;
    private volatile long recordingAccumulatedMs;
    private volatile long activeSegmentStartedAt;
    private String currentSegmentLabel = "Fond vert";
    private int planNumber;

    private volatile boolean realtimeCaptureActive;
    private volatile long realtimePlanBaseUs;
    private volatile long realtimePlanStartedAt;
    private volatile float liveSubjectScale = SubjectTransformTimeline.DEFAULT_SCALE;
    private volatile float liveCenterX = SubjectTransformTimeline.DEFAULT_CENTER_X;
    private volatile float liveCenterY = SubjectTransformTimeline.DEFAULT_CENTER_Y;

    private ActivityResultLauncher<String[]> audioPicker;
    private ActivityResultLauncher<Intent> downloadsAudioPicker;
    private ActivityResultLauncher<String[]> backgroundImagePicker;
    private ActivityResultLauncher<String[]> backgroundVideoPicker;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private static final class ClipSegmentUi {
        final long startMs;
        final long endMs;
        final String label;

        ClipSegmentUi(long startMs, long endMs, String label) {
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs, endMs);
            this.label = label == null ? "Décor" : label;
        }
    }

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) return;
            long duration = currentClipDurationMs();
            recordingTimer.setText(formatDuration(duration));
            if (activeRecording != null && !segmentStopRequested) {
                recordButton.setText("Ⅱ  PAUSE   " + formatDuration(duration));
            }
            timelineStatus.setText((completedSegments.size()
                    + (activeRecording == null ? 0 : 1)) + " plan(s) · "
                    + formatDuration(duration));
            uiHandler.postDelayed(this, 150L);
        }
    };

    private final Runnable audioProgressTick = new Runnable() {
        @Override
        public void run() {
            if (musicPrepared && musicPlayer != null) {
                try {
                    int position = musicPlayer.getCurrentPosition();
                    if (!musicSeekBar.isPressed() && !sessionActive) {
                        musicSeekBar.setProgress(position);
                    }
                    updateMusicPosition(position);
                    if (!sessionActive && renderedClipFile == null
                            && !musicPlayer.isPlaying()) {
                        musicPlayButton.setText("▶ ÉCOUTER");
                    }
                } catch (IllegalStateException ignored) { }
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
                    if (sessionActive && clipPaused) {
                        clipStatus.setText("Décor prêt · REPRENDRE");
                        renderTimeline();
                    }
                });
        backgroundVideoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    persistReadPermission(uri);
                    backgroundSpec.setVideo(uri);
                    currentDecorLabel = "Vidéo · " + readDisplayName(uri);
                    showVideoBackground(uri);
                    decorStatus.setText(currentDecorLabel);
                    if (sessionActive && clipPaused) {
                        clipStatus.setText("Décor vidéo prêt · REPRENDRE");
                        renderTimeline();
                    }
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
            if (sessionActive && clipPaused) renderTimeline();
        });

        importAudioButton.setOnClickListener(v -> {
            if (!sessionActive && renderedClipFile == null) {
                audioPicker.launch(new String[]{"audio/*"});
            }
        });
        downloadsMusicButton.setOnClickListener(v -> {
            if (!sessionActive && renderedClipFile == null) openDownloadsForMusic();
        });
        musicPlayButton.setOnClickListener(v -> toggleMusicPreview());

        qualityButton.setOnClickListener(v -> {
            if (sessionActive || renderedClipFile != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });
        flipCameraButton.setOnClickListener(v -> {
            if (sessionActive || renderedClipFile != null) return;
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        resetSubjectButton.setOnClickListener(v -> subjectPreview.resetSubjectTransform());
        subjectPreview.setTransformListener((scale, centerX, centerY, gestureFinished) -> {
            liveSubjectScale = scale;
            liveCenterX = centerX;
            liveCenterY = centerY;
            int percent = Math.round(scale * 100f);
            resetSubjectButton.setText("↺ " + percent + " %");
            transformHint.setText(gestureFinished
                    ? "Glisse · pince pour zoomer" : "SUJET · " + percent + " %");
        });

        musicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMusicPosition(progress);
                if (fromUser && musicPrepared && musicPlayer != null && !sessionActive
                        && renderedClipFile == null) {
                    try { musicPlayer.seekTo(progress); } catch (IllegalStateException ignored) { }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (musicPrepared && musicPlayer != null && !sessionActive
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
            if (!sessionActive) startClipSession();
            else if (activeRecording != null) pauseCurrentPlan();
            else if (clipPaused) resumeNextPlan();
        });
        finishButton.setOnClickListener(v -> finishClipSession());
        downloadButton.setOnClickListener(v -> saveRenderedClip());
    }

    private boolean canChangeDecor() {
        return renderedClipFile == null && (!sessionActive || (clipPaused && activeRecording == null));
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
        synchronized (maskLock) {
            latestMask = null;
            latestMaskWidth = 0;
            latestMaskHeight = 0;
        }

        Quality preferred = quality == 1080 ? Quality.FHD : Quality.HD;
        QualitySelector qualitySelector = QualitySelector.from(preferred,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
        Recorder recorder = new Recorder.Builder().setQualitySelector(qualitySelector).build();
        videoCapture = VideoCapture.withOutput(recorder);
        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        try {
            Size analysisSize = quality == 1080 ? new Size(720, 1280) : new Size(540, 960);
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(analysisSize)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build();
            analysis.setAnalyzer(cameraExecutor, this::analyzeCameraFrame);
            cameraProvider.bindToLifecycle(this, selector, analysis, videoCapture);
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
        } catch (Exception error) {
            imageProxy.close();
            return;
        } finally {
            if (!imageProxy.isClosed()) imageProxy.close();
        }
        bitmap = BitmapUtils.rotateAndMirror(bitmap, rotation,
                lensFacing == CameraSelector.LENS_FACING_FRONT);

        Bitmap inference = null;
        if (!segmenter.isStreamBusy()) {
            inference = BitmapUtils.scaleDown(bitmap, 640);
            if (inference == bitmap) {
                inference = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        }

        Bitmap displayFrame;
        Bitmap recordingFrame = null;
        if (realtimeCaptureActive && realtimeRecorder != null) {
            displayFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            recordingFrame = bitmap;
        } else {
            displayFrame = bitmap;
        }
        uiHandler.post(() -> acceptSourceFrame(displayFrame));

        if (recordingFrame != null) {
            float[] mask;
            int maskWidth;
            int maskHeight;
            synchronized (maskLock) {
                mask = latestMask;
                maskWidth = latestMaskWidth;
                maskHeight = latestMaskHeight;
            }
            long startedAt = realtimePlanStartedAt;
            long localUs = startedAt <= 0L ? 0L
                    : Math.max(0L, SystemClock.elapsedRealtime() - startedAt) * 1000L;
            long outputUs = realtimePlanBaseUs + localUs;
            realtimeRecorder.offerFrame(recordingFrame, mask, maskWidth, maskHeight,
                    outputUs, localUs, liveSubjectScale, liveCenterX, liveCenterY);
        }

        if (inference != null) {
            segmenter.processStream(inference, new SegmentationEngine.Callback() {
                @Override
                public void onResult(SegmentationEngine.Result result) {
                    acceptMaskResult(result);
                }

                @Override
                public void onError(Exception error) { }
            });
        }
    }

    private void loadMusic(Uri uri) {
        releaseMusicPlayer();
        deletePreparedAudio();
        musicUri = uri;
        preparedMusicUri = null;
        musicPrepared = false;
        audioPreparing = false;
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
                musicPlayButton.setEnabled(true);
                prepareMusicForInstantFinalization(uri, duration);
            });
            player.setOnCompletionListener(mp -> {
                if (sessionActive) finishClipSession();
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

    private void prepareMusicForInstantFinalization(Uri uri, int durationMs) {
        audioPreparing = true;
        recordButton.setEnabled(false);
        clipStatus.setText("Préparation audio rapide…");
        File directory = new File(getCacheDir(), "prepared_audio");
        if (!directory.exists()) directory.mkdirs();
        File destination = new File(directory,
                "prepared_" + System.currentTimeMillis() + ".m4a");
        ioExecutor.execute(() -> {
            try {
                Uri prepared = MuxerUtils.prepareAudioForFastMux(
                        this, uri, destination, Math.max(1L, durationMs) * 1000L);
                boolean usesFile = "file".equals(prepared.getScheme())
                        && destination.getAbsolutePath().equals(prepared.getPath());
                runOnUiThread(() -> {
                    preparedMusicUri = prepared;
                    preparedAudioFile = usesFile ? destination : null;
                    if (!usesFile && destination.exists()) destination.delete();
                    audioPreparing = false;
                    recordButton.setEnabled(true);
                    clipStatus.setText("Musique prête · montage final instantané");
                });
            } catch (Exception error) {
                if (destination.exists()) destination.delete();
                runOnUiThread(() -> {
                    audioPreparing = false;
                    preparedMusicUri = null;
                    recordButton.setEnabled(true);
                    clipStatus.setText("Préparation audio impossible");
                    showError("Impossible de préparer cette musique : " + error.getMessage());
                });
            }
        });
    }

    private void toggleMusicPreview() {
        if (!musicPrepared || musicPlayer == null || sessionActive || renderedClipFile != null) return;
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

    private void startClipSession() {
        if (!musicPrepared || musicPlayer == null || musicUri == null) {
            Toast.makeText(this, "Importe d’abord ta musique", Toast.LENGTH_SHORT).show();
            return;
        }
        if (audioPreparing) {
            Toast.makeText(this, "Préparation de la musique en cours…",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (preparedMusicUri == null) {
            Toast.makeText(this, "Cette musique n’est pas encore prête",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (videoCapture == null) {
            Toast.makeText(this, "Caméra en préparation…", Toast.LENGTH_SHORT).show();
            return;
        }

        cleanupFinishedClip();
        sourceTimeline.clear();
        completedSegments.clear();
        recordingAccumulatedMs = 0L;
        planNumber = 1;
        finishRequested = false;
        segmentStopRequested = false;
        sessionActive = true;
        clipPaused = false;
        downloadButton.setVisibility(View.GONE);
        recordButton.setVisibility(View.VISIBLE);

        int width = quality == 1080 ? 1080 : 720;
        int height = quality == 1080 ? 1920 : 1280;
        try {
            realtimeRecorder = new RealtimeClipRecorder(
                    this, width, height, threshold, softness);
        } catch (Exception error) {
            realtimeRecorder = null;
            Toast.makeText(this,
                    "Mode secours activé : la vidéo sera quand même rendue",
                    Toast.LENGTH_LONG).show();
        }

        try {
            musicPlayer.pause();
            clipAudioStartMs = Math.min(musicSeekBar.getProgress(),
                    Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(clipAudioStartMs);
        } catch (IllegalStateException error) {
            sessionActive = false;
            showError("La musique n’est pas prête");
            return;
        }
        renderTimeline();
        startNewPlanRecording();
    }

    private void startNewPlanRecording() {
        if (!sessionActive || activeRecording != null || videoCapture == null) return;
        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) directory = getCacheDir();
        final File segmentFile = new File(directory,
                "clip_plan_" + System.currentTimeMillis() + "_" + planNumber + ".mp4");
        currentSegmentFile = segmentFile;
        final BackgroundSpec.Type segmentType = backgroundSpec.getType();
        final Uri segmentBackgroundUri = backgroundSpec.getUri();
        final int segmentColor = backgroundSpec.getColor();
        final String segmentLabel = currentDecorLabel;
        currentSegmentLabel = segmentLabel;
        segmentStopRequested = false;

        if (realtimeRecorder != null) {
            realtimeRecorder.beginPlan(segmentType, segmentBackgroundUri, segmentColor);
        }

        FileOutputOptions options = new FileOutputOptions.Builder(segmentFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
        recordButton.setEnabled(false);
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    activeSegmentStartedAt = SystemClock.elapsedRealtime();
                    realtimePlanStartedAt = activeSegmentStartedAt;
                    realtimePlanBaseUs = recordingAccumulatedMs * 1000L;
                    realtimeCaptureActive = true;
                    clipPaused = false;
                    recordingTimer.setVisibility(View.VISIBLE);
                    recordButton.setEnabled(true);
                    recordButton.setText("Ⅱ  PAUSE   " + formatDuration(recordingAccumulatedMs));
                    finishButton.setVisibility(View.VISIBLE);
                    finishButton.setEnabled(true);
                    clipStatus.setText("PLAN " + planNumber + " · montage créé en direct");
                    seekMusicToTimelineAndPlay();
                    restartVideoBackgroundForPlan();
                    renderTimeline();
                    uiHandler.removeCallbacks(timerTick);
                    uiHandler.post(timerTick);
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                    realtimeCaptureActive = false;
                    activeRecording = null;
                    segmentStopRequested = false;
                    handlePlanFinalized(finalized, segmentFile, segmentType,
                            segmentBackgroundUri, segmentColor, segmentLabel);
                    activeSegmentStartedAt = 0L;
                    realtimePlanStartedAt = 0L;
                }
            });
        } catch (Exception error) {
            realtimeCaptureActive = false;
            activeRecording = null;
            sessionActive = false;
            showIdleUi();
            showError("Impossible de démarrer le plan");
        }
    }

    private void pauseCurrentPlan() {
        if (!sessionActive || activeRecording == null || segmentStopRequested) return;
        segmentStopRequested = true;
        clipPaused = true;
        realtimeCaptureActive = false;
        pauseMusicAndBackground();
        recordButton.setEnabled(false);
        recordButton.setText("Sécurisation du plan…");
        clipStatus.setText("PAUSE · plan déjà monté en arrière-plan");
        activeRecording.stop();
    }

    private void handlePlanFinalized(VideoRecordEvent.Finalize finalized, File segmentFile,
                                     BackgroundSpec.Type segmentType, Uri segmentBackgroundUri,
                                     int segmentColor, String segmentLabel) {
        if (finalized.getError() != VideoRecordEvent.Finalize.ERROR_NONE
                || segmentFile == null || !segmentFile.isFile()) {
            deleteFile(segmentFile);
            sessionActive = false;
            showError("Le plan n’a pas pu être conservé");
            showIdleUi();
            return;
        }

        long durationMs = readVideoDurationMs(segmentFile);
        if (durationMs <= 0L) {
            durationMs = Math.max(1L,
                    SystemClock.elapsedRealtime() - Math.max(1L, activeSegmentStartedAt));
        }
        long startMs = recordingAccumulatedMs;
        long endMs = startMs + durationMs;
        sourceTimeline.add(new ClipSourceTimeline.Segment(
                segmentFile.getAbsolutePath(), segmentType, segmentBackgroundUri,
                segmentColor, segmentLabel, durationMs));
        completedSegments.add(new ClipSegmentUi(startMs, endMs, segmentLabel));
        recordingAccumulatedMs = endMs;
        clipPaused = true;
        planNumber = completedSegments.size() + 1;
        currentSegmentFile = null;
        recordingTimer.setText(formatDuration(recordingAccumulatedMs));
        seekMusicToTimeline(false);
        renderTimeline();

        if (finishRequested) {
            finishInstantMontage();
            return;
        }

        setDecorControlsEnabled(true);
        setMusicControlsEnabled(false);
        recordButton.setVisibility(View.VISIBLE);
        recordButton.setEnabled(true);
        recordButton.setText("▶  REPRENDRE   " + formatDuration(recordingAccumulatedMs));
        finishButton.setVisibility(View.VISIBLE);
        finishButton.setEnabled(true);
        clipStatus.setText("PAUSE · change IMAGE/VIDÉO puis REPRENDRE");
    }

    private void resumeNextPlan() {
        if (!sessionActive || !clipPaused || activeRecording != null || finishRequested) return;
        clipPaused = false;
        setDecorControlsEnabled(false);
        clipStatus.setText("Préparation du plan " + planNumber + "…");
        startNewPlanRecording();
    }

    private void finishClipSession() {
        if (!sessionActive || finishRequested) return;
        finishRequested = true;
        finishButton.setEnabled(false);
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        pauseMusicAndBackground();
        realtimeCaptureActive = false;
        if (activeRecording != null) {
            segmentStopRequested = true;
            clipPaused = true;
            recordButton.setEnabled(false);
            recordButton.setText("Finalisation…");
            activeRecording.stop();
        } else {
            finishInstantMontage();
        }
    }

    private void finishInstantMontage() {
        if (sourceTimeline.isEmpty()) {
            sessionActive = false;
            finishRequested = false;
            showError("Aucun plan à monter");
            showIdleUi();
            return;
        }
        sessionActive = false;
        clipPaused = false;
        uiHandler.removeCallbacks(timerTick);
        showBusy(92, "Fermeture du clip…");

        RealtimeClipRecorder recorder = realtimeRecorder;
        realtimeRecorder = null;
        if (recorder == null) {
            buildFastRawFallback();
            return;
        }
        recorder.finish((videoOnly, error) -> {
            if (videoOnly != null && videoOnly.isFile() && videoOnly.length() > 0L) {
                finalizeAudioAndExpose(videoOnly, false);
            } else {
                buildFastRawFallback();
            }
        });
    }

    private void buildFastRawFallback() {
        showBusy(94, "Secours vidéo instantané…");
        ioExecutor.execute(() -> {
            File videoOnly = new File(getCacheDir(),
                    "clip_raw_fast_" + System.currentTimeMillis() + ".mp4");
            try {
                FastRawClipAssembler.assemble(sourceTimeline, videoOnly);
                runOnUiThread(() -> finalizeAudioAndExpose(videoOnly, true));
            } catch (Exception concatenateError) {
                File first = firstAvailableRawPlan();
                if (first == null) {
                    runOnUiThread(() -> {
                        showError("Aucune vidéo récupérable");
                        showIdleUi();
                    });
                    return;
                }
                try {
                    MuxerUtils.copyVideoOnly(first, videoOnly);
                    runOnUiThread(() -> finalizeAudioAndExpose(videoOnly, true));
                } catch (Exception copyError) {
                    runOnUiThread(() -> {
                        showError("Impossible de récupérer la vidéo");
                        showIdleUi();
                    });
                }
            }
        });
    }

    private void finalizeAudioAndExpose(File videoOnly, boolean rawFallback) {
        processingProgress.setProgress(97);
        ((TextView) findViewById(R.id.processingText)).setText("Ajout instantané de la musique…");
        ioExecutor.execute(() -> {
            File finalVideo = new File(getCacheDir(),
                    "clip_ready_" + System.currentTimeMillis() + ".mp4");
            boolean audioOk = false;
            Exception audioError = null;
            Uri preferredAudio = preparedMusicUri != null ? preparedMusicUri : musicUri;
            long durationUs = Math.max(1L, recordingAccumulatedMs) * 1000L;
            try {
                MuxerUtils.addAudio(this, videoOnly, preferredAudio, finalVideo,
                        durationUs, Math.max(0L, clipAudioStartMs) * 1000L);
                audioOk = MuxerUtils.hasAudioTrack(finalVideo);
            } catch (Exception firstError) {
                audioError = firstError;
                if (musicUri != null && preferredAudio != null
                        && !musicUri.toString().equals(preferredAudio.toString())) {
                    try {
                        MuxerUtils.addAudio(this, videoOnly, musicUri, finalVideo,
                                durationUs, Math.max(0L, clipAudioStartMs) * 1000L);
                        audioOk = MuxerUtils.hasAudioTrack(finalVideo);
                        audioError = null;
                    } catch (Exception secondError) {
                        audioError = secondError;
                    }
                }
            }

            if (!audioOk) {
                try {
                    if (finalVideo.exists()) finalVideo.delete();
                    MuxerUtils.copyVideoOnly(videoOnly, finalVideo);
                } catch (Exception copyError) {
                    File first = firstAvailableRawPlan();
                    if (first != null) {
                        try { MuxerUtils.copyVideoOnly(first, finalVideo); }
                        catch (Exception ignored) { }
                    }
                }
            }

            boolean validVideo = false;
            try { validVideo = MuxerUtils.hasVideoTrack(finalVideo); }
            catch (Exception ignored) { }
            if (!validVideo) {
                runOnUiThread(() -> {
                    showError("La vidéo finale n'a pas pu être créée");
                    showIdleUi();
                });
                return;
            }

            deleteFile(videoOnly);
            deleteSessionSources();
            boolean finalAudioOk = audioOk;
            Exception finalAudioError = audioError;
            runOnUiThread(() -> {
                renderedClipFile = finalVideo;
                showReadyToDownload();
                if (rawFallback) {
                    Toast.makeText(this,
                            "Vidéo récupérée en mode secours rapide",
                            Toast.LENGTH_LONG).show();
                }
                if (!finalAudioOk) {
                    Toast.makeText(this,
                            "Vidéo prête. La musique n'a pas pu être intégrée : "
                                    + (finalAudioError == null ? "format audio incompatible"
                                    : finalAudioError.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private File firstAvailableRawPlan() {
        for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
            File file = new File(segment.sourcePath);
            if (file.isFile() && file.length() > 0L) return file;
        }
        return null;
    }

    private void showReadyToDownload() {
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText("↓  TÉLÉCHARGER LA VIDÉO");
        setDecorControlsEnabled(false);
        setMusicControlsEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
        clipStatus.setText("VIDÉO PRÊTE · téléchargement disponible");
        timelineStatus.setText(completedSegments.size() + " plan(s) · vidéo prête");
        renderTimeline();
    }

    private void saveRenderedClip() {
        if (renderedClipFile == null || !renderedClipFile.isFile()) {
            showError("La vidéo n’est plus disponible");
            return;
        }
        downloadButton.setEnabled(false);
        downloadButton.setText("Téléchargement…");
        File source = renderedClipFile;
        ioExecutor.execute(() -> {
            try {
                Uri uri = MediaStoreSaver.saveVideo(this, source,
                        "ClipMusique_" + System.currentTimeMillis() + ".mp4");
                deleteFile(source);
                runOnUiThread(() -> {
                    renderedClipFile = null;
                    downloadButton.setVisibility(View.GONE);
                    clipStatus.setText("Enregistré dans la galerie");
                    showSavedSnackbar(uri);
                    recordButton.setVisibility(View.VISIBLE);
                    recordButton.setEnabled(!audioPreparing && preparedMusicUri != null);
                    recordButton.setText("●  NOUVEAU TOURNAGE");
                    setDecorControlsEnabled(true);
                    setMusicControlsEnabled(true);
                    flipCameraButton.setEnabled(true);
                    qualityButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadButton.setEnabled(true);
                    downloadButton.setText("↓  TÉLÉCHARGER LA VIDÉO");
                    showError("Téléchargement impossible : " + error.getMessage());
                });
            }
        });
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
            if (!sessionActive) backgroundVideo.start();
            else if (clipPaused) backgroundVideo.seekTo(1);
            else backgroundVideo.start();
        });
    }

    private void seekMusicToTimelineAndPlay() {
        seekMusicToTimeline(true);
    }

    private void seekMusicToTimeline(boolean play) {
        if (!musicPrepared || musicPlayer == null) return;
        try {
            int target = (int) Math.min(Integer.MAX_VALUE,
                    (long) clipAudioStartMs + recordingAccumulatedMs);
            target = Math.min(target, Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(target);
            if (play) musicPlayer.start();
        } catch (IllegalStateException ignored) { }
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
        if (!sessionActive || activeRecording == null || activeSegmentStartedAt <= 0L
                || segmentStopRequested) return recordingAccumulatedMs;
        return recordingAccumulatedMs
                + Math.max(0L, SystemClock.elapsedRealtime() - activeSegmentStartedAt);
    }

    private long readVideoDurationMs(File file) {
        if (file == null || !file.isFile()) return 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
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
        synchronized (maskLock) {
            latestMask = result.mask;
            latestMaskWidth = result.maskWidth;
            latestMaskHeight = result.maskHeight;
        }
        subjectPreview.setMask(result.alphaMask, result.mask, result.maskWidth,
                result.maskHeight, threshold, softness);
        if (result.source != null && !result.source.isRecycled()) result.source.recycle();
        if (result.cutout != null && !result.cutout.isRecycled()) result.cutout.recycle();
    }

    private void renderTimeline() {
        if (timelineTrack == null) return;
        timelineTrack.removeAllViews();
        int index = 1;
        for (ClipSegmentUi segment : completedSegments) {
            addTimelineChip("PLAN " + index,
                    formatDuration(segment.startMs) + " → " + formatDuration(segment.endMs),
                    shortLabel(segment.label), false);
            index++;
        }
        if (sessionActive && activeRecording != null) {
            addTimelineChip("PLAN " + index, "EN COURS",
                    shortLabel(currentSegmentLabel), true);
        } else if (sessionActive && clipPaused) {
            addTimelineChip("+ PROCHAIN", "à " + formatDuration(recordingAccumulatedMs),
                    shortLabel(currentDecorLabel), true);
        }
        if (completedSegments.isEmpty() && !sessionActive) {
            addTimelineChip("TIMELINE", "Les plans apparaîtront ici",
                    "PAUSE = nouveau plan", true);
            timelineStatus.setText("0 plan · prêt à tourner");
        } else if (!sessionActive && !completedSegments.isEmpty()) {
            timelineStatus.setText(completedSegments.size() + " plan(s) · "
                    + formatDuration(recordingAccumulatedMs));
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
        recordButton.setEnabled(!audioPreparing && preparedMusicUri != null);
        finishButton.setVisibility(View.GONE);
        finishButton.setEnabled(true);
        downloadButton.setVisibility(View.GONE);
        setDecorControlsEnabled(true);
        setMusicControlsEnabled(true);
        flipCameraButton.setEnabled(true);
        qualityButton.setEnabled(true);
        clipStatus.setText("Clip prêt · rendu créé pendant le tournage");
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
        Snackbar.make(recordButton, "Clip enregistré dans la galerie", Snackbar.LENGTH_LONG)
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

    private void deletePreparedAudio() {
        if (preparedAudioFile != null) deleteFile(preparedAudioFile);
        preparedAudioFile = null;
        preparedMusicUri = null;
    }

    private void cleanupFinishedClip() {
        if (renderedClipFile != null) deleteFile(renderedClipFile);
        renderedClipFile = null;
        deleteSessionSources();
        if (realtimeRecorder != null) {
            realtimeRecorder.close();
            realtimeRecorder = null;
        }
    }

    private void deleteSessionSources() {
        for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
            deleteFile(new File(segment.sourcePath));
        }
        if (currentSegmentFile != null) deleteFile(currentSegmentFile);
        currentSegmentFile = null;
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
        if (!sessionActive && renderedClipFile == null
                && backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try { backgroundVideo.start(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onPause() {
        if (sessionActive && activeRecording != null && !segmentStopRequested) {
            pauseCurrentPlan();
        }
        pauseMusicAndBackground();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(timerTick);
        uiHandler.removeCallbacks(audioProgressTick);
        realtimeCaptureActive = false;
        if (activeRecording != null) {
            try { activeRecording.stop(); } catch (Exception ignored) { }
            activeRecording = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (realtimeRecorder != null) {
            realtimeRecorder.close();
            realtimeRecorder = null;
        }
        cameraExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (segmenter != null) segmenter.close();
        subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) currentSource.recycle();
        currentSource = null;
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundImage.setImageDrawable(null);
        releaseMusicPlayer();
        deletePreparedAudio();
        if (renderedClipFile != null) deleteFile(renderedClipFile);
        deleteSessionSources();
        super.onDestroy();
    }
}
