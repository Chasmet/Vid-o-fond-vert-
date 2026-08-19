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
        public final float[] mask;
        public final int maskWidth;
        public final int maskHeight;

        Result(Bitmap source, Bitmap cutout, float[] mask, int maskWidth, int maskHeight) {
            this.source = source;
            this.cutout = cutout;
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
    private volatile float threshold = 0.58f;
    private volatile float softness = 0.16f;

    public SegmentationEngine(Context context) {
        SelfieSegmenterOptions streamOptions = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
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

    public void processStream(Bitmap bitmap, @NonNull Callback callback) {
        if (!streamBusy.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        streamSegmenter.process(input)
                .addOnSuccessListener(resultExecutor, mask -> {
                    try {
                        Result result = makeResult(bitmap, mask, threshold, softness);
                        mainHandler.post(() -> callback.onResult(result));
                    } catch (Exception error) {
                        bitmap.recycle();
                        mainHandler.post(() -> callback.onError(error));
                    } finally {
                        streamBusy.set(false);
                    }
                })
                .addOnFailureListener(resultExecutor, error -> {
                    streamBusy.set(false);
                    bitmap.recycle();
                    mainHandler.post(() -> callback.onError(error));
                });
    }

    public void processStill(Bitmap bitmap, @NonNull Callback callback) {
        resultExecutor.execute(() -> {
            try {
                SegmentationMask mask = Tasks.await(
                        stillSegmenter.process(InputImage.fromBitmap(bitmap, 0)),
                        60, TimeUnit.SECONDS);
                Result result = makeResult(bitmap, mask, threshold, softness);
                mainHandler.post(() -> callback.onResult(result));
            } catch (Exception error) {
                bitmap.recycle();
                mainHandler.post(() -> callback.onError(error));
            }
        });
    }

    public Result processStillBlocking(Bitmap bitmap, float localThreshold,
                                       float localSoftness) throws Exception {
        SegmentationMask mask = Tasks.await(
                stillSegmenter.process(InputImage.fromBitmap(bitmap, 0)),
                60, TimeUnit.SECONDS);
        return makeResult(bitmap, mask, localThreshold, localSoftness);
    }

    private static Result makeResult(Bitmap bitmap, SegmentationMask segmentationMask,
                                     float threshold, float softness) {
        int maskWidth = segmentationMask.getWidth();
        int maskHeight = segmentationMask.getHeight();
        FloatBuffer buffer = segmentationMask.getBuffer();
        buffer.rewind();
        float[] mask = new float[maskWidth * maskHeight];
        buffer.get(mask);
        Bitmap cutout = BitmapUtils.applyMask(bitmap, mask, maskWidth, maskHeight,
                threshold, softness);
        return new Result(bitmap, cutout, mask, maskWidth, maskHeight);
    }

    @Override
    public void close() {
        streamSegmenter.close();
        stillSegmenter.close();
        resultExecutor.shutdownNow();
    }
}
