package com.chasmet.fondvertstudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One simple flow: choose an untouched background, film the camera, export.
 */
public final class MainActivity extends AppCompatActivity {
    private MaskedCameraView subjectPreview;
    private ImageView backgroundImage;
    private VideoView backgroundVideo;
    private View previewContainer;
    private TextView previewHint;
    private TextView backgroundStatus;
    private TextView recordingTimer;
    private TextView transformHint;
    private LinearLayout processingOverlay;
    private ProgressBar processingProgress;
    private TextView processingText;
    private MaterialButton recordButton;
    private MaterialButton flipCameraButton;
    private MaterialButton qualityButton;
    private MaterialButton maskModeButton;
    private MaterialButton resetSubjectButton;
    private MaterialButton[] backgroundButtons;

    private final BackgroundSpec backgroundSpec = new BackgroundSpec();
    private int quality = 1080;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private int maskPreset;
    private float threshold = 0.50f;
    private float softness = 0.065f;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File activeRawFile;

    private SegmentationEngine segmenter;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final AtomicInteger edgeGeneration = new AtomicInteger();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private long recordingStartedAt;
    private long lastTransformSampleAt;
    private boolean recordingTransformActive;
    private final SubjectTransformTimeline transformTimeline =
            new SubjectTransformTimeline();
    private static final long TRANSFORM_SAMPLE_INTERVAL_MS = 34L;

    private Bitmap currentSource;
    private Bitmap backgroundPreviewBitmap;
    private float[] currentMask;
    private int currentMaskWidth;
    private int currentMaskHeight;

