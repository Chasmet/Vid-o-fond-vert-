package com.chasmet.fondvertstudio;

import android.Manifest;
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
import android.provider.OpenableColumns;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caméra Fond vert : rendu temps réel prioritaire, projet récupérable et deuxième passe
 * de rendu sécurisée avant tout recours à la vidéo caméra brute.
 */
public final class GreenScreenCameraActivity extends AppCompatActivity {
    private final BackgroundSpec backgroundSpec = new BackgroundSpec();
    private final ClipSourceTimeline sourceTimeline = new ClipSourceTimeline();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Object maskLock = new Object();

    private MaskedCameraView subjectPreview;
    private ImageView backgroundImage;
    private FillVideoView backgroundVideo;
    private View previewContainer;
    private View processingOverlay;
    private ProgressBar processingProgress;
    private TextView processingText;
    private TextView previewHint;
    private TextView recordingTimer;
    private TextView transformHint;
    private TextView decorStatus;
    private TextView audioName;
    private TextView musicPosition;
    private TextView clipStatus;
    private TextView timelineStatus;
    private MaterialButton recordButton;
    private MaterialButton finishButton;
    private MaterialButton downloadButton;
    private MaterialButton flipCameraButton;
    private MaterialButton qualityButton;
    private MaterialButton resetSubjectButton;
    private MaterialButton importImageButton;
    private MaterialButton importVideoButton;
    private MaterialButton greenButton;
    private MaterialButton importAudioButton;
    private MaterialButton downloadsMusicButton;
    private MaterialButton musicPlayButton;
    private MaterialButton audioMinusButton;
    private MaterialButton audioAutoButton;
    private MaterialButton audioPlusButton;
    private SeekBar musicSeekBar;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private SegmentationEngine segmenter;
    private RealtimeClipRecorder realtimeRecorder;

    private Bitmap currentSource;
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
    private int detectedAudioStartMs;

    private File currentSegmentFile;
    private File renderedClipFile;
    private boolean sessionActive;
    private boolean paused;
    private boolean finishRequested;
    private boolean stopRequested;
    private int planNumber = 1;
    private String currentDecorLabel = "Fond vert";
    private volatile long accumulatedMs;
    private volatile long segmentStartedAt;
    private volatile boolean realtimeCaptureActive;
    private volatile long realtimePlanBaseUs;
    private volatile long realtimePlanStartedAt;
    private volatile float liveScale = SubjectTransformTimeline.DEFAULT_SCALE;
    private volatile float liveCenterX = SubjectTransformTimeline.DEFAULT_CENTER_X;
    private volatile float liveCenterY = SubjectTransformTimeline.DEFAULT_CENTER_Y;
    private int quality = 1080;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean cameraReady;
    private boolean horizontalFormat;
    private ProjectRepository projectRepository;
    private boolean restoredProject;
    private boolean forceOfflineRender;
    private boolean lifecycleInterrupted;
    private boolean activityDestroyed;
    private boolean projectSaveWarningShown;
    private String readyProjectStatus = ProjectRepository.STATUS_READY_EFFECT;
    private String rendererMessage = "";
    private boolean readyAudioApplied = true;
    private int pendingRestoredAudioStartMs = -1;

    private static final float THRESHOLD = 0.52f;
    private static final float SOFTNESS = 0.040f;

