package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SegmentationEngine implements AutoCloseable {
    public interface Callback {
        void onResult(Result result);

        void onError(Exception error);
    }

    public static final class Result {
        public final Bitmap source;
        public final Bitmap cutout;
        public final Bitmap alphaMask;
        public final float[] mask;
        public final int maskWidth;
        public final int maskHeight;

        Result(Bitmap source, Bitmap cutout, Bitmap alphaMask, float[] mask,
               int maskWidth, int maskHeight) {
            this.source = source;
            this.cutout = cutout;
            this.alphaMask = alphaMask;
            this.mask = mask;
            this.maskWidth = maskWidth;
            this.maskHeight = maskHeight;
        }
    }

    private final Segmenter streamSegmenter;
    private final Segmenter stillSegmenter;
    private final Segmenter exportSegmenter;
    private final ExecutorService resultExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean streamBusy = new AtomicBoolean(false);

    private static final int INFERENCE_MAX_DIMENSION = 512;
    private static final int EXPORT_INFERENCE_MAX_DIMENSION = 576;
    private static final int STABILIZATION_NONE = 0;
    private static final int STABILIZATION_STREAM = 1;
    private static final int STABILIZATION_EXPORT = 2;
    private static final int EXPORT_SAMPLE_GRID = 12;
    private static final float EXPORT_REUSE_MAX_LUMA_DELTA = 0.040f;

    private volatile float threshold = 0.50f;
    private volatile float softness = 0.065f;

    private float[] previousStreamMask;
    private int previousStreamWidth;
    private int previousStreamHeight;

    private float[] previousExportMask;
    private int previousExportWidth;
    private int previousExportHeight;
    private float[] exportReferenceLuma;
    private int exportFramesSinceInference;

    public SegmentationEngine(Context context) {
        SelfieSegmenterOptions streamOptions = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build();
        SelfieSegmenterOptions stillOptions = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build();
        SelfieSegmenterOptions exportOptions = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build();
        streamSegmenter = Segmentation.getClient(streamOptions);
        stillSegmenter = Segmentation.getClient(stillOptions);
        exportSegmenter = Segmentation.getClient(exportOptions);
    }

    public void setEdgeSettings(float newThreshold, float newSoftness) {
        threshold = newThreshold;
        softness = newSoftness;
    }

    public boolean isStreamBusy() {
        return streamBusy.get();
    }

    public synchronized void resetStreamHistory() {
        previousStreamMask = null;
        previousStreamWidth = 0;
        previousStreamHeight = 0;
    }

    public synchronized void resetExportHistory() {
        previousExportMask = null;
        previousExportWidth = 0;
        previousExportHeight = 0;
        exportReferenceLuma = null;
        exportFramesSinceInference = 0;
    }

    public void processStream(Bitmap bitmap, @NonNull Callback callback) {
        if (!streamBusy.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        final Bitmap inferenceBitmap;
        try {
            inferenceBitmap = BitmapUtils.scaleDown(bitmap, INFERENCE_MAX_DIMENSION);
        } catch (Exception error) {
            streamBusy.set(false);
            bitmap.recycle();
            mainHandler.post(() -> callback.onError(error));
            return;
        }
        InputImage input = InputImage.fromBitmap(inferenceBitmap, 0);
        streamSegmenter.process(input)
                .addOnSuccessListener(resultExecutor, mask -> {
                    try {
                        Result result = makeResult(bitmap, mask, threshold, softness,
                                false, STABILIZATION_STREAM);
                        mainHandler.post(() -> callback.onResult(result));
                    } catch (Exception error) {
                        bitmap.recycle();
                        mainHandler.post(() -> callback.onError(error));
                    } finally {
                        if (inferenceBitmap != bitmap && !inferenceBitmap.isRecycled()) {
                            inferenceBitmap.recycle();
                        }
                        streamBusy.set(false);
                    }
                })
                .addOnFailureListener(resultExecutor, error -> {
                    streamBusy.set(false);
                    if (inferenceBitmap != bitmap && !inferenceBitmap.isRecycled()) {
                        inferenceBitmap.recycle();
                    }
                    bitmap.recycle();
                    mainHandler.post(() -> callback.onError(error));
                });
    }

    public void processStill(Bitmap bitmap, @NonNull Callback callback) {
        resultExecutor.execute(() -> {
            Bitmap inferenceBitmap = null;
            try {
                inferenceBitmap = BitmapUtils.scaleDown(bitmap, INFERENCE_MAX_DIMENSION);
                SegmentationMask mask = Tasks.await(
                        stillSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                        60, TimeUnit.SECONDS);
                Result result = makeResult(bitmap, mask, threshold, softness,
                        true, STABILIZATION_NONE);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception error) {
                bitmap.recycle();
                mainHandler.post(() -> callback.onError(error));
            } finally {
                if (inferenceBitmap != null && inferenceBitmap != bitmap
                        && !inferenceBitmap.isRecycled()) {
                    inferenceBitmap.recycle();
                }
            }
        });
    }

    /**
     * Export vidéo optimisé : le segmenter STREAM conserve mieux les contours d'une image
     * à l'autre. Sur une image quasi identique à la précédente, un seul masque peut être
     * réutilisé pendant une frame maximum. Les mouvements rapides relancent immédiatement
     * l'inférence, ce qui évite la traîne tout en réduisant nettement le coût des plans fixes.
     */
    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        float[] lumaSample = sampleLuma(bitmap, EXPORT_SAMPLE_GRID);
        float[] reusableMask = null;
        int reusableWidth = 0;
        int reusableHeight = 0;

        synchronized (this) {
            if (previousExportMask != null
                    && exportReferenceLuma != null
                    && exportFramesSinceInference < 1
                    && meanAbsoluteDifference(exportReferenceLuma, lumaSample)
                    <= EXPORT_REUSE_MAX_LUMA_DELTA) {
                reusableMask = previousExportMask;
                reusableWidth = previousExportWidth;
                reusableHeight = previousExportHeight;
                exportFramesSinceInference++;
            }
        }

        if (reusableMask != null && reusableWidth > 0 && reusableHeight > 0) {
            Bitmap alphaMask = BitmapUtils.createAlphaMask(
                    reusableMask, reusableWidth, reusableHeight,
                    localThreshold, localSoftness);
            return new Result(bitmap, null, alphaMask, reusableMask,
                    reusableWidth, reusableHeight);
        }

        Bitmap inferenceBitmap = BitmapUtils.scaleDown(
                bitmap, EXPORT_INFERENCE_MAX_DIMENSION);
        try {
            SegmentationMask mask = Tasks.await(
                    exportSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                    60, TimeUnit.SECONDS);
            Result result = makeResult(bitmap, mask, localThreshold, localSoftness,
                    false, STABILIZATION_EXPORT);
            synchronized (this) {
                exportReferenceLuma = lumaSample;
                exportFramesSinceInference = 0;
            }
            return result;
        } finally {
            if (inferenceBitmap != bitmap && !inferenceBitmap.isRecycled()) {
                inferenceBitmap.recycle();
            }
        }
    }

    private Result makeResult(Bitmap bitmap, SegmentationMask segmentationMask,
                              float threshold, float softness,
                              boolean createCutout, int stabilizationMode) {
        int maskWidth = segmentationMask.getWidth();
        int maskHeight = segmentationMask.getHeight();
        ByteBuffer byteBuffer = segmentationMask.getBuffer();
        byteBuffer.rewind();
        FloatBuffer buffer = byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] mask = new float[maskWidth * maskHeight];
        buffer.get(mask);
        if (stabilizationMode != STABILIZATION_NONE) {
            mask = stabilizeMask(mask, maskWidth, maskHeight, stabilizationMode);
        }
        mask = refineConfidenceMask(mask, maskWidth, maskHeight,
                stabilizationMode == STABILIZATION_EXPORT);

        Bitmap cutout = createCutout
                ? BitmapUtils.applyMask(bitmap, mask, maskWidth, maskHeight,
                threshold, softness)
                : null;
        Bitmap alphaMask = createCutout ? null : BitmapUtils.createAlphaMask(
                mask, maskWidth, maskHeight, threshold, softness);
        return new Result(bitmap, cutout, alphaMask, mask, maskWidth, maskHeight);
    }

    /**
     * Stabilisation temporelle avec détection de changement de plan. Un cut franc remet
     * immédiatement l'historique à zéro au lieu de mélanger deux silhouettes différentes.
     */
    private synchronized float[] stabilizeMask(float[] current, int width, int height,
                                               int mode) {
        boolean export = mode == STABILIZATION_EXPORT;
        float[] previous = export ? previousExportMask : previousStreamMask;
        int previousWidth = export ? previousExportWidth : previousStreamWidth;
        int previousHeight = export ? previousExportHeight : previousStreamHeight;
        float[] stable = new float[current.length];

        if (previous == null || previousWidth != width || previousHeight != height
                || previous.length != current.length) {
            System.arraycopy(current, 0, stable, 0, current.length);
        } else {
            long hardChanges = 0L;
            double differenceSum = 0d;
            for (int index = 0; index < current.length; index++) {
                float difference = Math.abs(current[index] - previous[index]);
                differenceSum += difference;
                if (difference > 0.34f) hardChanges++;
            }
            float averageDifference = (float) (differenceSum / current.length);
            boolean sceneCut = averageDifference > 0.19f
                    || hardChanges > current.length * 0.22f;

            if (sceneCut) {
                System.arraycopy(current, 0, stable, 0, current.length);
            } else {
                for (int index = 0; index < current.length; index++) {
                    float value = current[index];
                    float history = previous[index];
                    float difference = Math.abs(value - history);
                    float historyWeight;
                    if (difference < 0.035f) {
                        historyWeight = export ? 0.40f : 0.36f;
                    } else if (difference < 0.08f) {
                        historyWeight = export ? 0.30f : 0.27f;
                    } else if (difference < 0.16f) {
                        historyWeight = export ? 0.16f : 0.14f;
                    } else if (difference < 0.28f) {
                        historyWeight = 0.045f;
                    } else {
                        historyWeight = 0.006f;
                    }
                    // Le fond doit réapparaître vite derrière un bras, une main ou des cheveux.
                    if (value < history && difference > 0.07f) {
                        historyWeight *= 0.46f;
                    }
                    float blended = value * (1f - historyWeight)
                            + history * historyWeight;
                    if (difference > 0.12f) {
                        float motion = Math.min(1f, (difference - 0.12f) / 0.40f);
                        blended = clamp(0.5f + (blended - 0.5f)
                                * (1f + 0.12f * motion));
                    }
                    stable[index] = clamp(blended);
                }
            }
        }

        if (export) {
            previousExportMask = stable;
            previousExportWidth = width;
            previousExportHeight = height;
        } else {
            previousStreamMask = stable;
            previousStreamWidth = width;
            previousStreamHeight = height;
        }
        return stable;
    }

    /**
     * Nettoyage spatial léger et respectueux des cheveux : les zones uniformes sont rendues
     * plus franches tandis que les zones à fort contraste restent proches du masque ML brut.
     */
    private static float[] refineConfidenceMask(float[] source, int width, int height,
                                                boolean export) {
        if (width < 3 || height < 3) return source;
        float[] output = source.clone();
        float smoothWeight = export ? 0.22f : 0.16f;
        float confidenceBoost = export ? 0.12f : 0.08f;

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int index = row + x;
                float center = source[index];
                if (center <= 0.025f) {
                    output[index] = 0f;
                    continue;
                }
                if (center >= 0.985f) {
                    output[index] = 1f;
                    continue;
                }

                float left = source[index - 1];
                float right = source[index + 1];
                float top = source[index - width];
                float bottom = source[index + width];
                float min = Math.min(center,
                        Math.min(Math.min(left, right), Math.min(top, bottom)));
                float max = Math.max(center,
                        Math.max(Math.max(left, right), Math.max(top, bottom)));
                float localRange = max - min;
                float localAverage = (center * 2f + left + right + top + bottom) / 6f;
                float value = center;

                // Lisser uniquement les surfaces calmes ; les mèches et contours restent nets.
                if (localRange < 0.22f && center > 0.10f && center < 0.90f) {
                    value = center * (1f - smoothWeight) + localAverage * smoothWeight;
                }

                // Écraser les voiles de fond et remplir les petits trous à haute confiance.
                if (localAverage < 0.34f && value < 0.55f) {
                    value -= (0.55f - value) * confidenceBoost;
                } else if (localAverage > 0.66f && value > 0.45f) {
                    value += (value - 0.45f) * confidenceBoost;
                }

                output[index] = clamp(value);
            }
        }
        return output;
    }

    private static float[] sampleLuma(Bitmap bitmap, int grid) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float[] sample = new float[grid * grid];
        int index = 0;
        for (int gy = 0; gy < grid; gy++) {
            int y = Math.min(height - 1,
                    Math.max(0, Math.round((gy + 0.5f) * height / grid - 0.5f)));
            for (int gx = 0; gx < grid; gx++) {
                int x = Math.min(width - 1,
                        Math.max(0, Math.round((gx + 0.5f) * width / grid - 0.5f)));
                int color = bitmap.getPixel(x, y);
                sample[index++] = (0.2126f * Color.red(color)
                        + 0.7152f * Color.green(color)
                        + 0.0722f * Color.blue(color)) / 255f;
            }
        }
        return sample;
    }

    private static float meanAbsoluteDifference(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 1f;
        }
        float sum = 0f;
        for (int index = 0; index < left.length; index++) {
            sum += Math.abs(left[index] - right[index]);
        }
        return sum / left.length;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        streamSegmenter.close();
        stillSegmenter.close();
        exportSegmenter.close();
        resultExecutor.shutdownNow();
    }
}