    private ActivityResultLauncher<String[]> backgroundImagePicker;
    private ActivityResultLauncher<String[]> backgroundVideoPicker;
    private ActivityResultLauncher<String[]> permissionLauncher;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (activeRecording == null) {
                return;
            }
            String elapsed = formatDuration(SystemClock.elapsedRealtime() - recordingStartedAt);
            recordingTimer.setText(elapsed);
            recordButton.setText("■  ARRÊTER   " + elapsed);
            uiHandler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        registerLaunchers();
        setupControls();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showUnsupportedDialog();
            return;
        }
        segmenter = new SegmentationEngine(this);
        segmenter.setEdgeSettings(threshold, softness);
        requestCameraPermissionsIfNeeded();
    }

    private void bindViews() {
        subjectPreview = findViewById(R.id.subjectPreview);
        backgroundImage = findViewById(R.id.backgroundImage);
        backgroundVideo = findViewById(R.id.backgroundVideo);
        previewContainer = findViewById(R.id.previewContainer);
        previewHint = findViewById(R.id.previewHint);
        backgroundStatus = findViewById(R.id.backgroundStatus);
        recordingTimer = findViewById(R.id.recordingTimer);
        transformHint = findViewById(R.id.transformHint);
        processingOverlay = findViewById(R.id.processingOverlay);
        processingProgress = findViewById(R.id.processingProgress);
        processingText = findViewById(R.id.processingText);
        recordButton = findViewById(R.id.recordButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        qualityButton = findViewById(R.id.qualityButton);
        maskModeButton = findViewById(R.id.maskModeButton);
        resetSubjectButton = findViewById(R.id.resetSubjectButton);
    }

    private void registerLaunchers() {
        backgroundImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    persistReadPermission(uri);
                    backgroundSpec.setImage(uri);
                    showImageBackground(uri);
                    highlightBackgroundButton(R.id.bgImage);
                    backgroundStatus.setText("DÉCOR · IMAGE INTACTE");
                    Toast.makeText(this, "L’image reste intacte derrière toi",
                            Toast.LENGTH_SHORT).show();
                });
        backgroundVideoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    persistReadPermission(uri);
                    backgroundSpec.setVideo(uri);
                    showVideoBackground(uri);
                    highlightBackgroundButton(R.id.bgVideo);
                    backgroundStatus.setText("DÉCOR · VIDÉO INTACTE");
                    Toast.makeText(this, "La vidéo reste intacte derrière toi",
                            Toast.LENGTH_SHORT).show();
                });
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);
    }

    private void setupControls() {
        MaterialButton imageButton = findViewById(R.id.bgImage);
        MaterialButton videoButton = findViewById(R.id.bgVideo);
        MaterialButton transparentButton = findViewById(R.id.bgTransparent);
        MaterialButton greenButton = findViewById(R.id.bgGreen);
        MaterialButton blackButton = findViewById(R.id.bgBlack);
        MaterialButton whiteButton = findViewById(R.id.bgWhite);
        backgroundButtons = new MaterialButton[]{imageButton, videoButton,
                transparentButton, greenButton, blackButton, whiteButton};

        imageButton.setOnClickListener(v ->
                backgroundImagePicker.launch(new String[]{"image/*"}));
        videoButton.setOnClickListener(v ->
                backgroundVideoPicker.launch(new String[]{"video/*"}));
        transparentButton.setOnClickListener(v -> {
            backgroundSpec.setTransparent();
            showTransparentBackground();
            highlightBackgroundButton(R.id.bgTransparent);
            backgroundStatus.setText("DÉCOR · SANS FOND");
        });
        greenButton.setOnClickListener(v -> selectColorBackground(
                R.id.bgGreen, Color.rgb(0, 255, 0), "DÉCOR · VERT"));
        blackButton.setOnClickListener(v -> selectColorBackground(
                R.id.bgBlack, Color.BLACK, "DÉCOR · NOIR"));
        whiteButton.setOnClickListener(v -> selectColorBackground(
                R.id.bgWhite, Color.WHITE, "DÉCOR · BLANC"));

        recordButton.setOnClickListener(v -> {
            if (activeRecording == null) {
                startRecording();
            } else {
                stopRecording();
            }
        });
        flipCameraButton.setOnClickListener(v -> {
            if (activeRecording != null) {
                return;
            }
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        qualityButton.setOnClickListener(v -> {
            if (activeRecording != null) {
                return;
            }
            quality = quality == 720 ? 1080 : 720;
            qualityButton.setText("REC " + quality + "p");
            startCamera();
            Toast.makeText(this, "Enregistrement en " + quality
                            + "p · aperçu fluide optimisé",
                    Toast.LENGTH_SHORT).show();
        });
        maskModeButton.setOnClickListener(v -> selectNextMaskPreset());
        resetSubjectButton.setOnClickListener(v -> subjectPreview.resetSubjectTransform());
        subjectPreview.setTransformListener((scale, centerX, centerY, gestureFinished) -> {
            int percent = Math.round(scale * 100f);
            resetSubjectButton.setText("↺ " + percent + " %");
            transformHint.setText(gestureFinished
                    ? "Glisse pour déplacer · pince pour zoomer"
                    : "SUJET · " + percent + " %");
            recordSubjectTransform(gestureFinished);
        });

        backgroundSpec.setColor(Color.rgb(0, 255, 0));
        showColorBackground(Color.rgb(0, 255, 0));
        highlightBackgroundButton(R.id.bgGreen);
    }

    private void selectColorBackground(int buttonId, int color, String label) {
        backgroundSpec.setColor(color);
        showColorBackground(color);
        highlightBackgroundButton(buttonId);
        backgroundStatus.setText(label);
    }

    private void selectNextMaskPreset() {
        maskPreset = (maskPreset + 1) % 3;
        if (maskPreset == 1) {
            threshold = 0.42f;
            softness = 0.13f;
            maskModeButton.setText("Contour · Cheveux");
        } else if (maskPreset == 2) {
            threshold = 0.48f;
            softness = 0.10f;
            maskModeButton.setText("Contour · Doux");
        } else {
            threshold = 0.50f;
            softness = 0.065f;
            maskModeButton.setText("Contour · HD net");
        }
        if (segmenter != null) {
            segmenter.setEdgeSettings(threshold, softness);
        }
        refreshCurrentMask();
    }

    private void requestCameraPermissionsIfNeeded() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted) {
            startCamera();
        }
        if (!cameraGranted || !audioGranted) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA))
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted) {
            startCamera();
        } else {
            Toast.makeText(this, "La caméra est nécessaire pour te filmer",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        if (segmenter == null || activeRecording != null
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
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
        if (cameraProvider == null || activeRecording != null) {
            return;
        }
        cameraProvider.unbindAll();
        segmenter.resetStreamHistory();
        Quality preferred = quality == 1080 ? Quality.FHD : Quality.HD;
        QualitySelector selector = QualitySelector.from(preferred,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
        Recorder recorder = new Recorder.Builder().setQualitySelector(selector).build();
        videoCapture = VideoCapture.withOutput(recorder);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();
        try {
            // Le VideoCapture reste en 1080p. Ce flux léger sert uniquement à
            // l'aperçu et au masque : le visage continue donc de bouger même
            // pendant qu'une inférence IA est encore en cours.
            ImageAnalysis analysis = createImageAnalysis(new Size(480, 854));
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis, videoCapture);
        } catch (Exception error) {
            showError("Cette caméra ne permet pas le mode vidéo sélectionné");
        }
    }

    private ImageAnalysis createImageAnalysis(Size targetSize) {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeCameraFrame);
        return analysis;
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
            if (inference == bitmap) {
                inference = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        Bitmap displayFrame = bitmap;
        uiHandler.post(() -> acceptSourceFrame(displayFrame));
        if (inference == null) {
            return;
        }
        segmenter.processStream(inference, new SegmentationEngine.Callback() {
            @Override
            public void onResult(SegmentationEngine.Result result) {
                acceptMaskResult(result);
            }

            @Override
            public void onError(Exception error) {
                // A dropped frame is expected on slower phones; the next frame replaces it.
            }
        });
    }

    private void startRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "Caméra en préparation…", Toast.LENGTH_SHORT).show();
            return;
        }
        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) {
            directory = getCacheDir();
        }
        activeRawFile = new File(directory,
                "camera_source_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(activeRawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO) {
            backgroundVideo.seekTo(0);
            backgroundVideo.start();
        }
        recordButton.setEnabled(false);
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    showRecordingUi();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                    File transformFile = finishSubjectTransformTimeline();
                    activeRecording = null;
                    showIdleUi();
                    if (finalized.getError() == VideoRecordEvent.Finalize.ERROR_NONE
                            && activeRawFile != null && activeRawFile.exists()) {
                        enqueueVideoExport(Uri.fromFile(activeRawFile), transformFile);
                    } else {
                        deleteFile(transformFile);
                        showError("Enregistrement interrompu");
                    }
                }
            });
        } catch (Exception error) {
            activeRecording = null;
            showIdleUi();
            showError("Impossible de démarrer l’enregistrement");
        }
    }

    private void stopRecording() {
        if (activeRecording == null) {
            return;
        }
        recordButton.setEnabled(false);
        recordButton.setText("Finalisation…");
        recordSubjectTransform(true);
        activeRecording.stop();
    }

    private void showRecordingUi() {
        recordingStartedAt = SystemClock.elapsedRealtime();
        beginSubjectTransformTimeline();
        recordingTimer.setText("00:00");
        recordingTimer.setVisibility(View.VISIBLE);
        recordButton.setText("■  ARRÊTER   00:00");
        recordButton.setEnabled(true);
        recordButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.danger)));
        backgroundStatus.setText("● ENREGISTREMENT");
        setControlsEnabled(false);
        uiHandler.removeCallbacks(timerTick);
        uiHandler.post(timerTick);
    }

    private void showIdleUi() {
        uiHandler.removeCallbacks(timerTick);
        recordingTimer.setVisibility(View.GONE);
        recordButton.setText("●  ENREGISTRER");
        recordButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.danger)));
        recordButton.setEnabled(true);
        setControlsEnabled(true);
        updateBackgroundStatus();
    }

    private void enqueueVideoExport(Uri sourceUri, File transformFile) {
        if (backgroundSpec.getType() == BackgroundSpec.Type.TRANSPARENT) {
            Toast.makeText(this,
                    "Sans décor, le MP4 utilise un vert pur compatible CapCut",
                    Toast.LENGTH_LONG).show();
        }
        Data.Builder input = new Data.Builder()
                .putString(VideoExportWorker.KEY_SOURCE_URI, sourceUri.toString())
                .putString(VideoExportWorker.KEY_BACKGROUND_TYPE,
                        backgroundSpec.getType().name())
                .putInt(VideoExportWorker.KEY_BACKGROUND_COLOR, backgroundSpec.getColor())
                .putFloat(VideoExportWorker.KEY_THRESHOLD, threshold)
                .putFloat(VideoExportWorker.KEY_SOFTNESS, softness)
                .putInt(VideoExportWorker.KEY_QUALITY, quality)
                .putBoolean(VideoExportWorker.KEY_MIRROR_SOURCE,
                        lensFacing == CameraSelector.LENS_FACING_FRONT)
                .putFloat(VideoExportWorker.KEY_TRANSFORM_SCALE,
                        subjectPreview.getSubjectScale())
                .putFloat(VideoExportWorker.KEY_TRANSFORM_CENTER_X,
                        subjectPreview.getSubjectCenterX())
                .putFloat(VideoExportWorker.KEY_TRANSFORM_CENTER_Y,
                        subjectPreview.getSubjectCenterY());
        if (transformFile != null) {
            input.putString(VideoExportWorker.KEY_TRANSFORM_PATH,
                    transformFile.getAbsolutePath());
        }
        if (backgroundSpec.getUri() != null) {
            input.putString(VideoExportWorker.KEY_BACKGROUND_URI,
                    backgroundSpec.getUri().toString());
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VideoExportWorker.class)
                .setInputData(input.build())
                .build();
        WorkManager.getInstance(this).enqueue(request);
        observeExport(request);
    }

    private void beginSubjectTransformTimeline() {
        transformTimeline.clear();
        recordingTransformActive = true;
        lastTransformSampleAt = Long.MIN_VALUE;
        transformTimeline.add(0L, subjectPreview.getSubjectScale(),
                subjectPreview.getSubjectCenterX(), subjectPreview.getSubjectCenterY());
        lastTransformSampleAt = recordingStartedAt;
    }

    private void recordSubjectTransform(boolean force) {
        if (!recordingTransformActive || recordingStartedAt <= 0L) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastTransformSampleAt < TRANSFORM_SAMPLE_INTERVAL_MS) {
            return;
        }
        long elapsedUs = Math.max(0L, now - recordingStartedAt) * 1000L;
        transformTimeline.add(elapsedUs, subjectPreview.getSubjectScale(),
                subjectPreview.getSubjectCenterX(), subjectPreview.getSubjectCenterY());
        lastTransformSampleAt = now;
    }

    private File finishSubjectTransformTimeline() {
        if (!recordingTransformActive) {
            return null;
        }
        recordSubjectTransform(true);
        recordingTransformActive = false;
        File directory = new File(getCacheDir(), "subject_transforms");
        File file = new File(directory,
                "movement_" + System.currentTimeMillis() + ".csv");
        try {
            transformTimeline.write(file);
            return file;
        } catch (Exception error) {
            deleteFile(file);
            return null;
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void observeExport(OneTimeWorkRequest request) {
        showBusy(0, "Création de ta vidéo…");
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.getId())
                .observe(this, info -> {
                    if (info == null) {
                        return;
                    }
                    int progress = info.getProgress().getInt(
                            VideoExportWorker.KEY_PROGRESS, 0);
                    processingProgress.setProgress(progress);
                    processingText.setText("Détourage de la caméra… " + progress + "%");
                    if (info.getState() == WorkInfo.State.SUCCEEDED) {
                        hideBusy();
                        String output = info.getOutputData().getString(
                                VideoExportWorker.KEY_OUTPUT_URI);
                        deleteRawRecording();
                        if (output != null) {
                            showSavedSnackbar(Uri.parse(output));
                        }
                    } else if (info.getState() == WorkInfo.State.FAILED
                            || info.getState() == WorkInfo.State.CANCELLED) {
                        String error = info.getOutputData().getString(VideoExportWorker.KEY_ERROR);
                        deleteRawRecording();
                        showError(error == null ? "Échec de l’export" : error);
                    }
                });
    }

    private void acceptSourceFrame(Bitmap source) {
        Bitmap oldSource = currentSource;
        currentSource = source;
        subjectPreview.setSource(source);
        previewHint.setVisibility(View.GONE);
        if (oldSource != null && oldSource != source && !oldSource.isRecycled()) {
            oldSource.recycle();
        }
    }

    private void acceptMaskResult(SegmentationEngine.Result result) {
        edgeGeneration.incrementAndGet();
        currentMask = result.mask;
        currentMaskWidth = result.maskWidth;
        currentMaskHeight = result.maskHeight;
        subjectPreview.setMask(result.alphaMask, currentMask, currentMaskWidth,
                currentMaskHeight, threshold, softness);
        if (result.source != null && !result.source.isRecycled()) result.source.recycle();
        if (result.cutout != null && !result.cutout.isRecycled()) result.cutout.recycle();
    }

    private void refreshCurrentMask() {
        edgeGeneration.incrementAndGet();
        subjectPreview.updateEdgeSettings(threshold, softness);
    }

    private void showTransparentBackground() {
        backgroundVideo.stopPlayback();
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackground(new CheckerboardDrawable(dp(18)));
    }

    private void showColorBackground(int color) {
        backgroundVideo.stopPlayback();
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(color);
    }

    private void showImageBackground(Uri uri) {
        backgroundVideo.stopPlayback();
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        ioExecutor.execute(() -> {
            try {
                Bitmap bitmap = BitmapUtils.decodeUri(this, uri, 2160);
                runOnUiThread(() -> {
                    Bitmap old = backgroundPreviewBitmap;
                    backgroundPreviewBitmap = bitmap;
                    backgroundImage.setImageBitmap(bitmap);
                    backgroundImage.setVisibility(View.VISIBLE);
                    if (old != null && !old.isRecycled()) {
                        old.recycle();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError("Image de fond illisible"));
            }
        });
    }

    private void showVideoBackground(Uri uri) {
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.BLACK);
        backgroundVideo.setVisibility(View.VISIBLE);
        backgroundVideo.setVideoURI(uri);
        backgroundVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            player.setVolume(0f, 0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
            backgroundVideo.start();
        });
    }

    private void highlightBackgroundButton(int selectedId) {
        for (MaterialButton button : backgroundButtons) {
            boolean selected = button.getId() == selectedId;
            button.setStrokeWidth(dp(selected ? 2 : 1));
            button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this,
                    selected ? R.color.accent : R.color.surface_high)));
            button.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.accent : R.color.ink));
        }
    }

    private void updateBackgroundStatus() {
        switch (backgroundSpec.getType()) {
            case IMAGE:
                backgroundStatus.setText("DÉCOR · IMAGE INTACTE");
                break;
            case VIDEO:
                backgroundStatus.setText("DÉCOR · VIDÉO INTACTE");
                break;
            case TRANSPARENT:
                backgroundStatus.setText("DÉCOR · SANS FOND");
                break;
            case COLOR:
            default:
                int color = backgroundSpec.getColor();
                backgroundStatus.setText(color == Color.BLACK ? "DÉCOR · NOIR"
                        : color == Color.WHITE ? "DÉCOR · BLANC" : "DÉCOR · VERT");
                break;
        }
    }

    private void setControlsEnabled(boolean enabled) {
        for (MaterialButton button : backgroundButtons) {
            button.setEnabled(enabled);
        }
        flipCameraButton.setEnabled(enabled);
        qualityButton.setEnabled(enabled);
        maskModeButton.setEnabled(enabled);
    }

    private void showBusy(int progress, String text) {
        processingProgress.setProgress(progress);
        processingText.setText(text);
        processingOverlay.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
        setControlsEnabled(false);
    }

    private void hideBusy() {
        processingOverlay.setVisibility(View.GONE);
        recordButton.setEnabled(true);
        setControlsEnabled(true);
    }

    private void showError(String message) {
        hideBusy();
        Toast.makeText(this, message == null ? "Une erreur est survenue" : message,
                Toast.LENGTH_LONG).show();
    }

    private void showSavedSnackbar(Uri uri) {
        Snackbar.make(recordButton, "Vidéo enregistrée dans la galerie",
                        Snackbar.LENGTH_LONG)
                .setAction("Ouvrir", v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(this, "Vidéo : " + uri, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void deleteRawRecording() {
        if (activeRawFile != null && activeRawFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            activeRawFile.delete();
        }
        activeRawFile = null;
    }

    private void clearSubjectPreview() {
        subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) {
            currentSource.recycle();
        }
        currentSource = null;
        currentMask = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    private void showUnsupportedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Android trop ancien")
                .setMessage("Le détourage IA demande Android 6.0 ou plus récent.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            backgroundVideo.start();
        }
    }

    @Override
    protected void onPause() {
        if (backgroundVideo.isPlaying()) {
            backgroundVideo.pause();
        }
        if (activeRecording != null) {
            stopRecording();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(timerTick);
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (segmenter != null) {
            segmenter.close();
        }
        clearSubjectPreview();
        if (backgroundPreviewBitmap != null && !backgroundPreviewBitmap.isRecycled()) {
            backgroundPreviewBitmap.recycle();
        }
        super.onDestroy();
    }
}
