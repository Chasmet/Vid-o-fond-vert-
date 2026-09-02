package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
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

    // Plus de définition qu'avant : le masque 512/576 créait des contours mous et grossiers.
    private static final int INFERENCE_MAX_DIMENSION = 640;
    private static final int EXPORT_INFERENCE_MAX_DIMENSION = 768;
    private static final int STABILIZATION_NONE = 0;
    private static final int STABILIZATION_STREAM = 1;
    private static final int STABILIZATION_EXPORT = 2;

    private volatile float threshold = 0.54f;
    private volatile float softness = 0.045f;

    private float[] previousStreamMask;
    private int previousStreamWidth;
    private int previousStreamHeight;

    private float[] previousExportMask;
    private int previousExportWidth;
    private int previousExportHeight;

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
                .enableRawSizeMask()
                .build();
        streamSegmenter = Segmentation.getClient(streamOptions);
        stillSegmenter = Segmentation.getClient(stillOptions);
        exportSegmenter = Segmentation.getClient(exportOptions);
    }

    public void setEdgeSettings(float newThreshold, float newSoftness) {
        threshold = clamp(newThreshold);
        softness = Math.max(0.02f, Math.min(0.09f, newSoftness));
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
                inferenceBitmap = BitmapUtils.scaleDown(bitmap, EXPORT_INFERENCE_MAX_DIMENSION);
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
     * Export qualité : chaque image est réellement ré-analysée. L'ancienne optimisation
     * réutilisait parfois le masque précédent et créait traînées, épaules fantômes et contours
     * rectangulaires. Pour un montage final, la qualité prime sur quelques secondes de calcul.
     */
    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        Bitmap inferenceBitmap = BitmapUtils.scaleDown(
                bitmap, EXPORT_INFERENCE_MAX_DIMENSION);
        try {
            SegmentationMask mask = Tasks.await(
                    exportSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                    60, TimeUnit.SECONDS);
            return makeResult(bitmap, mask,
                    Math.max(0.52f, localThreshold),
                    Math.min(0.055f, Math.max(0.025f, localSoftness)),
                    false, STABILIZATION_EXPORT);
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
        mask = featherFrameBorders(mask, maskWidth, maskHeight,
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
     * Stabilisation temporelle légère. On garde seulement un peu de mémoire dans les zones
     * réellement stables. Les mouvements de bras, tête et cheveux doivent suivre l'image actuelle.
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
                if (difference > 0.30f) hardChanges++;
            }
            float averageDifference = (float) (differenceSum / current.length);
            boolean sceneCut = averageDifference > 0.15f
                    || hardChanges > current.length * 0.18f;

            if (sceneCut) {
                System.arraycopy(current, 0, stable, 0, current.length);
            } else {
                for (int index = 0; index < current.length; index++) {
                    float value = current[index];
                    float history = previous[index];
                    float difference = Math.abs(value - history);
                    float historyWeight;
                    if (difference < 0.025f) {
                        historyWeight = export ? 0.20f : 0.25f;
                    } else if (difference < 0.07f) {
                        historyWeight = export ? 0.12f : 0.18f;
                    } else if (difference < 0.14f) {
                        historyWeight = export ? 0.055f : 0.09f;
                    } else {
                        historyWeight = 0.008f;
                    }
                    if (value < history && difference > 0.05f) {
                        historyWeight *= 0.30f;
                    }
                    stable[index] = clamp(value * (1f - historyWeight)
                            + history * historyWeight);
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

    /** Nettoyage du matte : fond plus propre, sujet plus plein, bord moins laiteux. */
    private static float[] refineConfidenceMask(float[] source, int width, int height,
                                                boolean export) {
        if (width < 3 || height < 3) return source;
        float[] output = source.clone();
        float smoothWeight = export ? 0.10f : 0.13f;
        float confidenceBoost = export ? 0.18f : 0.12f;

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int index = row + x;
                float center = source[index];
                if (center <= 0.035f) {
                    output[index] = 0f;
                    continue;
                }
                if (center >= 0.975f) {
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

                if (localRange < 0.16f && center > 0.12f && center < 0.88f) {
                    value = center * (1f - smoothWeight) + localAverage * smoothWeight;
                }

                if (localAverage < 0.38f && value < 0.56f) {
                    value -= (0.56f - value) * confidenceBoost;
                } else if (localAverage > 0.62f && value > 0.44f) {
                    value += (value - 0.44f) * confidenceBoost;
                }

                output[index] = clamp(value);
            }
        }
        return output;
    }

    /**
     * Élimine l'effet « rectangle caméra » quand le torse ou les épaules touchent le bord
     * de la prise. Les côtés sont très légèrement fondus et le bas est davantage adouci.
     */
    private static float[] featherFrameBorders(float[] source, int width, int height,
                                               boolean export) {
        if (width < 8 || height < 8) return source;
        float[] output = source.clone();
        int sideFeather = Math.max(2, Math.round(width * (export ? 0.020f : 0.015f)));
        int bottomFeather = Math.max(3, Math.round(height * (export ? 0.035f : 0.025f)));

        for (int y = 0; y < height; y++) {
            float bottomFactor = 1f;
            int distanceBottom = height - 1 - y;
            if (distanceBottom < bottomFeather) {
                bottomFactor = smooth01((distanceBottom + 1f) / (bottomFeather + 1f));
            }
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int sideDistance = Math.min(x, width - 1 - x);
                float sideFactor = sideDistance < sideFeather
                        ? smooth01((sideDistance + 1f) / (sideFeather + 1f)) : 1f;
                output[row + x] = clamp(output[row + x] * sideFactor * bottomFactor);
            }
        }
        return output;
    }

    private static float smooth01(float value) {
        float t = clamp(value);
        return t * t * (3f - 2f * t);
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
