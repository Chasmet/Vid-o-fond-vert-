package com.chasmet.fondvertstudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends AppCompatActivity {
    private enum SourceMode {
        CAMERA,
        VIDEO,
        PHOTO
    }

    private ImageView subjectPreview;
    private ImageView backgroundImage;
    private VideoView backgroundVideo;
    private View previewContainer;
    private TextView previewHint;
    private LinearLayout processingOverlay;
    private ProgressBar processingProgress;
    private TextView processingText;
    private MaterialButton recordButton;
    private MaterialButton importButton;
    private MaterialButton exportButton;
    private MaterialButton flipCameraButton;
    private MaterialButton qualityButton;
    private Slider thresholdSlider;
    private Slider softnessSlider;

    private final BackgroundSpec backgroundSpec = new BackgroundSpec();
    private SourceMode sourceMode = SourceMode.CAMERA;
    private Uri foregroundUri;
    private Uri pendingBackgroundUri;
    private int quality = 720;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File activeRawFile;

    private SegmentationEngine segmenter;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final AtomicInteger edgeGeneration = new AtomicInteger();
    private Bitmap currentSource;
    private Bitmap currentCutout;
    private Bitmap backgroundPreviewBitmap;
    private float[] currentMask;
    private int currentMaskWidth;
    private int currentMaskHeight;

    private ActivityResultLauncher<String[]> foregroundPicker;
    private ActivityResultLauncher<String[]> backgroundImagePicker;
    private ActivityResultLauncher<String[]> backgroundVideoPicker;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        registerLaunchers();
        setupControls();
        previewContainer.setBackground(new CheckerboardDrawable(dp(18)));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showUnsupportedDialog();
            return;
        }
        segmenter = new SegmentationEngine(this);
        requestCameraPermissionsIfNeeded();
    }

    private void bindViews() {
        subjectPreview = findViewById(R.id.subjectPreview);
        backgroundImage = findViewById(R.id.backgroundImage);
        backgroundVideo = findViewById(R.id.backgroundVideo);
        previewContainer = findViewById(R.id.previewContainer);
        previewHint = findViewById(R.id.previewHint);
        processingOverlay = findViewById(R.id.processingOverlay);
        processingProgress = findViewById(R.id.processingProgress);
        processingText = findViewById(R.id.processingText);
        recordButton = findViewById(R.id.recordButton);
        importButton = findViewById(R.id.importButton);
        exportButton = findViewById(R.id.exportButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        qualityButton = findViewById(R.id.qualityButton);
        thresholdSlider = findViewById(R.id.thresholdSlider);
        softnessSlider = findViewById(R.id.softnessSlider);
    }

    private void registerLaunchers() {
        foregroundPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onForegroundSelected);
        backgroundImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        persistReadPermission(uri);
                        pendingBackgroundUri = uri;
                        backgroundSpec.setImage(uri);
                        showImageBackground(uri);
                        highlightBackgroundButton(R.id.bgImage);
                    }
                });
        backgroundVideoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        persistReadPermission(uri);
                        pendingBackgroundUri = uri;
                        backgroundSpec.setVideo(uri);
                        showVideoBackground(uri);
                        highlightBackgroundButton(R.id.bgVideo);
                    }
                });
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);
    }

    private void setupControls() {
        MaterialButtonToggleGroup modeGroup = findViewById(R.id.modeGroup);
        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.modeCamera) {
                setSourceMode(SourceMode.CAMERA);
            } else if (checkedId == R.id.modeVideo) {
                setSourceMode(SourceMode.VIDEO);
            } else if (checkedId == R.id.modePhoto) {
                setSourceMode(SourceMode.PHOTO);
            }
        });

        findViewById(R.id.bgTransparent).setOnClickListener(v -> {
            backgroundSpec.setTransparent();
            showTransparentBackground();
            highlightBackgroundButton(R.id.bgTransparent);
        });
        findViewById(R.id.bgGreen).setOnClickListener(v -> {
            backgroundSpec.setColor(Color.rgb(0, 255, 0));
            showColorBackground(Color.rgb(0, 255, 0));
            highlightBackgroundButton(R.id.bgGreen);
        });
        findViewById(R.id.bgBlack).setOnClickListener(v -> {
            backgroundSpec.setColor(Color.BLACK);
            showColorBackground(Color.BLACK);
            highlightBackgroundButton(R.id.bgBlack);
        });
        findViewById(R.id.bgWhite).setOnClickListener(v -> {
            backgroundSpec.setColor(Color.WHITE);
            showColorBackground(Color.WHITE);
            highlightBackgroundButton(R.id.bgWhite);
        });
        findViewById(R.id.bgImage).setOnClickListener(v ->
                backgroundImagePicker.launch(new String[]{"image/*"}));
        findViewById(R.id.bgVideo).setOnClickListener(v ->
                backgroundVideoPicker.launch(new String[]{"video/*"}));

        thresholdSlider.addOnChangeListener((slider, value, fromUser) -> updateEdgeSettings());
        softnessSlider.addOnChangeListener((slider, value, fromUser) -> updateEdgeSettings());
        importButton.setOnClickListener(v -> openForegroundPicker());
        exportButton.setOnClickListener(v -> exportCurrentMedia());
        recordButton.setOnClickListener(v -> onCenterAction());
        flipCameraButton.setOnClickListener(v -> {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        qualityButton.setOnClickListener(v -> {
            quality = quality == 720 ? 1080 : 720;
            qualityButton.setText(quality + "p");
            Toast.makeText(this, "Export " + quality + "p", Toast.LENGTH_SHORT).show();
        });
        highlightBackgroundButton(R.id.bgTransparent);
    }

    private void setSourceMode(SourceMode mode) {
        if (sourceMode == mode) {
            return;
        }
        sourceMode = mode;
        foregroundUri = null;
        clearSubjectPreview();
        if (mode == SourceMode.CAMERA) {
            flipCameraButton.setVisibility(View.VISIBLE);
            recordButton.setText("●");
            startCamera();
        } else {
            flipCameraButton.setVisibility(View.GONE);
            recordButton.setText(mode == SourceMode.VIDEO ? "▶" : "◆");
            stopCamera();
            previewHint.setText(mode == SourceMode.VIDEO
                    ? "Importez une vidéo à détourer"
                    : "Importez une photo à détourer");
            previewHint.setVisibility(View.VISIBLE);
        }
    }

    private void openForegroundPicker() {
        if (sourceMode == SourceMode.CAMERA) {
            Toast.makeText(this, "Choisissez le mode Vidéo ou Photo", Toast.LENGTH_SHORT).show();
            return;
        }
        foregroundPicker.launch(sourceMode == SourceMode.VIDEO
                ? new String[]{"video/*"} : new String[]{"image/*"});
    }

    private void onForegroundSelected(Uri uri) {
        if (uri == null) {
            return;
        }
        persistReadPermission(uri);
        foregroundUri = uri;
        showBusy(0, "Analyse du média…");
        if (sourceMode == SourceMode.PHOTO) {
            ioExecutor.execute(() -> {
                try {
                    Bitmap bitmap = BitmapUtils.decodeUri(this, uri, 2048);
                    runOnUiThread(() -> processStillBitmap(bitmap));
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error.getMessage()));
                }
            });
        } else {
            ioExecutor.execute(() -> {
                try {
                    Bitmap bitmap = extractVideoPreview(uri);
                    runOnUiThread(() -> processStillBitmap(bitmap));
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error.getMessage()));
                }
            });
        }
    }

    private void processStillBitmap(Bitmap bitmap) {
        if (segmenter == null) {
            bitmap.recycle();
            return;
        }
        segmenter.processStill(bitmap, new SegmentationEngine.Callback() {
            @Override
            public void onResult(SegmentationEngine.Result result) {
                acceptResult(result);
                hideBusy();
            }

            @Override
            public void onError(Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private Bitmap extractVideoPreview(Uri uri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            setRetrieverDataSource(retriever, uri);
            Bitmap bitmap = retriever.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                throw new IOException("Aucune image lisible dans cette vidéo");
            }
            String rotationValue = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rotationValue == null ? 0 : Integer.parseInt(rotationValue);
            if ((rotation == 90 || rotation == 270)
                    && bitmap.getWidth() > bitmap.getHeight()) {
                bitmap = BitmapUtils.rotateAndMirror(bitmap, rotation, false);
            } else if (rotation == 180) {
                bitmap = BitmapUtils.rotateAndMirror(bitmap, 180, false);
            }
            return bitmap;
        } finally {
            retriever.release();
        }
    }

    private void requestCameraPermissionsIfNeeded() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted && audioGranted) {
            startCamera();
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        if (Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA))) {
            startCamera();
        } else {
            Toast.makeText(this, "La caméra est nécessaire pour filmer",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        if (sourceMode != SourceMode.CAMERA || segmenter == null
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
        if (cameraProvider == null) {
            return;
        }
        cameraProvider.unbindAll();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeCameraFrame);

        QualitySelector selector = QualitySelector.from(Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(selector)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();
        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, analysis, videoCapture);
        } catch (Exception error) {
            showError("Ce téléphone ne permet pas cette combinaison caméra");
        }
    }

    private void analyzeCameraFrame(@NonNull ImageProxy imageProxy) {
        try {
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            Bitmap bitmap = BitmapUtils.fromRgbaImageProxy(imageProxy);
            imageProxy.close();
            bitmap = BitmapUtils.rotateAndMirror(bitmap, rotation,
                    lensFacing == CameraSelector.LENS_FACING_FRONT);
            Bitmap finalBitmap = bitmap;
            segmenter.processStream(finalBitmap, new SegmentationEngine.Callback() {
                @Override
                public void onResult(SegmentationEngine.Result result) {
                    if (sourceMode == SourceMode.CAMERA) {
                        acceptResult(result);
                    } else {
                        result.source.recycle();
                        result.cutout.recycle();
                    }
                }

                @Override
                public void onError(Exception error) {
                    // Une frame perdue est ignorée pour garder un aperçu fluide.
                }
            });
        } catch (Exception error) {
            imageProxy.close();
        }
    }

    private void stopCamera() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void onCenterAction() {
        if (sourceMode == SourceMode.CAMERA) {
            if (activeRecording == null) {
                startRecording();
            } else {
                stopRecording();
            }
        } else if (foregroundUri == null) {
            openForegroundPicker();
        } else {
            exportCurrentMedia();
        }
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
                "raw_fond_vert_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(activeRawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recordButton.setText("■");
                recordButton.setBackgroundResource(R.drawable.bg_circle_recording);
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                activeRecording = null;
                recordButton.setText("●");
                recordButton.setBackgroundResource(R.drawable.bg_circle_accent);
                if (finalized.getError() == VideoRecordEvent.Finalize.ERROR_NONE
                        && activeRawFile != null && activeRawFile.exists()) {
                    foregroundUri = Uri.fromFile(activeRawFile);
                    enqueueVideoExport(foregroundUri);
                } else {
                    showError("Enregistrement interrompu");
                }
            }
        });
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
        }
    }

    private void exportCurrentMedia() {
        if (sourceMode == SourceMode.CAMERA) {
            if (activeRecording != null) {
                stopRecording();
            } else {
                Toast.makeText(this, "Appuyez sur le bouton central pour filmer",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (foregroundUri == null || currentCutout == null) {
            Toast.makeText(this, "Importez d’abord un média", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sourceMode == SourceMode.VIDEO) {
            enqueueVideoExport(foregroundUri);
        } else {
            exportPhoto();
        }
    }

    private void enqueueVideoExport(Uri sourceUri) {
        if (backgroundSpec.getType() == BackgroundSpec.Type.TRANSPARENT) {
            Toast.makeText(this,
                    "La vidéo MP4 sera exportée sur vert pur pour CapCut",
                    Toast.LENGTH_LONG).show();
        }
        Data.Builder input = new Data.Builder()
                .putString(VideoExportWorker.KEY_SOURCE_URI, sourceUri.toString())
                .putString(VideoExportWorker.KEY_BACKGROUND_TYPE,
                        backgroundSpec.getType().name())
                .putInt(VideoExportWorker.KEY_BACKGROUND_COLOR, backgroundSpec.getColor())
                .putFloat(VideoExportWorker.KEY_THRESHOLD, thresholdSlider.getValue())
                .putFloat(VideoExportWorker.KEY_SOFTNESS, softnessSlider.getValue())
                .putInt(VideoExportWorker.KEY_QUALITY, quality);
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

    private void observeExport(OneTimeWorkRequest request) {
        showBusy(0, "Détourage et export…");
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.getId())
                .observe(this, info -> {
                    if (info == null) {
                        return;
                    }
                    int progress = info.getProgress().getInt(
                            VideoExportWorker.KEY_PROGRESS, 0);
                    processingProgress.setProgress(progress);
                    processingText.setText("Détourage et export… " + progress + "%");
                    if (info.getState() == WorkInfo.State.SUCCEEDED) {
                        hideBusy();
                        String output = info.getOutputData().getString(
                                VideoExportWorker.KEY_OUTPUT_URI);
                        if (activeRawFile != null && activeRawFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            activeRawFile.delete();
                            activeRawFile = null;
                        }
                        if (output != null) {
                            showSavedSnackbar(Uri.parse(output), "video/mp4");
                        }
                    } else if (info.getState() == WorkInfo.State.FAILED
                            || info.getState() == WorkInfo.State.CANCELLED) {
                        String error = info.getOutputData().getString(VideoExportWorker.KEY_ERROR);
                        showError(error == null ? "Échec de l’export" : error);
                    }
                });
    }

    private void exportPhoto() {
        Bitmap cutout = currentCutout.copy(Bitmap.Config.ARGB_8888, false);
        BackgroundSpec.Type type = backgroundSpec.getType();
        int color = backgroundSpec.getColor();
        Uri backgroundUri = backgroundSpec.getUri();
        showBusy(20, "Création du PNG…");
        ioExecutor.execute(() -> {
            Bitmap output = null;
            Bitmap background = null;
            try {
                if (type == BackgroundSpec.Type.TRANSPARENT) {
                    output = cutout;
                } else {
                    if (type == BackgroundSpec.Type.IMAGE && backgroundUri != null) {
                        background = BitmapUtils.decodeUri(this, backgroundUri, 2048);
                    } else if (type == BackgroundSpec.Type.VIDEO && backgroundUri != null) {
                        background = extractVideoPreview(backgroundUri);
                    }
                    output = BitmapUtils.composite(cutout, background, color,
                            cutout.getWidth(), cutout.getHeight());
                    cutout.recycle();
                }
                Uri saved = MediaStoreSaver.savePng(this, output,
                        "FondVert_" + System.currentTimeMillis() + ".png");
                output.recycle();
                if (background != null) {
                    background.recycle();
                }
                runOnUiThread(() -> {
                    hideBusy();
                    showSavedSnackbar(saved, "image/png");
                });
            } catch (Exception error) {
                if (output != null && !output.isRecycled()) {
                    output.recycle();
                }
                if (background != null && !background.isRecycled()) {
                    background.recycle();
                }
                runOnUiThread(() -> showError(error.getMessage()));
            }
        });
    }

    private void acceptResult(SegmentationEngine.Result result) {
        Bitmap oldSource = currentSource;
        Bitmap oldCutout = currentCutout;
        currentSource = result.source;
        currentCutout = result.cutout;
        currentMask = result.mask;
        currentMaskWidth = result.maskWidth;
        currentMaskHeight = result.maskHeight;
        subjectPreview.setImageBitmap(currentCutout);
        previewHint.setVisibility(View.GONE);
        if (oldSource != null && oldSource != currentSource && !oldSource.isRecycled()) {
            oldSource.recycle();
        }
        if (oldCutout != null && oldCutout != currentCutout && !oldCutout.isRecycled()) {
            oldCutout.recycle();
        }
    }

    private void updateEdgeSettings() {
        float threshold = thresholdSlider.getValue();
        float softness = softnessSlider.getValue();
        if (segmenter != null) {
            segmenter.setEdgeSettings(threshold, softness);
        }
        Bitmap source = currentSource;
        float[] mask = currentMask;
        if (source == null || mask == null) {
            return;
        }
        int generation = edgeGeneration.incrementAndGet();
        Bitmap safeSource = source.copy(Bitmap.Config.ARGB_8888, false);
        int maskWidth = currentMaskWidth;
        int maskHeight = currentMaskHeight;
        ioExecutor.execute(() -> {
            Bitmap refreshed = BitmapUtils.applyMask(safeSource, mask,
                    maskWidth, maskHeight, threshold, softness);
            safeSource.recycle();
            runOnUiThread(() -> {
                if (generation != edgeGeneration.get()) {
                    refreshed.recycle();
                    return;
                }
                Bitmap old = currentCutout;
                currentCutout = refreshed;
                subjectPreview.setImageBitmap(refreshed);
                if (old != null && !old.isRecycled()) {
                    old.recycle();
                }
            });
        });
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
        previewContainer.setBackgroundColor(Color.BLACK);
        ioExecutor.execute(() -> {
            try {
                Bitmap bitmap = BitmapUtils.decodeUri(this, uri, 2048);
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
            backgroundVideo.start();
        });
    }

    private void highlightBackgroundButton(int selectedId) {
        int[] ids = {R.id.bgTransparent, R.id.bgGreen, R.id.bgBlack,
                R.id.bgWhite, R.id.bgImage, R.id.bgVideo};
        for (int id : ids) {
            MaterialButton button = findViewById(id);
            boolean selected = id == selectedId;
            button.setStrokeWidth(dp(selected ? 2 : 1));
            button.setStrokeColor(ColorStateList.valueOf(selected
                    ? ContextCompat.getColor(this, R.color.accent)
                    : ContextCompat.getColor(this, R.color.surface_high)));
            button.setTextColor(selected
                    ? ContextCompat.getColor(this, R.color.accent)
                    : ContextCompat.getColor(this, R.color.ink));
        }
    }

    private void clearSubjectPreview() {
        subjectPreview.setImageDrawable(null);
        if (currentSource != null && !currentSource.isRecycled()) {
            currentSource.recycle();
        }
        if (currentCutout != null && !currentCutout.isRecycled()) {
            currentCutout.recycle();
        }
        currentSource = null;
        currentCutout = null;
        currentMask = null;
    }

    private void showBusy(int progress, String text) {
        processingProgress.setProgress(progress);
        processingText.setText(text);
        processingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideBusy() {
        processingOverlay.setVisibility(View.GONE);
    }

    private void showError(String message) {
        hideBusy();
        Toast.makeText(this, message == null ? "Une erreur est survenue" : message,
                Toast.LENGTH_LONG).show();
    }

    private void showSavedSnackbar(Uri uri, String mime) {
        Snackbar.make(exportButton, "Fichier enregistré dans la galerie",
                        Snackbar.LENGTH_LONG)
                .setAction("Ouvrir", v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(this, "Fichier : " + uri,
                                Toast.LENGTH_LONG).show();
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

    private void setRetrieverDataSource(MediaMetadataRetriever retriever, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            retriever.setDataSource(uri.getPath());
        } else {
            retriever.setDataSource(this, uri);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
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