    private ActivityResultLauncher<String[]> audioPicker;
    private ActivityResultLauncher<String[]> imagePicker;
    private ActivityResultLauncher<String[]> videoPicker;
    private ActivityResultLauncher<String> cameraPermission;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) return;
            long duration = currentDurationMs();
            recordingTimer.setText(format(duration));
            if (activeRecording != null && !stopRequested) {
                recordButton.setText("Ⅱ  PAUSE   " + format(duration));
            }
            timelineStatus.setText(Math.max(1, planNumber) + " plan(s) · " + format(duration));
            ui.postDelayed(this, 150L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        CaptureFormat.applyRequestedOrientation(this, getIntent());
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this,
                    "Fond vert nécessite Android 6 ou supérieur. Le mode Classique reste disponible.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        horizontalFormat = CaptureFormat.isHorizontal(getIntent());
        setContentView(R.layout.activity_clip_timeline);
        projectRepository = new ProjectRepository(this);
        bindViews();
        registerPickers();
        setupControls();
        backgroundSpec.setColor(Color.rgb(0, 255, 0));
        showGreen();
        segmenter = new SegmentationEngine(this);
        segmenter.setEdgeSettings(THRESHOLD, SOFTNESS);
        if (getIntent().getBooleanExtra(ProjectRepository.EXTRA_RESUME_PROJECT, false)) {
            restoredProject = restoreProject();
        }
        if (renderedClipFile == null) requestCamera();
    }

    private void bindViews() {
        subjectPreview = findViewById(R.id.subjectPreview);
        backgroundImage = findViewById(R.id.backgroundImage);
        backgroundVideo = findViewById(R.id.backgroundVideo);
        previewContainer = findViewById(R.id.previewContainer);
        processingOverlay = findViewById(R.id.processingOverlay);
        processingProgress = findViewById(R.id.processingProgress);
        processingText = findViewById(R.id.processingText);
        previewHint = findViewById(R.id.previewHint);
        recordingTimer = findViewById(R.id.recordingTimer);
        transformHint = findViewById(R.id.transformHint);
        decorStatus = findViewById(R.id.decorStatus);
        audioName = findViewById(R.id.audioName);
        musicPosition = findViewById(R.id.musicPosition);
        clipStatus = findViewById(R.id.clipStatus);
        timelineStatus = findViewById(R.id.timelineStatus);
        recordButton = findViewById(R.id.recordButton);
        finishButton = findViewById(R.id.finishButton);
        downloadButton = findViewById(R.id.downloadButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        qualityButton = findViewById(R.id.qualityButton);
        resetSubjectButton = findViewById(R.id.resetSubjectButton);
        importImageButton = findViewById(R.id.importImageButton);
        importVideoButton = findViewById(R.id.importVideoButton);
        greenButton = findViewById(R.id.greenBackgroundButton);
        importAudioButton = findViewById(R.id.importAudioButton);
        downloadsMusicButton = findViewById(R.id.downloadsMusicButton);
        musicPlayButton = findViewById(R.id.musicPlayButton);
        audioMinusButton = findViewById(R.id.audioMinusButton);
        audioAutoButton = findViewById(R.id.audioAutoButton);
        audioPlusButton = findViewById(R.id.audioPlusButton);
        musicSeekBar = findViewById(R.id.musicSeekBar);
    }

    private void registerPickers() {
        audioPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            persist(uri);
            loadMusic(uri);
        });
        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || !canChangeDecor()) return;
            persist(uri);
            backgroundSpec.setImage(uri);
            currentDecorLabel = "Image · " + displayName(uri);
            decorStatus.setText(currentDecorLabel);
            showImage(uri);
            checkpointProject();
        });
        videoPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || !canChangeDecor()) return;
            persist(uri);
            backgroundSpec.setVideo(uri);
            currentDecorLabel = "Vidéo · " + displayName(uri);
            decorStatus.setText(currentDecorLabel);
            showVideo(uri);
            checkpointProject();
        });
        cameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startCamera();
                    else Toast.makeText(this, "Caméra nécessaire", Toast.LENGTH_LONG).show();
                });
    }

    private void setupControls() {
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        importAudioButton.setOnClickListener(v -> {
            if (canChangeMusic()) audioPicker.launch(new String[]{"audio/*"});
        });
        downloadsMusicButton.setOnClickListener(v -> {
            if (canChangeMusic()) audioPicker.launch(new String[]{"audio/*"});
        });
        importImageButton.setOnClickListener(v -> {
            if (canChangeDecor()) imagePicker.launch(new String[]{"image/*"});
        });
        importVideoButton.setOnClickListener(v -> {
            if (canChangeDecor()) videoPicker.launch(new String[]{"video/*"});
        });
        greenButton.setOnClickListener(v -> {
            if (!canChangeDecor()) return;
            backgroundSpec.setColor(Color.rgb(0, 255, 0));
            currentDecorLabel = "Fond vert";
            decorStatus.setText(currentDecorLabel);
            showGreen();
            checkpointProject();
        });
        musicPlayButton.setOnClickListener(v -> toggleMusic());
        audioMinusButton.setOnClickListener(v -> adjustMusicStart(-100));
        audioAutoButton.setOnClickListener(v -> applyDetectedMusicStart());
        audioPlusButton.setOnClickListener(v -> adjustMusicStart(100));
        recordButton.setOnClickListener(v -> {
            if (!sessionActive) startSession();
            else if (activeRecording != null) pausePlan(false);
            else if (paused) resumePlan();
        });
        recordButton.setText("CAMÉRA…");
        recordButton.setEnabled(false);
        audioMinusButton.setEnabled(false);
        audioAutoButton.setEnabled(false);
        audioPlusButton.setEnabled(false);
        finishButton.setOnClickListener(v -> finishSession());
        downloadButton.setOnClickListener(v -> downloadVideo());
        flipCameraButton.setOnClickListener(v -> {
            if (sessionActive || renderedClipFile != null) return;
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        qualityButton.setOnClickListener(v -> {
            if (sessionActive || renderedClipFile != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });
        resetSubjectButton.setOnClickListener(v -> subjectPreview.resetSubjectTransform());
        subjectPreview.setTransformListener((scale, x, y, finished) -> {
            liveScale = scale;
            liveCenterX = x;
            liveCenterY = y;
            int percent = Math.round(scale * 100f);
            resetSubjectButton.setText("↺ " + percent + " %");
            transformHint.setText(finished ? "Glisse · pince pour zoomer" : "SUJET · " + percent + " %");
            if (finished) checkpointProject();
        });
        musicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateMusicPosition(progress);
                if (fromUser && musicPrepared && musicPlayer != null && !sessionActive) {
                    try { musicPlayer.seekTo(progress); } catch (Exception ignored) { }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                clipStatus.setText("DÉPART MANUEL · " + formatPrecise(bar.getProgress()));
            }
        });
    }

    private boolean restoreProject() {
        ProjectRepository.Draft draft = projectRepository.load();
        if (draft == null) return false;

        sourceTimeline.replaceWith(draft.timeline);
        accumulatedMs = sourceTimeline.totalDurationMs();
        planNumber = sourceTimeline.size() + 1;
        quality = draft.quality;
        lensFacing = draft.lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        qualityButton.setText(quality + "p");
        liveScale = draft.subjectScale;
        liveCenterX = draft.subjectCenterX;
        liveCenterY = draft.subjectCenterY;
        subjectPreview.setSubjectTransform(liveScale, liveCenterX, liveCenterY);
        resetSubjectButton.setText("↺ " + Math.round(liveScale * 100f) + " %");
        applyDraftBackground(draft);

        clipAudioStartMs = draft.audioStartMs;
        detectedAudioStartMs = draft.detectedAudioStartMs;
        pendingRestoredAudioStartMs = draft.audioStartMs;
        readyProjectStatus = draft.status;
        rendererMessage = draft.rendererMessage;
        timelineStatus.setText(sourceTimeline.size() + " plan(s) · " + format(accumulatedMs));

        if (draft.isReady() && !draft.renderedPath.isEmpty()) {
            renderedClipFile = new File(draft.renderedPath);
            readyAudioApplied = draft.audioApplied;
            sessionActive = false;
            paused = false;
            showReady(ProjectRepository.STATUS_READY_EFFECT.equals(draft.status),
                    readyAudioApplied);
            return true;
        }

        forceOfflineRender = true;
        sessionActive = true;
        paused = true;
        finishRequested = false;
        stopRequested = false;
        setControlsForRecording(false);
        finishButton.setVisibility(View.VISIBLE);
        finishButton.setEnabled(true);
        recordButton.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
        recordButton.setText("PRÉPARATION DU PROJET…");
        clipStatus.setText("PROJET RESTAURÉ · préparation de la musique");

        if (!draft.musicUri.isEmpty()) {
            try {
                loadMusic(Uri.parse(draft.musicUri));
            } catch (Exception error) {
                showRestoredMusicMissing();
            }
        } else {
            showRestoredMusicMissing();
        }
        return true;
    }

    private void applyDraftBackground(ProjectRepository.Draft draft) {
        BackgroundSpec.Type type;
        try {
            type = BackgroundSpec.Type.valueOf(draft.backgroundType);
        } catch (IllegalArgumentException ignored) {
            type = BackgroundSpec.Type.COLOR;
        }
        Uri uri = draft.backgroundUri.isEmpty() ? null : Uri.parse(draft.backgroundUri);
        currentDecorLabel = draft.backgroundLabel;
        decorStatus.setText(currentDecorLabel);
        if (type == BackgroundSpec.Type.IMAGE && uri != null) {
            backgroundSpec.setImage(uri);
            showImage(uri);
        } else if (type == BackgroundSpec.Type.VIDEO && uri != null) {
            backgroundSpec.setVideo(uri);
            showVideo(uri);
        } else {
            backgroundSpec.setColor(draft.backgroundColor);
            if (draft.backgroundColor == Color.rgb(0, 255, 0)) {
                showGreen();
            } else {
                previewContainer.setBackgroundColor(draft.backgroundColor);
            }
        }
    }

    private void showRestoredMusicMissing() {
        musicUri = null;
        preparedMusicUri = null;
        audioPreparing = false;
        sessionActive = true;
        paused = true;
        importAudioButton.setEnabled(true);
        downloadsMusicButton.setEnabled(true);
        recordButton.setEnabled(false);
        recordButton.setText("♪ RÉIMPORTER LA MUSIQUE");
        clipStatus.setText("PROJET SAUVÉ · musique à réimporter pour continuer");
    }

    private void requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void startCamera() {
        if (segmenter == null || activeRecording != null) return;
        cameraReady = false;
        updateStartButton();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception error) {
                showError("Caméra indisponible");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null || activeRecording != null) return;
        cameraProvider.unbindAll();
        segmenter.resetStreamHistory();
        synchronized (maskLock) {
            latestMask = null;
            latestMaskWidth = 0;
            latestMaskHeight = 0;
        }
        Quality q = quality == 1080 ? Quality.FHD : Quality.HD;
        int targetRotation = CaptureFormat.surfaceRotation(this);
        Recorder recorder = new Recorder.Builder().setQualitySelector(
                QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build();
        videoCapture = VideoCapture.withOutput(recorder);
        videoCapture.setTargetRotation(targetRotation);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        Size analysisSize = horizontalFormat
                ? (quality == 1080 ? new Size(1280, 720) : new Size(960, 540))
                : (quality == 1080 ? new Size(720, 1280) : new Size(540, 960));
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(analysisSize)
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis, videoCapture);
            cameraReady = true;
            updateStartButton();
        } catch (Exception error) {
            cameraReady = false;
            updateStartButton();
            showError("Mode caméra non disponible");
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapUtils.fromRgbaImageProxy(imageProxy);
            bitmap = BitmapUtils.rotateAndMirror(bitmap,
                    imageProxy.getImageInfo().getRotationDegrees(),
                    lensFacing == CameraSelector.LENS_FACING_FRONT);
        } catch (Exception ignored) {
        } finally {
            imageProxy.close();
        }
        if (bitmap == null) return;

        Bitmap inference = null;
        if (!segmenter.isStreamBusy()) {
            inference = BitmapUtils.scaleDown(bitmap, 640);
            if (inference == bitmap) inference = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        Bitmap recordingFrame = null;
        Bitmap displayFrame = bitmap;
        if (realtimeCaptureActive && realtimeRecorder != null) {
            recordingFrame = bitmap;
            displayFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap uiFrame = displayFrame;
        ui.post(() -> acceptSource(uiFrame));

        if (recordingFrame != null) {
            float[] mask;
            int mw;
            int mh;
            synchronized (maskLock) {
                mask = latestMask;
                mw = latestMaskWidth;
                mh = latestMaskHeight;
            }
            long localUs = Math.max(0L,
                    SystemClock.elapsedRealtime() - realtimePlanStartedAt) * 1000L;
            realtimeRecorder.offerFrame(recordingFrame, mask, mw, mh,
                    realtimePlanBaseUs + localUs, localUs,
                    liveScale, liveCenterX, liveCenterY);
        }

        if (inference != null) {
            segmenter.processStream(inference, new SegmentationEngine.Callback() {
                @Override public void onResult(SegmentationEngine.Result result) {
                    synchronized (maskLock) {
                        latestMask = result.mask;
                        latestMaskWidth = result.maskWidth;
                        latestMaskHeight = result.maskHeight;
                    }
                    subjectPreview.setMask(result.alphaMask, result.mask,
                            result.maskWidth, result.maskHeight, THRESHOLD, SOFTNESS);
                    if (result.source != null && !result.source.isRecycled()) result.source.recycle();
                    if (result.cutout != null && !result.cutout.isRecycled()) result.cutout.recycle();
                }
                @Override public void onError(Exception error) { }
            });
        }
    }

    private void acceptSource(Bitmap source) {
        Bitmap old = currentSource;
        currentSource = source;
        subjectPreview.setSource(source);
        previewHint.setVisibility(View.GONE);
        if (old != null && old != source && !old.isRecycled()) old.recycle();
    }

    private void loadMusic(Uri uri) {
        releaseMusic();
        deletePreparedAudio();
        musicUri = uri;
        audioName.setText(displayName(uri));
        clipStatus.setText("Chargement musique…");
        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(this, uri);
            musicPlayer.setOnErrorListener((player, what, extra) -> {
                musicPrepared = false;
                audioPreparing = false;
                if (restoredProject && sessionActive && paused) {
                    showRestoredMusicMissing();
                } else {
                    showError("Musique illisible");
                }
                return true;
            });
            musicPlayer.setOnPreparedListener(player -> {
                musicPrepared = true;
                musicSeekBar.setMax(Math.max(1, player.getDuration()));
                musicSeekBar.setProgress(0);
                updateMusicPosition(0);
                prepareAudio(uri, player.getDuration());
            });
            musicPlayer.prepareAsync();
        } catch (Exception error) {
            showError("Musique illisible");
        }
    }

    private void prepareAudio(Uri source, int durationMs) {
        audioPreparing = true;
        recordButton.setEnabled(false);
        clipStatus.setText("Préparation audio…");
        File dir = new File(getCacheDir(), "prepared_audio");
        if (!dir.exists()) dir.mkdirs();
        File destination = new File(dir, "audio_" + System.currentTimeMillis() + ".m4a");
        ioExecutor.execute(() -> {
            try {
                Uri prepared = MuxerUtils.prepareAudioForFastMux(
                        this, source, destination, Math.max(1L, durationMs) * 1000L);
                boolean generated = "file".equals(prepared.getScheme())
                        && destination.getAbsolutePath().equals(prepared.getPath());
                int detected = 0;
                try {
                    detected = AudioStartDetector.detectStartMs(
                            this, prepared, Math.max(1, durationMs));
                } catch (Exception ignored) {
                    detected = 0;
                }
                int finalDetected = Math.max(0, Math.min(durationMs - 1, detected));
                runOnUiThread(() -> {
                    preparedMusicUri = prepared;
                    preparedAudioFile = generated ? destination : null;
                    if (!generated && destination.exists()) destination.delete();
                    audioPreparing = false;
                    detectedAudioStartMs = finalDetected;
                    int selectedStart = pendingRestoredAudioStartMs >= 0
                            ? Math.max(0, Math.min(durationMs - 1,
                            pendingRestoredAudioStartMs))
                            : finalDetected;
                    pendingRestoredAudioStartMs = -1;
                    clipAudioStartMs = selectedStart;
                    musicSeekBar.setProgress(selectedStart);
                    updateMusicPosition(selectedStart);
                    audioMinusButton.setEnabled(true);
                    audioAutoButton.setEnabled(true);
                    audioPlusButton.setEnabled(true);
                    updateStartButton();
                    if (sessionActive && paused) {
                        clipStatus.setText("PROJET RESTAURÉ · prêt à reprendre");
                    } else {
                        clipStatus.setText("DÉBUT DÉTECTÉ · "
                                + formatPrecise(finalDetected)
                                + " · ajuste si besoin");
                    }
                    checkpointProject();
                });
            } catch (Exception error) {
                if (destination.exists()) destination.delete();
                runOnUiThread(() -> {
                    audioPreparing = false;
                    preparedMusicUri = null;
                    updateStartButton();
                    if (restoredProject && sessionActive && paused) {
                        showRestoredMusicMissing();
                    }
                    showError("Préparation audio impossible : " + error.getMessage());
                });
            }
        });
    }

    private void startSession() {
        if (!musicPrepared || musicUri == null) {
            Toast.makeText(this,
                    "Importe une musique : le micro reste toujours coupé",
                    Toast.LENGTH_LONG).show();
            audioPicker.launch(new String[]{"audio/*"});
            return;
        }
        if (audioPreparing || preparedMusicUri == null) {
            Toast.makeText(this, "Préparation de la musique en cours", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cameraReady || videoCapture == null) {
            Toast.makeText(this, "Caméra en préparation…", Toast.LENGTH_SHORT).show();
            startCamera();
            return;
        }
        projectRepository.clear(true);
        cleanupSessionFiles();
        sourceTimeline.clear();
        accumulatedMs = 0L;
        planNumber = 1;
        finishRequested = false;
        paused = false;
        sessionActive = true;
        restoredProject = false;
        forceOfflineRender = false;
        lifecycleInterrupted = false;
        readyProjectStatus = ProjectRepository.STATUS_PAUSED;
        rendererMessage = "";
        try {
            realtimeRecorder = new RealtimeClipRecorder(this,
                    CaptureFormat.videoWidth(horizontalFormat, quality),
                    CaptureFormat.videoHeight(horizontalFormat, quality),
                    THRESHOLD, SOFTNESS);
        } catch (Exception error) {
            realtimeRecorder = null;
        }
        clipAudioStartMs = musicSeekBar.getProgress();
        startPlan();
    }

    private void startPlan() {
        if (!sessionActive || activeRecording != null || videoCapture == null) return;
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getCacheDir();
        File segment = new File(dir,
                "clip_backup_" + System.currentTimeMillis() + "_" + planNumber + ".mp4");
        currentSegmentFile = segment;
        BackgroundSpec.Type type = backgroundSpec.getType();
        Uri bgUri = backgroundSpec.getUri();
        int bgColor = backgroundSpec.getColor();
        String label = currentDecorLabel;
        if (realtimeRecorder != null) {
            try {
                realtimeRecorder.beginPlan(type, bgUri, bgColor);
            } catch (Exception renderError) {
                realtimeRecorder.close();
                realtimeRecorder = null;
            }
        }

        PendingRecording pending = videoCapture.getOutput().prepareRecording(
                this, new FileOutputOptions.Builder(segment).build());
        // Aucun withAudioEnabled() : le micro est toujours coupé.
        setControlsForRecording(true);
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                segmentStartedAt = SystemClock.elapsedRealtime();
                realtimePlanStartedAt = segmentStartedAt;
                realtimePlanBaseUs = accumulatedMs * 1000L;
                realtimeCaptureActive = realtimeRecorder != null;
                stopRequested = false;
                paused = false;
                recordingTimer.setVisibility(View.VISIBLE);
                finishButton.setVisibility(View.VISIBLE);
                recordButton.setEnabled(true);
                clipStatus.setText("PLAN " + planNumber + " · montage en direct");
                seekMusicAndPlay();
                restartBackgroundVideo();
                ui.removeCallbacks(timerTick);
                ui.post(timerTick);
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalEvent = (VideoRecordEvent.Finalize) event;
                realtimeCaptureActive = false;
                activeRecording = null;
                handleFinalizedPlan(finalEvent, segment, type, bgUri, bgColor, label);
            }
        });
        } catch (Exception startError) {
            activeRecording = null;
            realtimeCaptureActive = false;
            sessionActive = false;
            paused = false;
            stopRequested = false;
            setIdleControlsEnabled(true);
            updateStartButton();
            showError("Impossible de démarrer la caméra : " + startError.getMessage());
        }
    }

    private void pausePlan(boolean interrupted) {
        if (activeRecording == null || stopRequested) return;
        lifecycleInterrupted = interrupted;
        stopRequested = true;
        realtimeCaptureActive = false;
        pauseMusicAndBackground();
        recordButton.setEnabled(false);
        recordButton.setText(interrupted ? "INTERRUPTION…" : "Pause…");
        activeRecording.stop();
    }

    private void handleFinalizedPlan(VideoRecordEvent.Finalize event, File segment,
                                     BackgroundSpec.Type type, Uri bgUri,
                                     int bgColor, String label) {
        if (!segment.isFile() || segment.length() <= 0L) {
            deleteFile(segment);
            showError("Plan non enregistré");
            showIdle();
            return;
        }
        if (event.getError() != VideoRecordEvent.Finalize.ERROR_NONE) {
            Toast.makeText(this,
                    "Plan récupéré malgré un avertissement caméra",
                    Toast.LENGTH_SHORT).show();
        }
        long duration = videoDuration(segment);
        if (duration <= 0L) duration = Math.max(1L,
                SystemClock.elapsedRealtime() - segmentStartedAt);
        sourceTimeline.add(new ClipSourceTimeline.Segment(
                segment.getAbsolutePath(), type, bgUri, bgColor, label, duration));
        accumulatedMs += duration;
        planNumber = sourceTimeline.size() + 1;
        paused = true;
        stopRequested = false;
        currentSegmentFile = null;
        segmentStartedAt = 0L;
        seekMusic(false);
        timelineStatus.setText(sourceTimeline.size() + " plan(s) · " + format(accumulatedMs));
        checkpointProject();
        if (finishRequested) {
            finalizeSession();
        } else {
            setControlsForRecording(false);
            recordButton.setEnabled(true);
            recordButton.setText("▶ REPRENDRE · " + format(accumulatedMs));
            finishButton.setVisibility(View.VISIBLE);
            clipStatus.setText(lifecycleInterrupted
                    ? "ENREGISTREMENT INTERROMPU · projet sauvegardé · REPRENDRE"
                    : "PAUSE · projet sauvegardé · change le décor puis REPRENDRE");
        }
    }

    private void resumePlan() {
        if (!sessionActive || !paused || finishRequested) return;
        if (!musicPrepared || preparedMusicUri == null) {
            showRestoredMusicMissing();
            return;
        }
        lifecycleInterrupted = false;
        paused = false;
        startPlan();
    }

    private void finishSession() {
        if (!sessionActive || finishRequested) return;
        finishRequested = true;
        realtimeCaptureActive = false;
        pauseMusicAndBackground();
        finishButton.setEnabled(false);
        checkpointProject(ProjectRepository.STATUS_FINALIZING, "");
        if (activeRecording != null) {
            stopRequested = true;
            recordButton.setEnabled(false);
            recordButton.setText("Finalisation…");
            activeRecording.stop();
        } else {
            finalizeSession();
        }
    }

    private void finalizeSession() {
        sessionActive = false;
        paused = false;
        ui.removeCallbacks(timerTick);
        if (sourceTimeline.isEmpty()) {
            showError("Aucun plan disponible");
            showIdle();
            return;
        }
        showBusy(94, "Finalisation instantanée…");
        RealtimeClipRecorder recorder = realtimeRecorder;
        realtimeRecorder = null;
        if (forceOfflineRender || recorder == null) {
            if (recorder != null) recorder.close();
            renderSecondPass(recorder == null
                    ? "Rendu temps réel indisponible" : "Projet restauré");
            return;
        }
        recorder.finish((videoOnly, error) -> {
            if (videoOnly != null && videoOnly.isFile() && videoOnly.length() > 0L) {
                addAudioAndExpose(videoOnly, false);
            } else {
                renderSecondPass(error == null
                        ? "Rendu temps réel vide" : safeMessage(error));
            }
        });
    }

    private void renderSecondPass(String firstFailure) {
        rendererMessage = firstFailure == null ? "" : firstFailure;
        showBusy(3, "Deuxième rendu Fond vert…");
        final File timelineFile;
        try {
            timelineFile = projectRepository.createProjectFile("render_timeline", ".json");
            sourceTimeline.write(timelineFile);
        } catch (Exception error) {
            buildRawFallback("Deuxième rendu impossible : " + safeMessage(error));
            return;
        }

        Uri audio = preparedMusicUri != null ? preparedMusicUri : musicUri;
        if (audio == null) {
            buildRawFallback("Musique introuvable pour le deuxième rendu");
            return;
        }
        Data input = new Data.Builder()
                .putString(VideoRendererWorker.KEY_SOURCE_TIMELINE_PATH,
                        timelineFile.getAbsolutePath())
                .putString(VideoRendererWorker.KEY_EXTERNAL_AUDIO_URI, audio.toString())
                .putLong(VideoRendererWorker.KEY_EXTERNAL_AUDIO_START_MS, clipAudioStartMs)
                .putFloat(VideoRendererWorker.KEY_THRESHOLD, THRESHOLD)
                .putFloat(VideoRendererWorker.KEY_SOFTNESS, SOFTNESS)
                .putInt(VideoRendererWorker.KEY_QUALITY, quality)
                .putBoolean(VideoRendererWorker.KEY_MIRROR_SOURCE,
                        lensFacing == CameraSelector.LENS_FACING_FRONT)
                .putFloat(VideoRendererWorker.KEY_TRANSFORM_SCALE, liveScale)
                .putFloat(VideoRendererWorker.KEY_TRANSFORM_CENTER_X, liveCenterX)
                .putFloat(VideoRendererWorker.KEY_TRANSFORM_CENTER_Y, liveCenterY)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VideoRendererWorker.class)
                .setInputData(input)
                .build();
        WorkManager manager = WorkManager.getInstance(this);
        manager.enqueue(request);
        manager.getWorkInfoByIdLiveData(request.getId()).observe(this, info -> {
            if (info == null) return;
            if (info.getState() == WorkInfo.State.RUNNING) {
                int progress = info.getProgress().getInt(VideoRendererWorker.KEY_PROGRESS, 3);
                processingProgress.setProgress(Math.max(3, Math.min(97, progress)));
                processingText.setText("Deuxième rendu Fond vert · "
                        + Math.max(3, Math.min(97, progress)) + " %");
                return;
            }
            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                String path = info.getOutputData().getString(VideoRendererWorker.KEY_OUTPUT_FILE);
                if (path == null || path.trim().isEmpty()) {
                    buildRawFallback("Deuxième rendu terminé sans fichier");
                } else {
                    adoptSecondPassOutput(new File(path));
                }
                return;
            }
            if (info.getState() == WorkInfo.State.FAILED
                    || info.getState() == WorkInfo.State.CANCELLED) {
                String error = info.getOutputData().getString(VideoRendererWorker.KEY_ERROR);
                buildRawFallback("Deuxième rendu échoué : "
                        + (error == null ? "erreur inconnue" : error));
            }
        });
    }

    private void adoptSecondPassOutput(File rendered) {
        ioExecutor.execute(() -> {
            try {
                File durable = projectRepository.adoptOutput(rendered);
                boolean audioOk = MuxerUtils.hasAudioTrack(durable);
                runOnUiThread(() -> exposeReadyVideo(durable, true, audioOk,
                        rendererMessage));
            } catch (Exception error) {
                runOnUiThread(() -> buildRawFallback(
                        "Deuxième rendu illisible : " + safeMessage(error)));
            }
        });
    }

    private void buildRawFallback(String failure) {
        rendererMessage = failure == null ? "" : failure;
        showBusy(97, "Récupération de la vidéo caméra…");
        ioExecutor.execute(() -> {
            File fallback = new File(getCacheDir(),
                    "clip_fallback_" + System.currentTimeMillis() + ".mp4");
            try {
                FastRawClipAssembler.assemble(sourceTimeline, fallback);
            } catch (Exception error) {
                File first = firstRawPlan();
                if (first != null) {
                    try { MuxerUtils.copyVideoOnly(first, fallback); }
                    catch (Exception ignored) { }
                }
            }
            runOnUiThread(() -> {
                if (fallback.isFile() && fallback.length() > 0L) addAudioAndExpose(fallback, true);
                else {
                    showError("Aucune vidéo récupérable · projet conservé");
                    showIdle();
                }
            });
        });
    }

    private void addAudioAndExpose(File videoOnly, boolean fallbackUsed) {
        showBusy(98, "Ajout musique…");
        ioExecutor.execute(() -> {
            File output;
            try {
                output = projectRepository.createProjectFile("clip_ready", ".mp4");
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showError("Stockage du projet inaccessible · prises conservées");
                    showIdle();
                });
                return;
            }
            boolean audioOk = false;
            Exception audioError = null;
            try {
                MuxerUtils.addAudio(this, videoOnly,
                        preparedMusicUri != null ? preparedMusicUri : musicUri,
                        output, Math.max(1L, accumulatedMs) * 1000L,
                        Math.max(0L, clipAudioStartMs) * 1000L);
                audioOk = MuxerUtils.hasAudioTrack(output);
            } catch (Exception error) {
                audioError = error;
            }
            if (!audioOk) {
                try {
                    if (output.exists()) output.delete();
                    MuxerUtils.copyVideoOnly(videoOnly, output);
                } catch (Exception copyError) {
                    File first = firstRawPlan();
                    if (first != null) {
                        try { MuxerUtils.copyVideoOnly(first, output); }
                        catch (Exception ignored) { }
                    }
                }
            }
            boolean valid = false;
            try { valid = MuxerUtils.hasVideoTrack(output); } catch (Exception ignored) { }
            if (!valid) {
                runOnUiThread(() -> {
                    showError("Vidéo finale impossible");
                    showIdle();
                });
                return;
            }
            deleteFile(videoOnly);
            boolean finalAudioOk = audioOk;
            Exception finalAudioError = audioError;
            runOnUiThread(() -> {
                exposeReadyVideo(output, !fallbackUsed, finalAudioOk, rendererMessage);
                if (fallbackUsed) {
                    Toast.makeText(this,
                            "VIDÉO DE SECOURS : l'effet Fond vert n'a pas été appliqué",
                            Toast.LENGTH_LONG).show();
                }
                if (!finalAudioOk) {
                    Toast.makeText(this,
                            "Vidéo prête, mais musique non intégrée : "
                                    + (finalAudioError == null ? "format incompatible"
                                    : finalAudioError.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void exposeReadyVideo(File output, boolean effectApplied,
                                  boolean audioOk, String detail) {
        renderedClipFile = output;
        readyAudioApplied = audioOk;
        readyProjectStatus = effectApplied
                ? ProjectRepository.STATUS_READY_EFFECT : ProjectRepository.STATUS_READY_RAW;
        rendererMessage = detail == null ? "" : detail;
        checkpointProject(readyProjectStatus, rendererMessage);
        showReady(effectApplied, audioOk);
    }

    private File firstRawPlan() {
        for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
            File file = new File(segment.sourcePath);
            if (file.isFile() && file.length() > 0L) return file;
        }
        return null;
    }

    private void downloadVideo() {
        File source = renderedClipFile;
        if (source == null || !source.isFile()) {
            showError("Vidéo introuvable");
            return;
        }
        downloadButton.setEnabled(false);
        downloadButton.setText("Téléchargement…");
        ioExecutor.execute(() -> {
            try {
                Uri saved = MediaStoreSaver.saveVideo(this, source,
                        "ClipMusique_" + System.currentTimeMillis() + ".mp4");
                projectRepository.clear(true);
                runOnUiThread(() -> {
                    renderedClipFile = null;
                    sourceTimeline.clear();
                    downloadButton.setVisibility(View.GONE);
                    recordButton.setVisibility(View.VISIBLE);
                    setIdleControlsEnabled(true);
                    updateStartButton();
                    clipStatus.setText("Vidéo enregistrée dans la galerie");
                    Snackbar.make(recordButton, "Vidéo enregistrée", Snackbar.LENGTH_LONG)
                            .setAction("Ouvrir", v -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(saved, "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                                } catch (Exception ignored) { }
                            }).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadButton.setEnabled(true);
                    downloadButton.setText("↓ TÉLÉCHARGER LA VIDÉO");
                    showError("Téléchargement impossible");
                });
            }
        });
    }

    private void adjustMusicStart(int deltaMs) {
        if (!musicPrepared || musicPlayer == null || sessionActive) return;
        int max = Math.max(0, musicPlayer.getDuration() - 1);
        int next = Math.max(0, Math.min(max,
                musicSeekBar.getProgress() + deltaMs));
        musicSeekBar.setProgress(next);
        try { musicPlayer.seekTo(next); } catch (Exception ignored) { }
        clipStatus.setText("DÉPART AJUSTÉ · " + formatPrecise(next));
    }

    private void applyDetectedMusicStart() {
        if (!musicPrepared || musicPlayer == null || sessionActive) return;
        int max = Math.max(0, musicPlayer.getDuration() - 1);
        int next = Math.max(0, Math.min(max, detectedAudioStartMs));
        musicSeekBar.setProgress(next);
        try { musicPlayer.seekTo(next); } catch (Exception ignored) { }
        clipStatus.setText("DÉBUT AUTO · " + formatPrecise(next));
    }

    private void toggleMusic() {
        if (!musicPrepared || musicPlayer == null || sessionActive) return;
        try {
            if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
                musicPlayButton.setText("▶ ÉCOUTER");
            } else {
                musicPlayer.start();
                musicPlayButton.setText("Ⅱ PAUSE SON");
            }
        } catch (Exception ignored) { }
    }

    private void seekMusicAndPlay() {
        seekMusic(true);
    }

    private void seekMusic(boolean play) {
        if (!musicPrepared || musicPlayer == null) return;
        try {
            int target = (int) Math.min(Integer.MAX_VALUE,
                    (long) clipAudioStartMs + accumulatedMs);
            target = Math.min(target, Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(target);
            if (play) musicPlayer.start();
        } catch (Exception ignored) { }
    }

    private void pauseMusicAndBackground() {
        if (musicPlayer != null) {
            try { musicPlayer.pause(); } catch (Exception ignored) { }
        }
        try {
            if (backgroundVideo.isPlaying()) backgroundVideo.pause();
        } catch (Exception ignored) { }
    }

    private void restartBackgroundVideo() {
        if (backgroundSpec.getType() != BackgroundSpec.Type.VIDEO) return;
        try {
            backgroundVideo.seekTo(0);
            backgroundVideo.start();
        } catch (Exception ignored) { }
    }

    private boolean canChangeDecor() {
        return renderedClipFile == null && (!sessionActive || (paused && activeRecording == null));
    }

    private boolean canChangeMusic() {
        return renderedClipFile == null
                && (!sessionActive || (restoredProject && paused && activeRecording == null));
    }

    private void checkpointProject() {
        String status = renderedClipFile != null
                ? readyProjectStatus
                : (finishRequested ? ProjectRepository.STATUS_FINALIZING
                : ProjectRepository.STATUS_PAUSED);
        checkpointProject(status, rendererMessage);
    }

    private void checkpointProject(String status, String detail) {
        if (projectRepository == null
                || (sourceTimeline.isEmpty() && renderedClipFile == null)) {
            return;
        }
        ProjectRepository.Draft draft = new ProjectRepository.Draft();
        draft.format = horizontalFormat ? CaptureFormat.HORIZONTAL : CaptureFormat.VERTICAL;
        draft.musicUri = musicUri == null ? "" : musicUri.toString();
        draft.audioStartMs = Math.max(0, clipAudioStartMs);
        draft.detectedAudioStartMs = Math.max(0, detectedAudioStartMs);
        draft.quality = quality;
        draft.lensFacing = lensFacing;
        draft.backgroundType = backgroundSpec.getType().name();
        draft.backgroundUri = backgroundSpec.getUri() == null
                ? "" : backgroundSpec.getUri().toString();
        draft.backgroundColor = backgroundSpec.getColor();
        draft.backgroundLabel = currentDecorLabel;
        draft.subjectScale = liveScale;
        draft.subjectCenterX = liveCenterX;
        draft.subjectCenterY = liveCenterY;
        draft.status = status == null ? ProjectRepository.STATUS_PAUSED : status;
        draft.renderedPath = renderedClipFile == null
                ? "" : renderedClipFile.getAbsolutePath();
        draft.rendererMessage = detail == null ? "" : detail;
        draft.audioApplied = readyAudioApplied;
        for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
            draft.timeline.add(segment);
        }
        try {
            projectRepository.save(draft);
        } catch (Exception error) {
            if (!projectSaveWarningShown && !activityDestroyed) {
                projectSaveWarningShown = true;
                Toast.makeText(this,
                        "Sauvegarde automatique indisponible : " + safeMessage(error),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showGreen() {
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setVisibility(View.GONE);
        backgroundImage.setImageDrawable(null);
        previewContainer.setBackgroundColor(Color.rgb(0, 255, 0));
    }

    private void showImage(Uri uri) {
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        backgroundImage.setImageURI(uri);
        backgroundImage.setVisibility(View.VISIBLE);
    }

    private void showVideo(Uri uri) {
        backgroundImage.setImageDrawable(null);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        backgroundVideo.setVisibility(View.VISIBLE);
        try { backgroundVideo.stopPlayback(); } catch (Exception ignored) { }
        backgroundVideo.setVideoURI(uri);
        backgroundVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            player.setVolume(0f, 0f);
            if (!sessionActive || paused) backgroundVideo.start();
        });
    }

    private void showBusy(int progress, String text) {
        processingProgress.setProgress(progress);
        processingText.setText(text);
        processingOverlay.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
        finishButton.setEnabled(false);
        downloadButton.setEnabled(false);
    }

    private void showReady(boolean effectApplied, boolean audioOk) {
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText("↓ TÉLÉCHARGER LA VIDÉO");
        clipStatus.setText(effectApplied
                ? (audioOk
                ? "VIDÉO FOND VERT · effet et musique appliqués"
                : "VIDÉO FOND VERT · effet appliqué · musique absente")
                : (audioOk
                ? "VIDÉO DE SECOURS — EFFET NON APPLIQUÉ"
                : "VIDÉO DE SECOURS — SANS EFFET NI MUSIQUE"));
        timelineStatus.setText(sourceTimeline.size() + " plan(s) · vidéo prête et sauvegardée");
    }

    private void showIdle() {
        sessionActive = false;
        paused = false;
        finishRequested = false;
        stopRequested = false;
        realtimeCaptureActive = false;
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        recordButton.setVisibility(View.VISIBLE);
        setIdleControlsEnabled(true);
        updateStartButton();
    }

    private void updateStartButton() {
        if (renderedClipFile != null || activeRecording != null) return;
        if (sessionActive && paused) {
            boolean ready = cameraReady && musicPrepared
                    && preparedMusicUri != null && !audioPreparing;
            recordButton.setEnabled(ready);
            recordButton.setText(ready
                    ? "▶ REPRENDRE · " + format(accumulatedMs)
                    : (musicUri == null ? "♪ RÉIMPORTER LA MUSIQUE" : "PRÉPARATION DU PROJET…"));
            finishButton.setVisibility(View.VISIBLE);
            finishButton.setEnabled(!sourceTimeline.isEmpty());
            return;
        }
        if (sessionActive) return;
        if (!cameraReady) {
            recordButton.setEnabled(false);
            recordButton.setText("CAMÉRA…");
            clipStatus.setText("Préparation caméra…");
            return;
        }
        recordButton.setEnabled(!audioPreparing);
        if (preparedMusicUri == null) {
            recordButton.setText("♪ IMPORTE MUSIQUE");
            clipStatus.setText("MICRO COUPÉ · importe une musique pour tourner");
        } else {
            recordButton.setText("● TOURNER");
            clipStatus.setText("PRÊT · MICRO COUPÉ · audio importé uniquement");
        }
    }

    private void setControlsForRecording(boolean recording) {
        boolean idle = !recording;
        importImageButton.setEnabled(idle);
        importVideoButton.setEnabled(idle);
        greenButton.setEnabled(idle);
        importAudioButton.setEnabled(false);
        downloadsMusicButton.setEnabled(false);
        musicSeekBar.setEnabled(false);
        audioMinusButton.setEnabled(false);
        audioAutoButton.setEnabled(false);
        audioPlusButton.setEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
    }

    private void setIdleControlsEnabled(boolean enabled) {
        importImageButton.setEnabled(enabled);
        importVideoButton.setEnabled(enabled);
        greenButton.setEnabled(enabled);
        importAudioButton.setEnabled(enabled);
        downloadsMusicButton.setEnabled(enabled);
        musicSeekBar.setEnabled(enabled && musicPrepared);
        musicPlayButton.setEnabled(enabled && musicPrepared);
        boolean audioReady = enabled && musicPrepared
                && preparedMusicUri != null && !audioPreparing;
        audioMinusButton.setEnabled(audioReady);
        audioAutoButton.setEnabled(audioReady);
        audioPlusButton.setEnabled(audioReady);
        flipCameraButton.setEnabled(enabled);
        qualityButton.setEnabled(enabled);
    }

    private long currentDurationMs() {
        if (activeRecording == null || segmentStartedAt <= 0L || stopRequested) return accumulatedMs;
        return accumulatedMs + Math.max(0L, SystemClock.elapsedRealtime() - segmentStartedAt);
    }

    private long videoDuration(File file) {
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

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "Fichier sélectionné";
    }

    private void persist(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private void updateMusicPosition(int positionMs) {
        int duration = musicPrepared && musicPlayer != null ? musicPlayer.getDuration() : 0;
        musicPosition.setText(format(positionMs) + " / " + format(duration));
    }

    private void releaseMusic() {
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

    private void cleanupSessionFiles() {
        if (renderedClipFile != null) deleteFile(renderedClipFile);
        renderedClipFile = null;
        deleteRawPlans();
        if (currentSegmentFile != null) deleteFile(currentSegmentFile);
        currentSegmentFile = null;
        if (realtimeRecorder != null) {
            realtimeRecorder.close();
            realtimeRecorder = null;
        }
    }

    private void deleteRawPlans() {
        for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
            deleteFile(new File(segment.sourcePath));
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null
                || error.getMessage().trim().isEmpty()) {
            return "erreur inconnue";
        }
        return error.getMessage().trim();
    }

    private void showError(String message) {
        processingOverlay.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String format(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String formatPrecise(long ms) {
        long safe = Math.max(0L, ms);
        long minutes = safe / 60_000L;
        long seconds = (safe / 1000L) % 60L;
        long tenths = (safe % 1000L) / 100L;
        return String.format(Locale.FRANCE, "%02d:%02d.%d",
                minutes, seconds, tenths);
    }

    @Override
    protected void onPause() {
        if (sessionActive && activeRecording != null && !stopRequested) pausePlan(true);
        pauseMusicAndBackground();
        checkpointProject();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lifecycleInterrupted && sessionActive && paused && activeRecording == null) {
            clipStatus.setText("ENREGISTREMENT INTERROMPU · projet sauvegardé · REPRENDRE");
            updateStartButton();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        ui.removeCallbacks(timerTick);
        realtimeCaptureActive = false;
        checkpointProject();
        if (activeRecording != null) {
            try { activeRecording.stop(); } catch (Exception ignored) { }
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (realtimeRecorder != null) realtimeRecorder.close();
        cameraExecutor.shutdownNow();
        ioExecutor.shutdown();
        if (segmenter != null) segmenter.close();
        if (subjectPreview != null) subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) currentSource.recycle();
        releaseMusic();
        deletePreparedAudio();
        super.onDestroy();
    }
}
