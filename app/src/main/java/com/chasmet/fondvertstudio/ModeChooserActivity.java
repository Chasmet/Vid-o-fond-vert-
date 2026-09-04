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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_chooser);

        MaterialButton classicButton = findViewById(R.id.classicModeButton);
        MaterialButton greenButton = findViewById(R.id.greenModeButton);
        MaterialButton settingsButton = findViewById(R.id.settingsButton);
        updateBadge = findViewById(R.id.updateBadge);

        classicButton.setOnClickListener(v ->
                startActivity(new Intent(this, ClassicCameraActivity.class)));
        greenButton.setOnClickListener(v ->
                startActivity(new Intent(this, ClipTimelineInstantActivity.class)));
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        refreshUpdateBadge();
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
