package com.chasmet.fondvertstudio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caméra classique : aucun détourage, aucun fond artificiel, aucun montage.
 * La vidéo CameraX est enregistrée telle quelle puis copiée directement dans la galerie.
 */
public final class ClassicCameraActivity extends AppCompatActivity {
    private PreviewView previewView;
    private MaterialButton recordButton;
    private MaterialButton flipButton;
    private MaterialButton qualityButton;
    private TextView timerView;
    private TextView micStatus;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private int quality = 1080;
    private long recordingStartedAt;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> permissionsLauncher;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (activeRecording == null) return;
            long elapsed = SystemClock.elapsedRealtime() - recordingStartedAt;
            String value = formatDuration(elapsed);
            timerView.setText(value);
            recordButton.setText("■  ARRÊTER   " + value);
            uiHandler.postDelayed(this, 200L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_classic_camera);

        previewView = findViewById(R.id.classicPreview);
        recordButton = findViewById(R.id.classicRecordButton);
        flipButton = findViewById(R.id.classicFlipButton);
        qualityButton = findViewById(R.id.classicQualityButton);
        timerView = findViewById(R.id.classicTimer);
        micStatus = findViewById(R.id.classicMicStatus);

        permissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionsResult);

        findViewById(R.id.classicBackButton).setOnClickListener(v -> finish());
        recordButton.setOnClickListener(v -> {
            if (activeRecording == null) startRecording();
            else stopRecording();
        });
        flipButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                    ? CameraSelector.LENS_FACING_BACK
                    : CameraSelector.LENS_FACING_FRONT;
            startCamera();
        });
        qualityButton.setOnClickListener(v -> {
            if (activeRecording != null) return;
            quality = quality == 1080 ? 720 : 1080;
            qualityButton.setText(quality + "p");
            startCamera();
        });

        requestPermissions();
    }

    private void requestPermissions() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted) {
            updateMicStatus(audioGranted);
            startCamera();
            if (!audioGranted) {
                permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
            }
            return;
        }
        permissionsLauncher.launch(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        });
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        boolean cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        updateMicStatus(audioGranted);
        if (cameraGranted) {
            startCamera();
        } else {
            Toast.makeText(this, "La caméra est nécessaire pour filmer",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateMicStatus(boolean audioGranted) {
        micStatus.setText(audioGranted
                ? "MODE CLASSIQUE · MICRO DU TÉLÉPHONE"
                : "MODE CLASSIQUE · MICRO NON AUTORISÉ");
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED || activeRecording != null) {
            return;
        }

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Quality preferred = quality == 1080 ? Quality.FHD : Quality.HD;
                QualitySelector qualitySelector = QualitySelector.from(
                        preferred,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture);
                recordButton.setEnabled(true);
            } catch (Exception error) {
                recordButton.setEnabled(false);
                Toast.makeText(this, "Caméra indisponible",
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startRecording() {
        if (videoCapture == null || activeRecording != null) return;

        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) directory = getCacheDir();
        File temporary = new File(directory,
                "classic_" + System.currentTimeMillis() + ".mp4");

        FileOutputOptions outputOptions = new FileOutputOptions.Builder(temporary).build();
        PendingRecording pending = videoCapture.getOutput()
                .prepareRecording(this, outputOptions);

        boolean audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (audioGranted) {
            pending = pending.withAudioEnabled();
        }

        recordButton.setEnabled(false);
        flipButton.setEnabled(false);
        qualityButton.setEnabled(false);

        try {
            activeRecording = pending.start(
                    ContextCompat.getMainExecutor(this),
                    event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            recordingStartedAt = SystemClock.elapsedRealtime();
                            timerView.setVisibility(View.VISIBLE);
                            recordButton.setEnabled(true);
                            uiHandler.removeCallbacks(timerTick);
                            uiHandler.post(timerTick);
                        } else if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize finalize =
                                    (VideoRecordEvent.Finalize) event;
                            activeRecording = null;
                            uiHandler.removeCallbacks(timerTick);
                            timerView.setVisibility(View.GONE);
                            recordButton.setText("●  FILMER");
                            recordButton.setEnabled(true);
                            flipButton.setEnabled(true);
                            qualityButton.setEnabled(true);

                            if (temporary.isFile() && temporary.length() > 0L) {
                                saveToGallery(temporary,
                                        finalize.getError()
                                                != VideoRecordEvent.Finalize.ERROR_NONE);
                            } else {
                                Toast.makeText(this,
                                        "La vidéo n'a pas pu être enregistrée",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
        } catch (Exception error) {
            activeRecording = null;
            recordButton.setEnabled(true);
            flipButton.setEnabled(true);
            qualityButton.setEnabled(true);
            Toast.makeText(this, "Impossible de démarrer l'enregistrement",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        if (activeRecording == null) return;
        recordButton.setEnabled(false);
        activeRecording.stop();
    }

    private void saveToGallery(File source, boolean recoveredAfterCameraWarning) {
        recordButton.setEnabled(false);
        recordButton.setText("ENREGISTREMENT…");
        ioExecutor.execute(() -> {
            try {
                Uri uri = MediaStoreSaver.saveVideo(
                        this,
                        source,
                        "VideoClassique_" + System.currentTimeMillis() + ".mp4");
                if (source.exists()) source.delete();
                runOnUiThread(() -> {
                    recordButton.setEnabled(true);
                    recordButton.setText("●  FILMER");
                    String message = recoveredAfterCameraWarning
                            ? "Vidéo récupérée et enregistrée dans la galerie"
                            : "Vidéo enregistrée dans la galerie";
                    Snackbar.make(recordButton, message, Snackbar.LENGTH_LONG)
                            .setAction("OK", v -> { })
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    recordButton.setEnabled(true);
                    recordButton.setText("●  FILMER");
                    Toast.makeText(this,
                            "Vidéo créée mais copie galerie impossible : "
                                    + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.FRANCE, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(timerTick);
        if (activeRecording != null) {
            try {
                activeRecording.stop();
            } catch (Exception ignored) {
            }
            activeRecording = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
