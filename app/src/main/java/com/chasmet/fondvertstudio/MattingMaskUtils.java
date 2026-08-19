package com.chasmet.fondvertstudio;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/** Finition alpha dédiée aux vrais mattes MODNet : pas de seuillage brutal. */
final class MattingMaskUtils {
    private MattingMaskUtils() {
    }

    static Bitmap createAlphaMask(float[] source, int width, int height) {
        float[] refined = refine(source, width, height);
        byte[] alpha = new byte[refined.length];
        for (int i = 0; i < refined.length; i++) {
            float value = refined[i];
            if (value <= 0.012f) value = 0f;
            else if (value >= 0.988f) value = 1f;
            alpha[i] = (byte) Math.round(clamp(value) * 255f);
        }
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        output.copyPixelsFromBuffer(ByteBuffer.wrap(alpha));
        return output;
    }

    static Bitmap applyMask(Bitmap source, float[] matte, int matteWidth, int matteHeight) {
        Bitmap alpha = createAlphaMask(matte, matteWidth, matteHeight);
        try {
            BitmapUtils.AlphaMaskFlattener flattener = new BitmapUtils.AlphaMaskFlattener(
                    source.getWidth(), source.getHeight());
            return flattener.flatten(source, alpha);
        } finally {
            alpha.recycle();
        }
    }

    /**
     * Petit filtre edge-aware : retire le bruit de matte sans écraser cheveux et transparences.
     * Le flou n'est utilisé que pour détecter le bruit, puis le détail original est réinjecté.
     */
    private static float[] refine(float[] source, int width, int height) {
        if (source == null || source.length != width * height || width < 2 || height < 2) {
            return source == null ? new float[0] : source.clone();
        }
        float[] blurX = new float[source.length];
        float[] blur = new float[source.length];
        float[] output = new float[source.length];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int l = row + Math.max(0, x - 1);
                int c = row + x;
                int r = row + Math.min(width - 1, x + 1);
                blurX[c] = source[l] * 0.22f + source[c] * 0.56f + source[r] * 0.22f;
            }
        }
        for (int y = 0; y < height; y++) {
            int prev = Math.max(0, y - 1) * width;
            int row = y * width;
            int next = Math.min(height - 1, y + 1) * width;
            for (int x = 0; x < width; x++) {
                blur[row + x] = blurX[prev + x] * 0.22f
                        + blurX[row + x] * 0.56f + blurX[next + x] * 0.22f;
            }
        }

        for (int i = 0; i < source.length; i++) {
            float original = clamp(source[i]);
            float local = blur[i];
            float detail = original - local;
            // On préserve davantage les alphas intermédiaires (cheveux, tissu fin, motion blur).
            float edge = 1f - Math.min(1f, Math.abs(original - 0.5f) * 2f);
            float amount = 0.26f + 0.26f * edge;
            float value = original + detail * amount;
            // Nettoyage très léger des zones presque certaines seulement.
            if (value < 0.06f) value *= 0.72f;
            else if (value > 0.94f) value = 1f - (1f - value) * 0.72f;
            output[i] = clamp(value);
        }
        return output;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
