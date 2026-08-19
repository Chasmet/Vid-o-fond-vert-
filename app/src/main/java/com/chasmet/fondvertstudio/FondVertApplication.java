package com.chasmet.fondvertstudio;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.ActivityResultRegistryOwner;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Petites améliorations d'interface qui doivent rester disponibles dans le mode Clip Musique
 * sans mélanger son fonctionnement avec le mode caméra classique.
 */
public final class FondVertApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (!(activity instanceof ClipMusicActivity)
                || !(activity instanceof ActivityResultRegistryOwner)) {
            return;
        }

        ClipMusicActivity clipActivity = (ClipMusicActivity) activity;
        ActivityResultRegistry registry =
                ((ActivityResultRegistryOwner) activity).getActivityResultRegistry();
        String key = "clip_downloads_" + System.identityHashCode(activity);
        ActivityResultLauncher<Intent> downloadsLauncher = registry.register(
                key,
                clipActivity,
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK
                            || result.getData() == null
                            || result.getData().getData() == null) {
                        return;
                    }
                    Uri uri = result.getData().getData();
                    try {
                        clipActivity.getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    importMusicIntoClip(clipActivity, uri);
                });

        // Les vues existent à la fin de setContentView(); post() laisse simplement onCreate()
        // terminer ses propres initialisations avant d'ajouter le raccourci.
        activity.getWindow().getDecorView().post(
                () -> enhanceClipMusicUi(clipActivity, downloadsLauncher));
    }

    private void enhanceClipMusicUi(ClipMusicActivity activity,
                                    ActivityResultLauncher<Intent> downloadsLauncher) {
        MaterialButton importButton = activity.findViewById(R.id.importAudioButton);
        TextView audioName = activity.findViewById(R.id.audioName);
        View musicCard = activity.findViewById(R.id.musicCard);
        if (importButton == null || audioName == null || musicCard == null) {
            return;
        }

        View parent = (View) importButton.getParent();
        if (!(parent instanceof LinearLayout)
                || !(((View) parent.getParent()) instanceof LinearLayout)) {
            return;
        }
        LinearLayout content = (LinearLayout) parent.getParent();

        // Ajout d'un véritable accès visible au dossier Téléchargements du téléphone.
        MaterialButton downloadsButton = new MaterialButton(
                activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        downloadsButton.setText("↓  TÉLÉCHARGEMENTS");
        downloadsButton.setTextSize(11f);
        downloadsButton.setAllCaps(false);
        downloadsButton.setTextColor(ContextCompat.getColor(activity, R.color.cyan));
        downloadsButton.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.cyan)));
        downloadsButton.setStrokeWidth(dp(activity, 1));
        downloadsButton.setCornerRadius(dp(activity, 14));
        downloadsButton.setMinHeight(0);
        downloadsButton.setMinWidth(0);
        downloadsButton.setContentDescription(
                "Ouvrir le dossier Téléchargements pour choisir une musique");
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40));
        downloadParams.topMargin = dp(activity, 4);
        downloadsButton.setLayoutParams(downloadParams);
        content.addView(downloadsButton, 1);

        // La carte grandit juste assez pour garder la timeline et les textes lisibles.
        ViewGroup.LayoutParams cardParams = musicCard.getLayoutParams();
        cardParams.height = dp(activity, 210);
        musicCard.setLayoutParams(cardParams);

        downloadsButton.setOnClickListener(v -> {
            if (isClipBusy(activity)) {
                Toast.makeText(activity,
                        "Termine la prise avant de changer de musique",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android/OEM peut ignorer ce point de départ, mais dans ce cas le sélecteur
                // système reste disponible normalement.
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.providers.downloads.documents/root/downloads"));
            }
            try {
                downloadsLauncher.launch(intent);
            } catch (Exception error) {
                Toast.makeText(activity,
                        "Impossible d'ouvrir Téléchargements",
                        Toast.LENGTH_LONG).show();
            }
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateImportedState(importButton, s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        audioName.addTextChangedListener(watcher);
        updateImportedState(importButton, String.valueOf(audioName.getText()));

        // Le bouton Téléchargements suit le même verrouillage que les autres imports.
        downloadsButton.post(new Runnable() {
            @Override
            public void run() {
                if (!downloadsButton.isAttachedToWindow()) {
                    return;
                }
                downloadsButton.setEnabled(!isClipBusy(activity));
                downloadsButton.postDelayed(this, 250L);
            }
        });
    }

    private static void updateImportedState(MaterialButton button, String label) {
        boolean imported = label != null
                && !label.trim().isEmpty()
                && !"Aucune musique sélectionnée".equalsIgnoreCase(label.trim())
                && !"Audio illisible".equalsIgnoreCase(label.trim())
                && !"Fichier sélectionné".equalsIgnoreCase(label.trim());
        button.setText(imported
                ? "✓  MUSIQUE IMPORTÉE"
                : "2 · IMPORTER MUSIQUE");
    }

    private static void importMusicIntoClip(ClipMusicActivity activity, Uri uri) {
        try {
            Method loadMusic = ClipMusicActivity.class.getDeclaredMethod("loadMusic", Uri.class);
            loadMusic.setAccessible(true);
            loadMusic.invoke(activity, uri);
        } catch (Exception error) {
            Toast.makeText(activity,
                    "Impossible d'importer cette musique",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isClipBusy(ClipMusicActivity activity) {
        try {
            Field recording = ClipMusicActivity.class.getDeclaredField("activeRecording");
            recording.setAccessible(true);
            return recording.get(activity) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
