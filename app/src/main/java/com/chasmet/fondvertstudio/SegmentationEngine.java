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
    private final ExecutorService resultExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean streamBusy = new AtomicBoolean(false);
    private static final int INFERENCE_MAX_DIMENSION = 512;
    private static final int STABILIZATION_NONE = 0;
    private static final int STABILIZATION_STREAM = 1;
    private static final int STABILIZATION_EXPORT = 2;
    private volatile float threshold = 0.50f;
    private volatile float softness = 0.065f;
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
        streamSegmenter = Segmentation.getClient(streamOptions);
        stillSegmenter = Segmentation.getClient(stillOptions);
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

    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        Bitmap inferenceBitmap = BitmapUtils.scaleDown(
                bitmap, INFERENCE_MAX_DIMENSION);
        try {
            SegmentationMask mask = Tasks.await(
                    stillSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                    60, TimeUnit.SECONDS);
            return makeResult(bitmap, mask, localThreshold, localSoftness,
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
        Bitmap cutout = createCutout
                ? BitmapUtils.applyMask(bitmap, mask, maskWidth, maskHeight,
                threshold, softness)
                : null;
        Bitmap alphaMask = createCutout ? null : BitmapUtils.createAlphaMask(
                mask, maskWidth, maskHeight, threshold, softness);
        return new Result(bitmap, cutout, alphaMask, mask, maskWidth, maskHeight);
    }

    /**
     * Réduit le scintillement d'une image à l'autre sans conserver une silhouette
     * fantôme : plus un pixel bouge, moins le masque précédent intervient.
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
            for (int index = 0; index < current.length; index++) {
                float value = current[index];
                float history = previous[index];
                float difference = Math.abs(value - history);
                float historyWeight;
                if (difference < 0.035f) {
                    historyWeight = export ? 0.44f : 0.38f;
                } else if (difference < 0.08f) {
                    historyWeight = export ? 0.34f : 0.29f;
                } else if (difference < 0.16f) {
                    historyWeight = export ? 0.19f : 0.16f;
                } else if (difference < 0.28f) {
                    historyWeight = 0.055f;
                } else {
                    historyWeight = 0.012f;
                }
                // Le fond doit réapparaître vite derrière un bras ou des cheveux.
                if (value < history && difference > 0.08f) {
                    historyWeight *= 0.55f;
                }
                float blended = value * (1f - historyWeight)
                        + history * historyWeight;
                if (difference > 0.12f) {
                    float motion = Math.min(1f, (difference - 0.12f) / 0.40f);
                    blended = clamp(0.5f + (blended - 0.5f)
                            * (1f + 0.14f * motion));
                }
                stable[index] = clamp(blended);
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

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        streamSegmenter.close();
        stillSegmenter.close();
        resultExecutor.shutdownNow();
    }
}
