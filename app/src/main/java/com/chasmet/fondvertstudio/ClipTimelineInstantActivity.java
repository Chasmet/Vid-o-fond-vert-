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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clip Musique instantané : le rendu détouré est calculé pendant la prise.
 * La fin du montage ne fait plus de segmentation frame par frame.
 */
public final class ClipTimelineInstantActivity extends AppCompatActivity {
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
        super.onCreate(state);
        setContentView(R.layout.activity_clip_timeline);
        bindViews();
        registerPickers();
        setupControls();
        backgroundSpec.setColor(Color.rgb(0, 255, 0));
        showGreen();
        segmenter = new SegmentationEngine(this);
        segmenter.setEdgeSettings(THRESHOLD, SOFTNESS);
        requestCamera();
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
        });
        videoPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || !canChangeDecor()) return;
            persist(uri);
            backgroundSpec.setVideo(uri);
            currentDecorLabel = "Vidéo · " + displayName(uri);
            decorStatus.setText(currentDecorLabel);
            showVideo(uri);
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
            if (!sessionActive && renderedClipFile == null) audioPicker.launch(new String[]{"audio/*"});
        });
        downloadsMusicButton.setOnClickListener(v -> {
            if (!sessionActive && renderedClipFile == null) audioPicker.launch(new String[]{"audio/*"});
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
        });
        musicPlayButton.setOnClickListener(v -> toggleMusic());
        recordButton.setOnClickListener(v -> {
            if (!sessionActive) startSession();
            else if (activeRecording != null) pausePlan();
            else if (paused) resumePlan();
        });
        recordButton.setText("CAMÉRA…");
        recordButton.setEnabled(false);
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
                clipStatus.setText("Départ musique · " + format(bar.getProgress()));
            }
        });
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
        Recorder recorder = new Recorder.Builder().setQualitySelector(
                QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build();
        videoCapture = VideoCapture.withOutput(recorder);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(quality == 1080 ? new Size(720, 1280) : new Size(540, 960))
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
                runOnUiThread(() -> {
                    preparedMusicUri = prepared;
                    preparedAudioFile = generated ? destination : null;
                    if (!generated && destination.exists()) destination.delete();
                    audioPreparing = false;
                    clipStatus.setText("Musique prête · MICRO COUPÉ · audio importé uniquement");
                    updateStartButton();
                });
            } catch (Exception error) {
                if (destination.exists()) destination.delete();
                runOnUiThread(() -> {
                    audioPreparing = false;
                    preparedMusicUri = null;
                    updateStartButton();
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
        cleanupSessionFiles();
        sourceTimeline.clear();
        accumulatedMs = 0L;
        planNumber = 1;
        finishRequested = false;
        paused = false;
        sessionActive = true;
        try {
            realtimeRecorder = new RealtimeClipRecorder(this,
                    quality == 1080 ? 1080 : 720,
                    quality == 1080 ? 1920 : 1280,
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

    private void pausePlan() {
        if (activeRecording == null || stopRequested) return;
        stopRequested = true;
        realtimeCaptureActive = false;
        pauseMusicAndBackground();
        recordButton.setEnabled(false);
        recordButton.setText("Pause…");
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
        if (finishRequested) {
            finalizeSession();
        } else {
            setControlsForRecording(false);
            recordButton.setEnabled(true);
            recordButton.setText("▶ REPRENDRE · " + format(accumulatedMs));
            finishButton.setVisibility(View.VISIBLE);
            clipStatus.setText("PAUSE · change le décor puis REPRENDRE");
        }
    }

    private void resumePlan() {
        if (!sessionActive || !paused || finishRequested) return;
        paused = false;
        startPlan();
    }

    private void finishSession() {
        if (!sessionActive || finishRequested) return;
        finishRequested = true;
        realtimeCaptureActive = false;
        pauseMusicAndBackground();
        finishButton.setEnabled(false);
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
        if (recorder == null) {
            buildRawFallback();
            return;
        }
        recorder.finish((videoOnly, error) -> {
            if (videoOnly != null && videoOnly.isFile() && videoOnly.length() > 0L) {
                addAudioAndExpose(videoOnly, false);
            } else {
                buildRawFallback();
            }
        });
    }

    private void buildRawFallback() {
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
                    showError("Aucune vidéo récupérable");
                    showIdle();
                }
            });
        });
    }

    private void addAudioAndExpose(File videoOnly, boolean fallbackUsed) {
        showBusy(98, "Ajout musique…");
        ioExecutor.execute(() -> {
            File output = new File(getCacheDir(),
                    "clip_ready_" + System.currentTimeMillis() + ".mp4");
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
            deleteRawPlans();
            boolean finalAudioOk = audioOk;
            Exception finalAudioError = audioError;
            runOnUiThread(() -> {
                renderedClipFile = output;
                showReady();
                if (fallbackUsed) {
                    Toast.makeText(this, "Mode secours vidéo utilisé", Toast.LENGTH_LONG).show();
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
                deleteFile(source);
                runOnUiThread(() -> {
                    renderedClipFile = null;
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

    private void showReady() {
        processingOverlay.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        downloadButton.setEnabled(true);
        downloadButton.setText("↓ TÉLÉCHARGER LA VIDÉO");
        clipStatus.setText("VIDÉO PRÊTE");
        timelineStatus.setText(sourceTimeline.size() + " plan(s) · vidéo prête");
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
        if (sessionActive || activeRecording != null || renderedClipFile != null) return;
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

    private void showError(String message) {
        processingOverlay.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String format(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    @Override
    protected void onPause() {
        if (sessionActive && activeRecording != null && !stopRequested) pausePlan();
        pauseMusicAndBackground();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(timerTick);
        realtimeCaptureActive = false;
        if (activeRecording != null) {
            try { activeRecording.stop(); } catch (Exception ignored) { }
            activeRecording = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (realtimeRecorder != null) realtimeRecorder.close();
        cameraExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (segmenter != null) segmenter.close();
        subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) currentSource.recycle();
        releaseMusic();
        deletePreparedAudio();
        cleanupSessionFiles();
        super.onDestroy();
    }
}
