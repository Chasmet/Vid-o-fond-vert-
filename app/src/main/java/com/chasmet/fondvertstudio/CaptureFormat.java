package com.chasmet.fondvertstudio;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.Display;
import android.view.Surface;

/** Format vidéo choisi avant d'ouvrir la caméra. */
final class CaptureFormat {
    static final String EXTRA_FORMAT = "capture_format";
    static final String VERTICAL = "vertical";
    static final String HORIZONTAL = "horizontal";

    private CaptureFormat() {
    }

    static boolean isHorizontal(Intent intent) {
        return intent != null
                && HORIZONTAL.equals(intent.getStringExtra(EXTRA_FORMAT));
    }

    static String sanitize(String format) {
        return HORIZONTAL.equals(format) ? HORIZONTAL : VERTICAL;
    }

    static int requestedOrientation(Intent intent) {
        return isHorizontal(intent)
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    static void applyRequestedOrientation(Activity activity, Intent intent) {
        if (activity == null) return;
        int requested = requestedOrientation(intent);
        if (activity.getRequestedOrientation() != requested) {
            activity.setRequestedOrientation(requested);
        }
    }

    @SuppressWarnings("deprecation")
    static int surfaceRotation(Activity activity) {
        if (activity == null) return Surface.ROTATION_0;
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    /**
     * Évite qu'une valeur de rotation encore héritée de l'écran précédent soit transmise à
     * CameraX pendant le changement portrait/paysage. C'était visible sur certains appareils
     * sous la forme d'une caméra couchée dans un fichier horizontal entouré de bandes noires.
     */
    static int cameraTargetRotation(Activity activity, boolean horizontal) {
        return normalizeCameraRotation(surfaceRotation(activity), horizontal);
    }

    static int normalizeCameraRotation(int displayRotation, boolean horizontal) {
        if (horizontal) {
            return displayRotation == Surface.ROTATION_90
                    || displayRotation == Surface.ROTATION_270
                    ? displayRotation : Surface.ROTATION_90;
        }
        return displayRotation == Surface.ROTATION_0
                || displayRotation == Surface.ROTATION_180
                ? displayRotation : Surface.ROTATION_0;
    }

    static int videoWidth(boolean horizontal, int quality) {
        if (quality >= 1080) return horizontal ? 1920 : 1080;
        return horizontal ? 1280 : 720;
    }

    static int videoHeight(boolean horizontal, int quality) {
        if (quality >= 1080) return horizontal ? 1080 : 1920;
        return horizontal ? 720 : 1280;
    }

    static String label(boolean horizontal) {
        return horizontal ? "HORIZONTAL 16:9" : "VERTICAL 9:16";
    }
}
