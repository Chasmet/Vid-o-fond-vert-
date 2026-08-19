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

/**
 * Moteur hybride de détourage.
 *
 * MODNet est prioritaire et fonctionne entièrement en local via ONNX Runtime. ML Kit est gardé
 * comme secours afin que la caméra reste utilisable si un appareil refuse le modèle ONNX.
 */
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

    private final Context appContext;
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
    private volatile ModNetEngine modNet;
    private volatile boolean modNetDisabled;
    private volatile String backendName = "MODNet (initialisation)";

    private float[] previousStreamMask;
    private int previousStreamWidth;
    private int previousStreamHeight;
    private float[] previousExportMask;
    private int previousExportWidth;
    private int previousExportHeight;

    public SegmentationEngine(Context context) {
        appContext = context.getApplicationContext();
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

    public String getBackendName() {
        return backendName;
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
        resultExecutor.execute(() -> {
            ModNetEngine engine = getModNet();
            if (engine != null) {
                try {
                    ModNetEngine.Matte matte = engine.predict(
                            bitmap, ModNetEngine.PREVIEW_SHORT_SIDE);
                    float[] stable = stabilizeMatte(matte.alpha, matte.width, matte.height,
                            STABILIZATION_STREAM);
                    Bitmap alphaMask = MattingMaskUtils.createAlphaMask(
                            stable, matte.width, matte.height);
                    Result result = new Result(bitmap, null, alphaMask, stable,
                            matte.width, matte.height);
                    backendName = "MODNet local · aperçu";
                    streamBusy.set(false);
                    mainHandler.post(() -> callback.onResult(result));
                    return;
                } catch (Throwable ignored) {
                    disableModNetAfterNativeFailure();
                    // ML Kit prend immédiatement le relais sur ce frame.
                }
            }
            processStreamWithMlKit(bitmap, callback);
        });
    }

    private void processStreamWithMlKit(Bitmap bitmap, @NonNull Callback callback) {
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
                        Result result = makeMlKitResult(bitmap, mask, threshold, softness,
                                false, STABILIZATION_STREAM);
                        backendName = "ML Kit secours";
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
            ModNetEngine engine = getModNet();
            if (engine != null) {
                try {
                    ModNetEngine.Matte matte = engine.predict(
                            bitmap, ModNetEngine.EXPORT_SHORT_SIDE);
                    float[] refined = stabilizeMatte(matte.alpha, matte.width, matte.height,
                            STABILIZATION_NONE);
                    Bitmap cutout = MattingMaskUtils.applyMask(
                            bitmap, refined, matte.width, matte.height);
                    backendName = "MODNet local · qualité";
                    Result result = new Result(bitmap, cutout, null, refined,
                            matte.width, matte.height);
                    mainHandler.post(() -> callback.onResult(result));
                    return;
                } catch (Throwable ignored) {
                    disableModNetAfterNativeFailure();
                }
            }
            processStillWithMlKit(bitmap, callback);
        });
    }

    private void processStillWithMlKit(Bitmap bitmap, @NonNull Callback callback) {
        Bitmap inferenceBitmap = null;
        try {
            inferenceBitmap = BitmapUtils.scaleDown(bitmap, INFERENCE_MAX_DIMENSION);
            SegmentationMask mask = Tasks.await(
                    stillSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                    60, TimeUnit.SECONDS);
            Result result = makeMlKitResult(bitmap, mask, threshold, softness,
                    true, STABILIZATION_NONE);
            backendName = "ML Kit secours";
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
    }

    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        ModNetEngine engine = getModNet();
        if (engine != null) {
            try {
                ModNetEngine.Matte matte = engine.predict(
                        bitmap, ModNetEngine.EXPORT_SHORT_SIDE);
                float[] stable = stabilizeMatte(matte.alpha, matte.width, matte.height,
                        STABILIZATION_EXPORT);
                Bitmap alphaMask = MattingMaskUtils.createAlphaMask(
                        stable, matte.width, matte.height);
                backendName = "MODNet local · export 512";
                return new Result(bitmap, null, alphaMask, stable,
                        matte.width, matte.height);
            } catch (Throwable ignored) {
                disableModNetAfterNativeFailure();
            }
        }

        Bitmap inferenceBitmap = BitmapUtils.scaleDown(bitmap, INFERENCE_MAX_DIMENSION);
        try {
            SegmentationMask mask = Tasks.await(
                    stillSegmenter.process(InputImage.fromBitmap(inferenceBitmap, 0)),
                    60, TimeUnit.SECONDS);
            backendName = "ML Kit secours";
            return makeMlKitResult(bitmap, mask, localThreshold, localSoftness,
                    false, STABILIZATION_EXPORT);
        } finally {
            if (inferenceBitmap != bitmap && !inferenceBitmap.isRecycled()) {
                inferenceBitmap.recycle();
            }
        }
    }

    private synchronized ModNetEngine getModNet() {
        if (modNetDisabled) return null;
        if (modNet != null && modNet.isReady()) return modNet;
        try {
            modNet = new ModNetEngine(appContext);
            backendName = "MODNet local";
            return modNet;
        } catch (Throwable error) {
            disableModNetAfterNativeFailure();
            return null;
        }
    }

    private synchronized void disableModNetAfterNativeFailure() {
        modNetDisabled = true;
        backendName = "ML Kit secours";
        ModNetEngine engine = modNet;
        modNet = null;
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private Result makeMlKitResult(Bitmap bitmap, SegmentationMask segmentationMask,
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
            mask = stabilizeMlKitMask(mask, maskWidth, maskHeight, stabilizationMode);
        }
        Bitmap cutout = createCutout
                ? BitmapUtils.applyMask(bitmap, mask, maskWidth, maskHeight,
                threshold, softness)
                : null;
        Bitmap alphaMask = createCutout ? null : BitmapUtils.createAlphaMask(
                mask, maskWidth, maskHeight, threshold, softness);
        return new Result(bitmap, cutout, alphaMask, mask, maskWidth, maskHeight);
    }

    /** Stabilisation douce adaptée aux alphas MODNet : conserve cheveux et semi-transparences. */
    private synchronized float[] stabilizeMatte(float[] current, int width, int height,
                                                int mode) {
        if (mode == STABILIZATION_NONE) return current.clone();
        boolean export = mode == STABILIZATION_EXPORT;
        float[] previous = export ? previousExportMask : previousStreamMask;
        int previousWidth = export ? previousExportWidth : previousStreamWidth;
        int previousHeight = export ? previousExportHeight : previousStreamHeight;
        float[] stable = new float[current.length];

        if (previous == null || previousWidth != width || previousHeight != height
                || previous.length != current.length) {
            System.arraycopy(current, 0, stable, 0, current.length);
        } else {
            for (int i = 0; i < current.length; i++) {
                float value = clamp(current[i]);
                float history = clamp(previous[i]);
                float difference = Math.abs(value - history);
                float historyWeight;
                if (difference < 0.025f) historyWeight = export ? 0.48f : 0.38f;
                else if (difference < 0.07f) historyWeight = export ? 0.35f : 0.27f;
                else if (difference < 0.15f) historyWeight = export ? 0.18f : 0.13f;
                else historyWeight = 0.025f;
                if (value < history && difference > 0.07f) historyWeight *= 0.45f;
                stable[i] = clamp(value * (1f - historyWeight) + history * historyWeight);
            }
        }
        saveHistory(stable, width, height, export);
        return stable;
    }

    /** Ancienne stabilisation conservée uniquement pour le fallback ML Kit. */
    private synchronized float[] stabilizeMlKitMask(float[] current, int width, int height,
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
                if (value < history && difference > 0.08f) historyWeight *= 0.55f;
                float blended = value * (1f - historyWeight) + history * historyWeight;
                if (difference > 0.12f) {
                    float motion = Math.min(1f, (difference - 0.12f) / 0.40f);
                    blended = clamp(0.5f + (blended - 0.5f) * (1f + 0.14f * motion));
                }
                stable[index] = clamp(blended);
            }
        }
        saveHistory(stable, width, height, export);
        return stable;
    }

    private void saveHistory(float[] stable, int width, int height, boolean export) {
        if (export) {
            previousExportMask = stable;
            previousExportWidth = width;
            previousExportHeight = height;
        } else {
            previousStreamMask = stable;
            previousStreamWidth = width;
            previousStreamHeight = height;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        streamSegmenter.close();
        stillSegmenter.close();
        ModNetEngine engine = modNet;
        modNet = null;
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
            }
        }
        resultExecutor.shutdownNow();
    }
}
