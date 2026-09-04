package com.chasmet.fondvertstudio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public final class ModeChooserActivity extends AppCompatActivity {
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private TextView updateBadge;
    private TextView formatStatus;
    private TextView resumeProjectSummary;
    private View resumeProjectCard;
    private MaterialButton verticalButton;
    private MaterialButton horizontalButton;
    private MaterialButton classicButton;
    private MaterialButton greenButton;
    private String selectedFormat;
    private ProjectRepository projectRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_chooser);
        projectRepository = new ProjectRepository(this);

        verticalButton = findViewById(R.id.verticalFormatButton);
        horizontalButton = findViewById(R.id.horizontalFormatButton);
        classicButton = findViewById(R.id.classicModeButton);
        greenButton = findViewById(R.id.greenModeButton);
        MaterialButton settingsButton = findViewById(R.id.settingsButton);
        formatStatus = findViewById(R.id.formatStatus);
        updateBadge = findViewById(R.id.updateBadge);
        resumeProjectCard = findViewById(R.id.resumeProjectCard);
        resumeProjectSummary = findViewById(R.id.resumeProjectSummary);
        MaterialButton resumeProjectButton = findViewById(R.id.resumeProjectButton);
        MaterialButton discardProjectButton = findViewById(R.id.discardProjectButton);

        classicButton.setEnabled(false);
        greenButton.setEnabled(false);
        classicButton.setAlpha(0.45f);
        greenButton.setAlpha(0.45f);

        verticalButton.setOnClickListener(v -> selectFormat(CaptureFormat.VERTICAL));
        horizontalButton.setOnClickListener(v -> selectFormat(CaptureFormat.HORIZONTAL));

        classicButton.setOnClickListener(v -> openCapture(ClassicCameraActivity.class));
        greenButton.setOnClickListener(v -> {
            if (!greenScreenSupported()) {
                Toast.makeText(this,
                        "Fond vert nécessite Android 6 ou supérieur",
                        Toast.LENGTH_LONG).show();
                return;
            }
            openCapture(GreenScreenCameraActivity.class);
        });
        resumeProjectButton.setOnClickListener(v -> resumeProject());
        discardProjectButton.setOnClickListener(v -> {
            projectRepository.clear(true);
            refreshProjectCard();
        });
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        updateBadge.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        refreshUpdateBadge();
        refreshProjectCard();
    }

    private void selectFormat(String format) {
        selectedFormat = format;
        boolean horizontal = CaptureFormat.HORIZONTAL.equals(format);

        verticalButton.setAlpha(horizontal ? 0.55f : 1f);
        horizontalButton.setAlpha(horizontal ? 1f : 0.55f);
        verticalButton.setText(horizontal ? "VERTICAL\n9:16" : "✓ VERTICAL\n9:16");
        horizontalButton.setText(horizontal ? "✓ HORIZONTAL\n16:9" : "HORIZONTAL\n16:9");

        formatStatus.setText(horizontal
                ? "Format choisi : HORIZONTAL 16:9"
                : "Format choisi : VERTICAL 9:16");

        classicButton.setEnabled(true);
        greenButton.setEnabled(greenScreenSupported());
        classicButton.setAlpha(1f);
        greenButton.setAlpha(greenScreenSupported() ? 1f : 0.45f);
        if (!greenScreenSupported()) {
            greenButton.setText("FOND VERT · ANDROID 6+ REQUIS");
            formatStatus.setText(formatStatus.getText()
                    + " · Classique disponible sur cet appareil");
        }
    }

    private void openCapture(Class<?> activityClass) {
        if (selectedFormat == null) return;
        Intent intent = new Intent(this, activityClass);
        intent.putExtra(CaptureFormat.EXTRA_FORMAT, selectedFormat);
        startActivity(intent);
    }

    private void resumeProject() {
        ProjectRepository.Draft draft = projectRepository.load();
        if (draft == null) {
            refreshProjectCard();
            return;
        }
        if (!greenScreenSupported()) {
            Toast.makeText(this,
                    "Ce projet Fond vert nécessite Android 6 ou supérieur",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, GreenScreenCameraActivity.class);
        intent.putExtra(CaptureFormat.EXTRA_FORMAT, draft.format);
        intent.putExtra(ProjectRepository.EXTRA_RESUME_PROJECT, true);
        startActivity(intent);
    }

    private boolean greenScreenSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    private void refreshProjectCard() {
        ProjectRepository.Draft draft = projectRepository.load();
        if (draft == null) {
            resumeProjectCard.setVisibility(View.GONE);
            return;
        }
        int plans = draft.timeline.size();
        long seconds = Math.max(0L, draft.timeline.totalDurationMs() / 1000L);
        String state = draft.isReady()
                ? "vidéo terminée" : "montage à reprendre";
        resumeProjectSummary.setText(plans + " plan(s) · "
                + String.format(java.util.Locale.FRANCE, "%02d:%02d",
                seconds / 60L, seconds % 60L)
                + " · " + CaptureFormat.label(CaptureFormat.HORIZONTAL.equals(draft.format))
                + " · " + state);
        resumeProjectCard.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateBadge != null) refreshUpdateBadge();
        if (resumeProjectCard != null) refreshProjectCard();
    }

    private void refreshUpdateBadge() {
        SharedPreferences preferences =
                getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        boolean cachedAvailable = preferences.getBoolean(
                SettingsActivity.KEY_CACHED_AVAILABLE, false);
        String cachedVersion = preferences.getString(
                SettingsActivity.KEY_CACHED_VERSION, "");
        showCachedUpdate(cachedAvailable, cachedVersion);

        if (!preferences.getBoolean(SettingsActivity.KEY_AUTO_CHECK, true)) return;

        long lastCheck = preferences.getLong(SettingsActivity.KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) return;

        AppUpdateManager.checkLatest(this, new AppUpdateManager.CheckCallback() {
            @Override
            public void onSuccess(AppUpdateManager.UpdateInfo info) {
                preferences.edit()
                        .putLong(SettingsActivity.KEY_LAST_CHECK,
                                System.currentTimeMillis())
                        .putBoolean(SettingsActivity.KEY_CACHED_AVAILABLE,
                                info.newer)
                        .putString(SettingsActivity.KEY_CACHED_VERSION,
                                info.version)
                        .apply();
                showCachedUpdate(info.newer, info.version);
            }

            @Override
            public void onError(String message) {
                // L'accueil reste silencieux si le réseau est indisponible.
            }
        });
    }

    private void showCachedUpdate(boolean available, String version) {
        if (!available) {
            updateBadge.setVisibility(View.GONE);
            return;
        }
        updateBadge.setText(version == null || version.trim().isEmpty()
                ? "MISE À JOUR DISPONIBLE"
                : "MISE À JOUR v" + version + " DISPONIBLE");
        updateBadge.setVisibility(View.VISIBLE);
    }
}
