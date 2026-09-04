package com.chasmet.fondvertstudio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public final class ModeChooserActivity extends AppCompatActivity {
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private TextView updateBadge;
    private TextView formatStatus;
    private MaterialButton verticalButton;
    private MaterialButton horizontalButton;
    private MaterialButton classicButton;
    private MaterialButton greenButton;
    private String selectedFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_chooser);

        verticalButton = findViewById(R.id.verticalFormatButton);
        horizontalButton = findViewById(R.id.horizontalFormatButton);
        classicButton = findViewById(R.id.classicModeButton);
        greenButton = findViewById(R.id.greenModeButton);
        MaterialButton settingsButton = findViewById(R.id.settingsButton);
        formatStatus = findViewById(R.id.formatStatus);
        updateBadge = findViewById(R.id.updateBadge);

        classicButton.setEnabled(false);
        greenButton.setEnabled(false);
        classicButton.setAlpha(0.45f);
        greenButton.setAlpha(0.45f);

        verticalButton.setOnClickListener(v -> selectFormat(CaptureFormat.VERTICAL));
        horizontalButton.setOnClickListener(v -> selectFormat(CaptureFormat.HORIZONTAL));

        classicButton.setOnClickListener(v -> openCapture(ClassicCameraActivity.class));
        greenButton.setOnClickListener(v -> openCapture(ClipTimelineInstantActivity.class));
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        refreshUpdateBadge();
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
        greenButton.setEnabled(true);
        classicButton.setAlpha(1f);
        greenButton.setAlpha(1f);
    }

    private void openCapture(Class<?> activityClass) {
        if (selectedFormat == null) return;
        Intent intent = new Intent(this, activityClass);
        intent.putExtra(CaptureFormat.EXTRA_FORMAT, selectedFormat);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateBadge != null) refreshUpdateBadge();
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
