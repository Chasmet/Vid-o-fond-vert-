package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;

import com.baidu.paddle.lite.MobileConfig;
import com.baidu.paddle.lite.PaddlePredictor;
import com.baidu.paddle.lite.PowerMode;
import com.baidu.paddle.lite.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Moteur ultra léger réservé à l'aperçu caméra.
 *
 * PP-HumanSegV2-Lite travaille en 192x192. Il ne remplace pas le moteur de rendu final :
 * l'objectif ici est d'obtenir un retour caméra réactif avec une silhouette humaine stable.
 */
final class PpHumanSegEngine implements AutoCloseable {
    static final int INPUT_WIDTH = 192;
    static final int INPUT_HEIGHT = 192;
    private static final String ASSET_MODEL = "pp_humanseg/model.nb";

    static final class Matte {
        final float[] alpha;
        final int width;
        final int height;
        final long latencyMs;

        Matte(float[] alpha, int width, int height, long latencyMs) {
            this.alpha = alpha;
            this.width = width;
            this.height = height;
            this.latencyMs = latencyMs;
        }
    }

    private PaddlePredictor predictor;
    private float[] previousMask;
    private final int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];
    private final float[] input = new float[3 * INPUT_WIDTH * INPUT_HEIGHT];

    PpHumanSegEngine(Context context) throws Exception {
        // Paddle Lite 2.13-rc provoque un arrêt natif sur certains appareils Android 16/API 36
        // (notamment Honor/MagicOS). Une exception Java ne peut pas intercepter un SIGSEGV natif.
        // On ne charge donc jamais le runtime Paddle sur API 36+ : SegmentationEngine bascule
        // automatiquement vers ML Kit pour l'aperçu, tandis que RVM reste utilisé pour l'export HQ.
        if (Build.VERSION.SDK_INT >= 36) {
            throw new UnsupportedOperationException(
                    "PP-HumanSeg désactivé sur Android 16 : aperçu ML Kit stable");
        }

        Context app = context.getApplicationContext();
        File model = copyAssetIfNeeded(app, ASSET_MODEL, "pp_humanseg_v2/model.nb");
        MobileConfig config = new MobileConfig();
        config.setModelFromFile(model.getAbsolutePath());
        config.setThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        config.setPowerMode(PowerMode.LITE_POWER_HIGH);
        predictor = PaddlePredictor.createPaddlePredictor(config);
        if (predictor == null) throw new IllegalStateException("PP-HumanSeg indisponible");

        // Premier passage de chauffe : il évite de faire payer l'initialisation à la première frame.
        Tensor tensor = predictor.getInput(0);
        tensor.resize(new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH});
        tensor.setData(input);
        predictor.run();
    }

    synchronized Matte predict(Bitmap source) {
        if (predictor == null) throw new IllegalStateException("PP-HumanSeg fermé");
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("Image caméra invalide");
        }

        long startedAt = SystemClock.elapsedRealtime();
        Bitmap scaled = Bitmap.createScaledBitmap(source, INPUT_WIDTH, INPUT_HEIGHT, true);
        try {
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT);
            // PaddleSeg Normalize par défaut : RGB, mean=0.5, std=0.5.
            int plane = INPUT_WIDTH * INPUT_HEIGHT;
            for (int i = 0; i < plane; i++) {
                int color = pixels[i];
                input[i] = (Color.red(color) / 127.5f) - 1f;
                input[plane + i] = (Color.green(color) / 127.5f) - 1f;
                input[plane * 2 + i] = (Color.blue(color) / 127.5f) - 1f;
            }

            Tensor inputTensor = predictor.getInput(0);
            inputTensor.resize(new long[]{1, 3, INPUT_HEIGHT, INPUT_WIDTH});
            inputTensor.setData(input);
            predictor.run();

            Tensor outputTensor = predictor.getOutput(0);
            float[] alpha = readPersonProbability(outputTensor);
            alpha = stabilize(alpha);
            long latency = Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
            return new Matte(alpha, INPUT_WIDTH, INPUT_HEIGHT, latency);
        } finally {
            if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        }
    }

    synchronized void reset() {
        previousMask = null;
    }

    private float[] readPersonProbability(Tensor outputTensor) {
        long[] shape = outputTensor.shape();
        int plane = INPUT_WIDTH * INPUT_HEIGHT;

        try {
            float[] values = outputTensor.getFloatData();
            if (values != null && values.length > 0) {
                float[] person = new float[plane];
                if (values.length == plane) {
                    for (int i = 0; i < plane; i++) person[i] = clamp(values[i]);
                    return person;
                }
                if (values.length >= plane * 2) {
                    // Les modèles exportés PaddleSeg sont généralement NCHW [1,2,H,W].
                    boolean nchw = shape != null && shape.length == 4
                            && shape[1] == 2;
                    if (nchw) {
                        for (int i = 0; i < plane; i++) person[i] = clamp(values[plane + i]);
                    } else {
                        // Tolère aussi NHWC [1,H,W,2].
                        for (int i = 0; i < plane; i++) person[i] = clamp(values[i * 2 + 1]);
                    }
                    return person;
                }
            }
        } catch (Throwable ignored) {
            // Certains modèles Paddle Lite exposent directement une carte d'indices int64.
        }

        long[] labels = outputTensor.getLongData();
        if (labels == null || labels.length < plane) {
            throw new IllegalStateException("Sortie PP-HumanSeg invalide");
        }
        float[] person = new float[plane];
        for (int i = 0; i < plane; i++) person[i] = labels[i] == 1L ? 1f : 0f;
        return person;
    }

    private float[] stabilize(float[] current) {
        if (previousMask == null || previousMask.length != current.length) {
            previousMask = current.clone();
            return current;
        }
        float[] stable = new float[current.length];
        for (int i = 0; i < current.length; i++) {
            float now = clamp(current[i]);
            float old = previousMask[i];
            float diff = Math.abs(now - old);
            // Peu de mouvement = contour très stable. Mouvement rapide = priorité à la frame courante.
            float history = diff < 0.04f ? 0.48f
                    : diff < 0.10f ? 0.34f
                    : diff < 0.22f ? 0.16f : 0.035f;
            if (now < old && diff > 0.12f) history *= 0.55f;
            stable[i] = clamp(now * (1f - history) + old * history);
        }
        previousMask = stable;
        return stable;
    }

    private static File copyAssetIfNeeded(Context context, String assetPath,
                                          String relativeCachePath) throws Exception {
        File target = new File(context.getCacheDir(), relativeCachePath);
        if (target.isFile() && target.length() > 100_000L) return target;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cache PP-HumanSeg inaccessible");
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        if (!target.isFile() || target.length() < 100_000L) {
            throw new IllegalStateException("Modèle PP-HumanSeg incomplet");
        }
        return target;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public synchronized void close() {
        predictor = null;
        previousMask = null;
    }
}
