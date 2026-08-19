package com.chasmet.fondvertstudio;

import android.Manifest;
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
 * Mode clip musical.
 *
 * La musique importée est la seule piste audio du MP4. Le microphone caméra n'est jamais
 * activé dans ce mode. Le sujet est détouré et peut être placé devant un fond vert, une image
 * ou une vidéo importée.
 */
public final class ClipMusicActivity extends AppCompatActivity {
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
    private ProgressBar processingProgress;
    private View processingOverlay;

    private MaterialButton recordButton;
    private MaterialButton finishButton;
    private MaterialButton flipCameraButton;
    private MaterialButton qualityButton;
    private MaterialButton importAudioButton;
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

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File activeRawFile;

    private SegmentationEngine segmenter;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
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
    private ActivityResultLauncher<String[]> backgroundImagePicker;
    private ActivityResultLauncher<String[]> backgroundVideoPicker;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (activeRecording == null) return;
            long duration = currentClipDurationMs();
            recordingTimer.setText(formatDuration(duration));
            if (!clipPaused) {
                recordButton.setText("Ⅱ  PAUSE   " + formatDuration(duration));
            }
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
        setContentView(R.layout.activity_clip_music);
        bindViews();
        registerLaunchers();
        setupControls();

        backgroundSpec.setColor(Color.rgb(0, 255, 0));
        showGreenBackground();

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
        processingProgress = findViewById(R.id.processingProgress);
        processingOverlay = findViewById(R.id.processingOverlay);
        recordButton = findViewById(R.id.recordButton);
        finishButton = findViewById(R.id.finishButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        qualityButton = findViewById(R.id.qualityButton);
        importAudioButton = findViewById(R.id.importAudioButton);
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

        backgroundImagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    persistReadPermission(uri);
                    backgroundSpec.setImage(uri);
                    showImageBackground(uri);
                    decorStatus.setText("Image · " + readDisplayName(uri));
                    Toast.makeText(this, "Image ajoutée derrière toi", Toast.LENGTH_SHORT).show();
                });

        backgroundVideoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    persistReadPermission(uri);
                    backgroundSpec.setVideo(uri);
                    showVideoBackground(uri);
                    decorStatus.setText("Vidéo · " + readDisplayName(uri));
                    Toast.makeText(this, "Vidéo ajoutée derrière toi", Toast.LENGTH_SHORT).show();
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
            if (activeRecording == null) {
                backgroundImagePicker.launch(new String[]{"image/*"});
            }
        });
        importVideoButton.setOnClickListener(v -> {
            if (activeRecording == null) {
                backgroundVideoPicker.launch(new String[]{"video/*"});
            }
        });
        greenBackgroundButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            backgroundSpec.setColor(Color.rgb(0, 255, 0));
            showGreenBackground();
            decorStatus.setText("Fond vert");
        });

        importAudioButton.setOnClickListener(v -> {
            if (activeRecording == null) audioPicker.launch(new String[]{"audio/*"});
        });
        musicPlayButton.setOnClickListener(v -> toggleMusicPreview());

        qualityButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });

        flipCameraButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
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
                if (fromUser && musicPrepared && musicPlayer != null && activeRecording == null) {
                    try {
                        musicPlayer.seekTo(progress);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (musicPrepared && musicPlayer != null && activeRecording == null) {
                    try {
                        musicPlayer.start();
                        musicPlayButton.setText("Ⅱ PAUSE SON");
                    } catch (IllegalStateException ignored) {
                    }
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
    }

    private void showGreenBackground() {
        try {
            backgroundVideo.stopPlayback();
        } catch (Exception ignored) {
        }
        backgroundVideo.setVisibility(View.GONE);
        backgroundImage.setImageDrawable(null);
        backgroundImage.setVisibility(View.GONE);
        previewContainer.setBackgroundColor(Color.rgb(0, 255, 0));
    }

    private void showImageBackground(Uri uri) {
        try {
            backgroundVideo.stopPlayback();
        } catch (Exception ignored) {
        }
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
        try {
            backgroundVideo.stopPlayback();
        } catch (Exception ignored) {
        }
        backgroundVideo.setVideoURI(uri);
        backgroundVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            player.setVolume(0f, 0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                player.setVideoScalingMode(
                        MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
            if (activeRecording == null || !clipPaused) {
                backgroundVideo.start();
            }
        });
    }

    private void loadMusic(Uri uri) {
        releaseMusicPlayer();
        musicUri = uri;
        musicPrepared = false;
        audioName.setText(readDisplayName(uri));
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
                if (activeRecording != null && !clipPaused) {
                    finishClipRecording();
                } else {
                    musicPlayButton.setText("▶ ÉCOUTER");
                }
            });
            player.prepareAsync();
            musicPlayer = player;
        } catch (Exception error) {
            musicUri = null;
            audioName.setText("Audio illisible");
            clipStatus.setText("Choisis un autre fichier audio");
            Toast.makeText(this, "Impossible de lire cette musique", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleMusicPreview() {
        if (!musicPrepared || musicPlayer == null || activeRecording != null) return;
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
        } catch (IllegalStateException ignored) {
        }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
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
            public void onError(Exception error) {
                // La prochaine image remplacera celle qui a été ignorée.
            }
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
        try {
            musicPlayer.pause();
            clipAudioStartMs = Math.min(musicSeekBar.getProgress(),
                    Math.max(0, musicPlayer.getDuration() - 1));
            musicPlayer.seekTo(clipAudioStartMs);
        } catch (IllegalStateException error) {
            showError("La musique n’est pas prête");
            return;
        }

        prepareBackgroundForRecording();

        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) directory = getCacheDir();
        activeRawFile = new File(directory,
                "clip_source_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(activeRawFile).build();

        // Aucun withAudioEnabled() : le microphone ne peut pas entrer dans le clip.
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        recordButton.setEnabled(false);
        try {
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    beginClipSession();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                    File transformFile = finishSubjectTransformTimeline();
                    activeRecording = null;
                    clipPaused = false;
                    uiHandler.removeCallbacks(timerTick);
                    pauseMusicAndBackground();
                    showIdleUi();
                    if (finalized.getError() == VideoRecordEvent.Finalize.ERROR_NONE
                            && activeRawFile != null && activeRawFile.exists()) {
                        enqueueClipExport(Uri.fromFile(activeRawFile), transformFile);
                    } else {
                        deleteFile(transformFile);
                        showError("Enregistrement interrompu");
                    }
                }
            });
        } catch (Exception error) {
            activeRecording = null;
            showIdleUi();
            showError("Impossible de démarrer le tournage");
        }
    }

    private void prepareBackgroundForRecording() {
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try {
                backgroundVideo.seekTo(0);
                backgroundVideo.start();
            } catch (Exception ignored) {
            }
        }
    }

    private void beginClipSession() {
        recordingAccumulatedMs = 0L;
        activeSegmentStartedAt = SystemClock.elapsedRealtime();
        clipPaused = false;
        beginSubjectTransformTimeline();
        recordingTimer.setText("00:00");
        recordingTimer.setVisibility(View.VISIBLE);
        recordButton.setText("Ⅱ  PAUSE   00:00");
        recordButton.setEnabled(true);
        finishButton.setVisibility(View.VISIBLE);
        finishButton.setEnabled(true);
        setImportControlsEnabled(false);
        musicPlayButton.setEnabled(false);
        musicSeekBar.setEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
        clipStatus.setText("TOURNAGE · musique seule, micro coupé");
        try {
            musicPlayer.seekTo(clipAudioStartMs);
            musicPlayer.start();
        } catch (IllegalStateException ignored) {
        }
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO) {
            try {
                backgroundVideo.seekTo(0);
                backgroundVideo.start();
            } catch (Exception ignored) {
            }
        }
        uiHandler.removeCallbacks(timerTick);
        uiHandler.post(timerTick);
    }

    private void pauseClipRecording() {
        if (activeRecording == null || clipPaused) return;
        recordingAccumulatedMs = currentClipDurationMs();
        activeSegmentStartedAt = 0L;
        clipPaused = true;
        recordSubjectTransform(true);
        try {
            activeRecording.pause();
        } catch (Exception ignored) {
        }
        pauseMusicAndBackground();
        recordButton.setText("▶  REPRENDRE   " + formatDuration(recordingAccumulatedMs));
        clipStatus.setText("PAUSE · change de plan puis reprends");
    }

    private void resumeClipRecording() {
        if (activeRecording == null || !clipPaused) return;
        try {
            activeRecording.resume();
        } catch (Exception ignored) {
        }
        activeSegmentStartedAt = SystemClock.elapsedRealtime();
        clipPaused = false;
        if (musicPrepared && musicPlayer != null) {
            try {
                musicPlayer.start();
            } catch (IllegalStateException ignored) {
            }
        }
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO) {
            try {
                backgroundVideo.start();
            } catch (Exception ignored) {
            }
        }
        recordButton.setText("Ⅱ  PAUSE   " + formatDuration(recordingAccumulatedMs));
        clipStatus.setText("TOURNAGE · reprise exactement au même son");
    }

    private void finishClipRecording() {
        if (activeRecording == null) return;
        if (!clipPaused) recordingAccumulatedMs = currentClipDurationMs();
        activeSegmentStartedAt = 0L;
        clipPaused = true;
        recordSubjectTransform(true);
        pauseMusicAndBackground();
        recordButton.setEnabled(false);
        finishButton.setEnabled(false);
        recordButton.setText("Finalisation…");
        activeRecording.stop();
    }

    private void pauseMusicAndBackground() {
        if (musicPrepared && musicPlayer != null) {
            try {
                musicPlayer.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO) {
            try {
                if (backgroundVideo.isPlaying()) backgroundVideo.pause();
            } catch (Exception ignored) {
            }
        }
    }

    private long currentClipDurationMs() {
        if (activeRecording == null) return recordingAccumulatedMs;
        if (clipPaused || activeSegmentStartedAt <= 0L) return recordingAccumulatedMs;
        return recordingAccumulatedMs
                + Math.max(0L, SystemClock.elapsedRealtime() - activeSegmentStartedAt);
    }

    private void enqueueClipExport(Uri sourceUri, File transformFile) {
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
                        subjectPreview.getSubjectCenterY())
                .putString(VideoExportWorker.KEY_EXTERNAL_AUDIO_URI, musicUri.toString())
                .putLong(VideoExportWorker.KEY_EXTERNAL_AUDIO_START_MS, clipAudioStartMs);

        if (backgroundSpec.getUri() != null) {
            input.putString(VideoExportWorker.KEY_BACKGROUND_URI,
                    backgroundSpec.getUri().toString());
        }
        if (transformFile != null) {
            input.putString(VideoExportWorker.KEY_TRANSFORM_PATH,
                    transformFile.getAbsolutePath());
        }

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VideoExportWorker.class)
                .setInputData(input.build()).build();
        WorkManager.getInstance(this).enqueue(request);
        observeExport(request);
    }

    private void observeExport(OneTimeWorkRequest request) {
        showBusy(0, "Détourage, décor et ajout de la musique…");
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.getId())
                .observe(this, info -> {
                    if (info == null) return;
                    int progress = info.getProgress().getInt(VideoExportWorker.KEY_PROGRESS, 0);
                    processingProgress.setProgress(progress);
                    TextView text = findViewById(R.id.processingText);
                    text.setText("Création du clip… " + progress + "%");
                    if (info.getState() == WorkInfo.State.SUCCEEDED) {
                        hideBusy();
                        String output = info.getOutputData().getString(
                                VideoExportWorker.KEY_OUTPUT_URI);
                        deleteRawRecording();
                        if (output != null) showSavedSnackbar(Uri.parse(output));
                    } else if (info.getState() == WorkInfo.State.FAILED
                            || info.getState() == WorkInfo.State.CANCELLED) {
                        String error = info.getOutputData().getString(VideoExportWorker.KEY_ERROR);
                        deleteRawRecording();
                        showError(error == null ? "Échec de l’export du clip" : error);
                    }
                });
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
                "clip_movement_" + System.currentTimeMillis() + ".csv");
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
        if (oldSource != null && oldSource != source && !oldSource.isRecycled()) {
            oldSource.recycle();
        }
    }

    private void acceptMaskResult(SegmentationEngine.Result result) {
        subjectPreview.setMask(result.alphaMask, result.mask, result.maskWidth,
                result.maskHeight, threshold, softness);
        if (result.source != null && !result.source.isRecycled()) result.source.recycle();
        if (result.cutout != null && !result.cutout.isRecycled()) result.cutout.recycle();
    }

    private void setImportControlsEnabled(boolean enabled) {
        importAudioButton.setEnabled(enabled);
        importImageButton.setEnabled(enabled);
        importVideoButton.setEnabled(enabled);
        greenBackgroundButton.setEnabled(enabled);
    }

    private void showIdleUi() {
        recordingTimer.setVisibility(View.GONE);
        recordButton.setText("●  TOURNER");
        recordButton.setEnabled(true);
        finishButton.setVisibility(View.GONE);
        finishButton.setEnabled(true);
        setImportControlsEnabled(true);
        musicPlayButton.setEnabled(musicPrepared);
        musicSeekBar.setEnabled(musicPrepared);
        flipCameraButton.setEnabled(true);
        qualityButton.setEnabled(true);
        clipStatus.setText("Clip prêt · tu peux refaire une prise");
        if (backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try {
                backgroundVideo.start();
            } catch (Exception ignored) {
            }
        }
    }

    private void showBusy(int progress, String text) {
        processingProgress.setProgress(progress);
        ((TextView) findViewById(R.id.processingText)).setText(text);
        processingOverlay.setVisibility(View.VISIBLE);
        recordButton.setEnabled(false);
        finishButton.setEnabled(false);
        setImportControlsEnabled(false);
        musicPlayButton.setEnabled(false);
        musicSeekBar.setEnabled(false);
        flipCameraButton.setEnabled(false);
        qualityButton.setEnabled(false);
    }

    private void hideBusy() {
        processingOverlay.setVisibility(View.GONE);
        showIdleUi();
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
                    try {
                        startActivity(intent);
                    } catch (Exception error) {
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
        } catch (Exception ignored) {
        }
        return "Fichier sélectionné";
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void releaseMusicPlayer() {
        musicPrepared = false;
        if (musicPlayer != null) {
            try {
                musicPlayer.release();
            } catch (Exception ignored) {
            }
            musicPlayer = null;
        }
    }

    private void deleteRawRecording() {
        if (activeRawFile != null && activeRawFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            activeRawFile.delete();
        }
        activeRawFile = null;
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeRecording == null
                && backgroundSpec.getType() == BackgroundSpec.Type.VIDEO
                && backgroundVideo.getVisibility() == View.VISIBLE) {
            try {
                backgroundVideo.start();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        if (activeRecording != null) finishClipRecording();
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
        if (segmenter != null) segmenter.close();
        subjectPreview.clearFrame();
        if (currentSource != null && !currentSource.isRecycled()) currentSource.recycle();
        currentSource = null;
        try {
            backgroundVideo.stopPlayback();
        } catch (Exception ignored) {
        }
        backgroundImage.setImageDrawable(null);
        releaseMusicPlayer();
        super.onDestroy();
    }
}
