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
 * Détourage vidéo hybride haute qualité.
 *
 * RVM fournit la continuité temporelle, le foreground RGB réellement extrait et l'alpha fin.
 * ML Kit sert en parallèle de garde-fou sémantique : il empêche un morceau de mur ou de décor
 * proche du corps d'être conservé par le matte. En cas de problème natif, ML Kit reste aussi
 * le moteur de secours complet.
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

    private static final class SemanticMask {
        final float[] values;
        final int width;
        final int height;

        SemanticMask(float[] values, int width, int height) {
            this.values = values;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MLKIT_MAX_DIMENSION = 512;
    private static final int GUIDE_MAX_DIMENSION = 256;
    private static final int STABILIZATION_NONE = 0;
    private static final int STABILIZATION_STREAM = 1;
    private static final int STABILIZATION_EXPORT = 2;

    private final Context appContext;
    private final Segmenter streamSegmenter;
    private final Segmenter stillSegmenter;
    private final ExecutorService resultExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean streamBusy = new AtomicBoolean(false);

    private volatile float threshold = 0.50f;
    private volatile float softness = 0.065f;
    private volatile RvmNcnnEngine rvm;
    private volatile boolean rvmDisabled;
    private volatile int consecutiveRvmFailures;
    private volatile String backendName = "RVM + guide humain · initialisation";

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
                .enableRawSizeMask()
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
        RvmNcnnEngine engine = rvm;
        if (engine != null) {
            try { engine.reset(); } catch (Throwable ignored) { }
        }
        MaskedCameraView.clearPublishedForeground();
    }

    public synchronized void resetExportHistory() {
        previousExportMask = null;
        previousExportWidth = 0;
        previousExportHeight = 0;
        RvmNcnnEngine engine = rvm;
        if (engine != null) {
            try { engine.reset(); } catch (Throwable ignored) { }
        }
    }

    public void processStream(Bitmap bitmap, @NonNull Callback callback) {
        if (!streamBusy.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        resultExecutor.execute(() -> {
            RvmNcnnEngine engine = getRvm();
            if (engine != null) {
                Bitmap inference = null;
                RvmNcnnEngine.Matte matte = null;
                try {
                    inference = BitmapUtils.scaleDown(bitmap,
                            RvmNcnnEngine.PREVIEW_MAX_DIMENSION);
                    matte = engine.predict(inference,
                            RvmNcnnEngine.PREVIEW_MAX_DIMENSION, false);
                    float[] fused = fuseWithSemanticGuide(matte.alpha, matte.width,
                            matte.height, inference, true);
                    Bitmap alphaMask = MattingMaskUtils.createAlphaMask(
                            fused, matte.width, matte.height);

                    // Le View consomme le foreground correspondant exactement à ce masque.
                    MaskedCameraView.publishProcessedForeground(matte.foreground);
                    matte = null;

                    Result result = new Result(bitmap, null, alphaMask, fused,
                            alphaMask.getWidth(), alphaMask.getHeight());
                    consecutiveRvmFailures = 0;
                    backendName = "RVM + humain · foreground propre";
                    streamBusy.set(false);
                    if (inference != bitmap && inference != null && !inference.isRecycled()) {
                        inference.recycle();
                    }
                    mainHandler.post(() -> callback.onResult(result));
                    return;
                } catch (Throwable error) {
                    if (matte != null && matte.foreground != null
                            && !matte.foreground.isRecycled()) {
                        matte.foreground.recycle();
                    }
                    if (inference != bitmap && inference != null && !inference.isRecycled()) {
                        inference.recycle();
                    }
                    noteRvmFailure();
                }
            }
            processStreamWithMlKit(bitmap, callback);
        });
    }

    private void processStreamWithMlKit(Bitmap bitmap, @NonNull Callback callback) {
        final Bitmap inferenceBitmap;
        try {
            inferenceBitmap = BitmapUtils.scaleDown(bitmap, MLKIT_MAX_DIMENSION);
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
            RvmNcnnEngine engine = getRvm();
            if (engine != null) {
                RvmNcnnEngine.Matte matte = null;
                try {
                    engine.reset();
                    matte = engine.predict(bitmap, RvmNcnnEngine.EXPORT_TARGET_SIZE, true);
                    float[] fused = fuseWithSemanticGuide(matte.alpha, matte.width,
                            matte.height, bitmap, false);
                    Bitmap cutout = MattingMaskUtils.applyMask(
                            matte.foreground, fused, matte.width, matte.height);
                    matte.foreground.recycle();
                    matte = null;
                    engine.reset();
                    consecutiveRvmFailures = 0;
                    backendName = "RVM + humain · photo HQ";
                    Result result = new Result(bitmap, cutout, null, fused,
                            bitmap.getWidth(), bitmap.getHeight());
                    mainHandler.post(() -> callback.onResult(result));
                    return;
                } catch (Throwable error) {
                    if (matte != null && matte.foreground != null
                            && !matte.foreground.isRecycled()) matte.foreground.recycle();
                    noteRvmFailure();
                    try { engine.reset(); } catch (Throwable ignored) { }
                }
            }
            processStillWithMlKit(bitmap, callback);
        });
    }

    private void processStillWithMlKit(Bitmap bitmap, @NonNull Callback callback) {
        Bitmap inferenceBitmap = null;
        try {
            inferenceBitmap = BitmapUtils.scaleDown(bitmap, MLKIT_MAX_DIMENSION);
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

    /** Appelé séquentiellement sur toutes les frames exportées : RVM garde sa mémoire. */
    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        RvmNcnnEngine engine = getRvm();
        if (engine != null) {
            RvmNcnnEngine.Matte matte = null;
            try {
                matte = engine.predict(bitmap, RvmNcnnEngine.EXPORT_TARGET_SIZE, true);
                float[] fused = fuseWithSemanticGuide(matte.alpha, matte.width,
                        matte.height, bitmap, false);
                Bitmap alphaMask = MattingMaskUtils.createAlphaMask(
                        fused, matte.width, matte.height);
                Bitmap cleanForeground = matte.foreground;
                matte = null;
                if (bitmap != cleanForeground && !bitmap.isRecycled()) bitmap.recycle();
                consecutiveRvmFailures = 0;
                backendName = "RVM + humain · export HQ";
                return new Result(cleanForeground, null, alphaMask, fused,
                        alphaMask.getWidth(), alphaMask.getHeight());
            } catch (Throwable error) {
                if (matte != null && matte.foreground != null
                        && !matte.foreground.isRecycled()) matte.foreground.recycle();
                noteRvmFailure();
            }
        }

        Bitmap inferenceBitmap = BitmapUtils.scaleDown(bitmap, MLKIT_MAX_DIMENSION);
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

    private float[] fuseWithSemanticGuide(float[] rvmAlpha, int width, int height,
                                          Bitmap bitmap, boolean stream) {
        try {
            SemanticMask guide = createSemanticGuide(bitmap, stream);
            if (guide == null) return rvmAlpha.clone();
            float[] semantic = resizeMask(guide.values, guide.width, guide.height,
                    width, height);
            float[] support = dilateSoft(semantic, width, height, 2);
            float[] fused = new float[rvmAlpha.length];
            for (int i = 0; i < fused.length; i++) {
                float alpha = clamp(rvmAlpha[i]);
                float person = clamp(semantic[i]);
                float nearbyPerson = clamp(support[i]);

                // Hors de la zone humaine, on supprime fermement les morceaux de mur/décor.
                float supportGate = smoothStep(0.045f, 0.34f, nearbyPerson);
                float value = alpha * supportGate;

                // Le centre du corps ne doit pas disparaître même lors d'un mouvement rapide.
                float core = smoothStep(0.58f, 0.90f, person);
                value = Math.max(value, core * 0.965f);

                // Les cheveux/doigts peuvent dépasser légèrement du masque sémantique brut.
                if (alpha > 0.52f && nearbyPerson > 0.12f) {
                    value = Math.max(value, alpha * 0.90f);
                }
                if (person < 0.012f && nearbyPerson < 0.055f) value = 0f;
                fused[i] = clamp(value);
            }
            return fused;
        } catch (Throwable ignored) {
            return rvmAlpha.clone();
        }
    }

    private SemanticMask createSemanticGuide(Bitmap bitmap, boolean stream) throws Exception {
        Bitmap guideBitmap = BitmapUtils.scaleDown(bitmap, GUIDE_MAX_DIMENSION);
        try {
            Segmenter segmenter = stream ? streamSegmenter : stillSegmenter;
            SegmentationMask mask = Tasks.await(
                    segmenter.process(InputImage.fromBitmap(guideBitmap, 0)),
                    8, TimeUnit.SECONDS);
            return readMask(mask);
        } finally {
            if (guideBitmap != bitmap && !guideBitmap.isRecycled()) guideBitmap.recycle();
        }
    }

    private static SemanticMask readMask(SegmentationMask segmentationMask) {
        int width = segmentationMask.getWidth();
        int height = segmentationMask.getHeight();
        ByteBuffer byteBuffer = segmentationMask.getBuffer();
        byteBuffer.rewind();
        FloatBuffer buffer = byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] values = new float[width * height];
        buffer.get(values);
        return new SemanticMask(values, width, height);
    }

    private static float[] resizeMask(float[] source, int sourceWidth, int sourceHeight,
                                      int targetWidth, int targetHeight) {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source.clone();
        float[] output = new float[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            float sy = targetHeight == 1 ? 0f
                    : y * (sourceHeight - 1f) / (targetHeight - 1f);
            int y0 = Math.max(0, Math.min(sourceHeight - 1, (int) sy));
            int y1 = Math.min(sourceHeight - 1, y0 + 1);
            float fy = sy - y0;
            for (int x = 0; x < targetWidth; x++) {
                float sx = targetWidth == 1 ? 0f
                        : x * (sourceWidth - 1f) / (targetWidth - 1f);
                int x0 = Math.max(0, Math.min(sourceWidth - 1, (int) sx));
                int x1 = Math.min(sourceWidth - 1, x0 + 1);
                float fx = sx - x0;
                float top = source[y0 * sourceWidth + x0] * (1f - fx)
                        + source[y0 * sourceWidth + x1] * fx;
                float bottom = source[y1 * sourceWidth + x0] * (1f - fx)
                        + source[y1 * sourceWidth + x1] * fx;
                output[y * targetWidth + x] = clamp(top * (1f - fy) + bottom * fy);
            }
        }
        return output;
    }

    private static float[] dilateSoft(float[] source, int width, int height, int radius) {
        float[] horizontal = new float[source.length];
        float[] output = new float[source.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float max = 0f;
                int from = Math.max(0, x - radius);
                int to = Math.min(width - 1, x + radius);
                for (int xx = from; xx <= to; xx++) max = Math.max(max, source[row + xx]);
                horizontal[row + x] = max;
            }
        }
        for (int y = 0; y < height; y++) {
            int from = Math.max(0, y - radius);
            int to = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                float max = 0f;
                for (int yy = from; yy <= to; yy++) {
                    max = Math.max(max, horizontal[yy * width + x]);
                }
                output[y * width + x] = max;
            }
        }
        return output;
    }

    private synchronized RvmNcnnEngine getRvm() {
        if (rvmDisabled) return null;
        if (rvm != null && rvm.isReady()) return rvm;
        try {
            rvm = new RvmNcnnEngine(appContext);
            consecutiveRvmFailures = 0;
            backendName = "RVM + humain · prêt";
            return rvm;
        } catch (Throwable error) {
            rvmDisabled = true;
            backendName = "ML Kit secours";
            return null;
        }
    }

    private synchronized void noteRvmFailure() {
        consecutiveRvmFailures++;
        RvmNcnnEngine engine = rvm;
        if (engine != null) {
            try { engine.reset(); } catch (Throwable ignored) { }
        }
        if (consecutiveRvmFailures >= 3) {
            rvmDisabled = true;
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) { }
            }
            rvm = null;
            backendName = "ML Kit secours";
        }
    }

    private Result makeMlKitResult(Bitmap bitmap, SegmentationMask segmentationMask,
                                   float threshold, float softness,
                                   boolean createCutout, int stabilizationMode) {
        SemanticMask semantic = readMask(segmentationMask);
        float[] mask = semantic.values;
        if (stabilizationMode != STABILIZATION_NONE) {
            mask = stabilizeMlKitMask(mask, semantic.width, semantic.height, stabilizationMode);
        }
        Bitmap cutout = createCutout
                ? BitmapUtils.applyMask(bitmap, mask, semantic.width, semantic.height,
                threshold, softness)
                : null;
        Bitmap alphaMask = createCutout ? null : BitmapUtils.createAlphaMask(
                mask, semantic.width, semantic.height, threshold, softness);
        return new Result(bitmap, cutout, alphaMask, mask,
                semantic.width, semantic.height);
    }

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
                if (difference < 0.035f) historyWeight = export ? 0.44f : 0.38f;
                else if (difference < 0.08f) historyWeight = export ? 0.34f : 0.29f;
                else if (difference < 0.16f) historyWeight = export ? 0.19f : 0.16f;
                else if (difference < 0.28f) historyWeight = 0.055f;
                else historyWeight = 0.012f;
                if (value < history && difference > 0.08f) historyWeight *= 0.55f;
                stable[index] = clamp(value * (1f - historyWeight) + history * historyWeight);
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

    private static float smoothStep(float low, float high, float value) {
        if (value <= low) return 0f;
        if (value >= high) return 1f;
        float t = (value - low) / Math.max(0.0001f, high - low);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        streamSegmenter.close();
        stillSegmenter.close();
        MaskedCameraView.clearPublishedForeground();
        RvmNcnnEngine engine = rvm;
        if (engine != null) {
            try { engine.close(); } catch (Throwable ignored) { }
        }
        resultExecutor.shutdownNow();
    }
}
