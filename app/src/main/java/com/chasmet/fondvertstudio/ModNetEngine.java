package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Moteur MODNet 100 % local. Le modèle est embarqué dans les assets de l'APK.
 * Preview : entrée courte 256 px. Export : 512 px.
 */
final class ModNetEngine implements AutoCloseable {
    static final String MODEL_ASSET = "modnet_photographic.onnx";
    static final int PREVIEW_SHORT_SIDE = 256;
    static final int EXPORT_SHORT_SIDE = 512;

    static final class Matte {
        final float[] alpha;
        final int width;
        final int height;

        Matte(float[] alpha, int width, int height) {
            this.alpha = alpha;
            this.width = width;
            this.height = height;
        }
    }

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private volatile boolean closed;

    ModNetEngine(Context context) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        File model = copyAssetOnce(context, MODEL_ASSET);
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors() - 1)));
        options.setInterOpNumThreads(1);
        session = environment.createSession(model.getAbsolutePath(), options);
        inputName = session.getInputNames().iterator().next();
    }

    boolean isReady() {
        return !closed;
    }

    Matte predict(Bitmap bitmap, int shortSide) throws OrtException {
        if (closed) throw new IllegalStateException("MODNet fermé");
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int[] size = inferenceSize(originalWidth, originalHeight, shortSide);
        int width = size[0];
        int height = size[1];
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != bitmap) scaled.recycle();

        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(width * height * 3 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer input = byteBuffer.asFloatBuffer();
        // NCHW RGB, normalisation MODNet : (x / 255 - 0.5) / 0.5.
        for (int channel = 0; channel < 3; channel++) {
            for (int pixel : pixels) {
                int value = channel == 0 ? Color.red(pixel)
                        : channel == 1 ? Color.green(pixel) : Color.blue(pixel);
                input.put(value / 127.5f - 1f);
            }
        }
        input.rewind();

        long[] shape = new long[]{1, 3, height, width};
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input, shape);
             OrtSession.Result outputs = session.run(Collections.singletonMap(inputName, tensor))) {
            Object value = outputs.get(0).getValue();
            float[][][][] output = (float[][][][]) value;
            float[] matte = new float[width * height];
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    matte[index++] = clamp(output[0][0][y][x]);
                }
            }
            return new Matte(matte, width, height);
        }
    }

    private static int[] inferenceSize(int originalWidth, int originalHeight, int shortSide) {
        int width;
        int height;
        if (originalWidth >= originalHeight) {
            height = shortSide;
            width = Math.round((float) originalWidth / originalHeight * shortSide);
        } else {
            width = shortSide;
            height = Math.round((float) originalHeight / originalWidth * shortSide);
        }
        width = Math.max(32, width - width % 32);
        height = Math.max(32, height - height % 32);
        int max = Math.max(width, height);
        int cap = shortSide <= PREVIEW_SHORT_SIDE ? 512 : 1024;
        if (max > cap) {
            float scale = (float) cap / max;
            width = Math.max(32, Math.round(width * scale));
            height = Math.max(32, Math.round(height * scale));
            width -= width % 32;
            height -= height % 32;
        }
        return new int[]{width, height};
    }

    private static File copyAssetOnce(Context context, String assetName) throws IOException {
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Impossible de créer le dossier modèles");
        }
        File target = new File(directory, assetName);
        if (target.isFile() && target.length() > 20_000_000L) return target;
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        if (target.length() < 20_000_000L) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("Modèle MODNet incomplet");
        }
        return target;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        closed = true;
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
