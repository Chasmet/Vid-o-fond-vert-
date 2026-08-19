package com.chasmet.fondvertstudio;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/** Utilitaires pour les vrais alpha mattes produits par RVM. */
final class MattingMaskUtils {
    private MattingMaskUtils() {
    }

    static Bitmap createAlphaMask(float[] source, int width, int height) {
        if (source == null || source.length != width * height) {
            throw new IllegalArgumentException("Matte RVM invalide");
        }
        byte[] alpha = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            float value = clamp(source[i]);
            // Seulement les valeurs pratiquement certaines sont nettoyées.
            // Les alphas intermédiaires (cheveux, motion blur, tissus fins) restent intacts.
            if (value < 0.004f) value = 0f;
            else if (value > 0.996f) value = 1f;
            alpha[i] = (byte) Math.round(value * 255f);
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

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
