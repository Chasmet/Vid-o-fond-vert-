package com.chasmet.fondvertstudio;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Date;
import java.util.Locale;

public final class SettingsActivity extends AppCompatActivity {
    public static final String PREFS = "fond_vert_settings";
    public static final String KEY_AUTO_CHECK = "auto_check_updates";
    public static final String KEY_LAST_CHECK = "last_update_check_ms";
    public static final String KEY_CACHED_AVAILABLE = "cached_update_available";
    public static final String KEY_CACHED_VERSION = "cached_update_version";

    private TextView currentVersionText;
    private TextView updateStatusText;
    private TextView availableVersionText;
    private TextView releaseNotesText;
    private TextView progressText;
    private TextView lastCheckText;
    private ProgressBar progressBar;
    private MaterialButton checkButton;
    private MaterialButton downloadButton;
    private SwitchMaterial autoCheckSwitch;

    private AppUpdateManager.UpdateInfo latestInfo;
    private File pendingApk;
    private boolean waitingForInstallPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        currentVersionText = findViewById(R.id.currentVersionText);
        updateStatusText = findViewById(R.id.updateStatusText);
        availableVersionText = findViewById(R.id.availableVersionText);
        releaseNotesText = findViewById(R.id.releaseNotesText);
        progressText = findViewById(R.id.updateProgressText);
        lastCheckText = findViewById(R.id.lastCheckText);
        progressBar = findViewById(R.id.updateProgressBar);
        checkButton = findViewById(R.id.checkUpdateButton);
        downloadButton = findViewById(R.id.downloadUpdateButton);
        autoCheckSwitch = findViewById(R.id.autoCheckSwitch);
        MaterialButton backButton = findViewById(R.id.settingsBackButton);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentVersionText.setText("Version installée : " + AppUpdateManager.getCurrentVersionName(this));
        autoCheckSwitch.setChecked(preferences.getBoolean(KEY_AUTO_CHECK, true));
        autoCheckSwitch.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(KEY_AUTO_CHECK, checked).apply());

        backButton.setOnClickListener(v -> finish());
        checkButton.setOnClickListener(v -> checkForUpdate());
        downloadButton.setOnClickListener(v -> startDownload());

        updateLastCheckLabel(preferences.getLong(KEY_LAST_CHECK, 0L));
        if (preferences.getBoolean(KEY_CACHED_AVAILABLE, false)) {
            String cachedVersion = preferences.getString(KEY_CACHED_VERSION, "");
            if (!TextUtils.isEmpty(cachedVersion)) {
                updateStatusText.setText("Une mise à jour a été détectée récemment.");
                availableVersionText.setText("Version disponible : " + cachedVersion);
            }
        }

        checkForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForInstallPermission && pendingApk != null) {
            waitingForInstallPermission = false;
            updateStatusText.setText("Vérification de l'autorisation d'installation…");
            boolean installationOpened = AppUpdateManager.installDownloadedApk(this, pendingApk);
            if (!installationOpened) {
                waitingForInstallPermission = true;
                updateStatusText.setText(
                        "L'autorisation d'installation est nécessaire pour appliquer la mise à jour.");
            }
        }
    }

    private void checkForUpdate() {
        setCheckingUi(true);
        updateStatusText.setText("Vérification de la dernière version GitHub…");
        progressText.setText("Connexion à GitHub Releases");
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);

        AppUpdateManager.checkLatest(this, new AppUpdateManager.CheckCallback() {
            @Override
            public void onSuccess(AppUpdateManager.UpdateInfo info) {
                latestInfo = info;
                setCheckingUi(false);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");

                long now = System.currentTimeMillis();
                SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
                preferences.edit()
                        .putLong(KEY_LAST_CHECK, now)
                        .putBoolean(KEY_CACHED_AVAILABLE, info.newer)
                        .putString(KEY_CACHED_VERSION, info.version)
                        .apply();
                updateLastCheckLabel(now);

                availableVersionText.setText("Version disponible : " + info.version);
                if (info.newer) {
                    updateStatusText.setText("Mise à jour disponible.");
                    downloadButton.setVisibility(View.VISIBLE);
                    downloadButton.setEnabled(true);
                } else {
                    updateStatusText.setText("L'application est à jour.");
                    downloadButton.setVisibility(View.GONE);
                }

                String notes = info.notes == null ? "" : info.notes.trim();
                if (notes.isEmpty()) {
                    releaseNotesText.setText("Aucune note de version fournie.");
                } else {
                    releaseNotesText.setText(notes);
                }
            }

            @Override
            public void onError(String message) {
                setCheckingUi(false);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");
                updateStatusText.setText("Vérification impossible : " + message);
            }
        });
    }

    private void startDownload() {
        if (latestInfo == null || !latestInfo.newer) {
            checkForUpdate();
            return;
        }

        checkButton.setEnabled(false);
        downloadButton.setEnabled(false);
        updateStatusText.setText("Téléchargement de la mise à jour…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(latestInfo.assetSize <= 0);
        progressBar.setProgress(0);
        progressText.setText("0 %");

        AppUpdateManager.download(this, latestInfo, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onStarted(long totalBytes) {
                progressBar.setIndeterminate(totalBytes <= 0);
                if (totalBytes > 0) {
                    progressText.setText("0 % · 0 / " + formatBytes(totalBytes));
                } else {
                    progressText.setText("Téléchargement en cours…");
                }
            }

            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes,
                                   long bytesPerSecond) {
                if (percent >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(percent);
                }
                StringBuilder text = new StringBuilder();
                if (percent >= 0) {
                    text.append(percent).append(" % · ");
                }
                text.append(formatBytes(downloadedBytes));
                if (totalBytes > 0) {
                    text.append(" / ").append(formatBytes(totalBytes));
                }
                if (bytesPerSecond > 0) {
                    text.append(" · ").append(formatBytes(bytesPerSecond)).append("/s");
                }
                progressText.setText(text.toString());
            }

            @Override
            public void onReady(File apkFile) {
                pendingApk = apkFile;
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
                progressText.setText("100 % · APK vérifié");
                updateStatusText.setText("Téléchargement terminé. Ouverture de l'installation Android…");
                checkButton.setEnabled(true);
                downloadButton.setEnabled(true);
                boolean installationOpened = AppUpdateManager.installDownloadedApk(
                        SettingsActivity.this, apkFile);
                if (!installationOpened) {
                    waitingForInstallPermission = true;
                    updateStatusText.setText(
                            "Autorise Fond Vert Studio à installer cette mise à jour, puis reviens ici.");
                }
            }

            @Override
            public void onError(String message) {
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");
                updateStatusText.setText("Mise à jour interrompue : " + message);
                checkButton.setEnabled(true);
                downloadButton.setEnabled(true);
            }
        });
    }

    private void setCheckingUi(boolean checking) {
        checkButton.setEnabled(!checking);
        if (checking) {
            downloadButton.setEnabled(false);
        }
    }

    private void updateLastCheckLabel(long timestamp) {
        if (timestamp <= 0L) {
            lastCheckText.setText("Dernière vérification : jamais");
            return;
        }
        java.text.DateFormat formatter = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault());
        lastCheckText.setText("Dernière vérification : " + formatter.format(new Date(timestamp)));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " o";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.getDefault(), "%.1f Ko", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.getDefault(), "%.1f Mo", mb);
        }
        return String.format(Locale.getDefault(), "%.2f Go", mb / 1024.0);
    }
}
