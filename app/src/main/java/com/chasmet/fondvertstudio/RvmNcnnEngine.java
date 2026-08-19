package com.chasmet.fondvertstudio;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Pont Java vers Robust Video Matting MobileNetV3 exécuté localement avec ncnn.
 * RVM renvoie à la fois le foreground RGB nettoyé de l'ancien décor et son alpha.
 */
final class RvmNcnnEngine implements AutoCloseable {
    static final int PREVIEW_MAX_DIMENSION = 512;
    static final int EXPORT_TARGET_SIZE = 512;

    static final class Matte {
        final float[] alpha;
        final int width;
        final int height;
        final Bitmap foreground;

        Matte(float[] alpha, int width, int height, Bitmap foreground) {
            this.alpha = alpha;
            this.width = width;
            this.height = height;
            this.foreground = foreground;
        }
    }

    private static final class NativeLoader {
        static final boolean LOADED;
        static final Throwable ERROR;

        static {
            boolean loaded = false;
            Throwable error = null;
            try {
                System.loadLibrary("rvmncnn");
                loaded = true;
            } catch (Throwable failure) {
                error = failure;
            }
            LOADED = loaded;
            ERROR = error;
        }
    }

    private long nativeHandle;

    RvmNcnnEngine(Context context) {
        if (!NativeLoader.LOADED) {
            throw new IllegalStateException("Bibliothèque RVM native indisponible", NativeLoader.ERROR);
        }
        AssetManager assets = context.getApplicationContext().getAssets();
        nativeHandle = nativeCreate(assets);
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Impossible d'initialiser RVM ncnn");
        }
    }

    boolean isReady() {
        return nativeHandle != 0L;
    }

    synchronized Matte predict(Bitmap bitmap, int targetSize, boolean highQuality) {
        if (nativeHandle == 0L) throw new IllegalStateException("RVM fermé");
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("Image RVM invalide");
        }
        Bitmap input = bitmap;
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            input = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
        try {
            byte[] rgba = nativePredict(nativeHandle, input,
                    Math.max(256, targetSize), highQuality);
            int width = input.getWidth();
            int height = input.getHeight();
            int pixelCount = width * height;
            if (rgba == null || rgba.length != pixelCount * 4) {
                throw new IllegalStateException("Sortie RVM incomplète");
            }

            float[] alpha = new float[pixelCount];
            int[] foregroundPixels = new int[pixelCount];
            int offset = 0;
            for (int i = 0; i < pixelCount; i++) {
                int red = rgba[offset++] & 0xFF;
                int green = rgba[offset++] & 0xFF;
                int blue = rgba[offset++] & 0xFF;
                int a = rgba[offset++] & 0xFF;
                alpha[i] = a / 255f;
                foregroundPixels[i] = Color.argb(255, red, green, blue);
            }
            Bitmap foreground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            foreground.setPixels(foregroundPixels, 0, width, 0, 0, width, height);
            return new Matte(alpha, width, height, foreground);
        } finally {
            if (input != bitmap && !input.isRecycled()) input.recycle();
        }
    }

    synchronized void reset() {
        if (nativeHandle != 0L) nativeReset(nativeHandle);
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
    }

    private static native long nativeCreate(AssetManager assets);
    private static native byte[] nativePredict(long handle, Bitmap bitmap,
                                               int targetSize, boolean highQuality);
    private static native void nativeReset(long handle);
    private static native void nativeDestroy(long handle);
}
