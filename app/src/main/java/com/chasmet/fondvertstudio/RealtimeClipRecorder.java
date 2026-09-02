package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encode le sujet détouré + décor pendant le tournage.
 * La finalisation n'a donc plus à recalculer chaque image de la vidéo.
 */
final class RealtimeClipRecorder implements AutoCloseable {
    interface FinishCallback {
        void onFinished(File videoOnly, Exception error);
    }

    private final Context context;
    private final int width;
    private final int height;
    private final float threshold;
    private final float softness;
    private final File outputFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean framePending = new AtomicBoolean(false);
    private final BitmapUtils.AlphaMaskFlattener maskFlattener;

    private H264FrameEncoder encoder;
    private ClipBackgroundProvider backgroundProvider;
    private volatile boolean finishing;
    private volatile boolean closed;
    private volatile Exception fatalError;
    private long lastPresentationUs = -1L;
    private int encodedFrames;
    private int consecutiveFrameErrors;

    RealtimeClipRecorder(Context context, int width, int height,
                         float threshold, float softness) throws IOException {
        this.context = context.getApplicationContext();
        this.width = width;
        this.height = height;
        this.threshold = threshold;
        this.softness = softness;
        this.maskFlattener = new BitmapUtils.AlphaMaskFlattener(width, height);
        File directory = new File(context.getCacheDir(), "realtime_clips");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Dossier de montage temps réel inaccessible");
        }
        outputFile = new File(directory,
                "clip_realtime_" + System.currentTimeMillis() + ".mp4");
        executor.execute(() -> {
            try {
                encoder = new H264FrameEncoder(outputFile, width, height, 30);
            } catch (Exception error) {
                fatalError = asException(error);
            }
        });
    }

    void beginPlan(BackgroundSpec.Type type, Uri uri, int color) {
        if (finishing || closed) return;
        executor.execute(() -> {
            closeBackground();
            if (fatalError != null) return;
            try {
                backgroundProvider = new ClipBackgroundProvider(
                        context, type, uri, color, Math.max(width, height));
            } catch (Exception error) {
                // Un décor vidéo/image défaillant ne doit pas tuer le clip entier.
                try {
                    backgroundProvider = new ClipBackgroundProvider(
                            context, BackgroundSpec.Type.COLOR, null, color,
                            Math.max(width, height));
                } catch (Exception ignored) {
                    fatalError = asException(error);
                }
            }
        });
    }

    boolean offerFrame(Bitmap source, float[] mask, int maskWidth, int maskHeight,
                       long outputTimeUs, long localTimeUs,
                       float subjectScale, float centerX, float centerY) {
        if (source == null || source.isRecycled() || mask == null
                || maskWidth <= 0 || maskHeight <= 0 || finishing || closed
                || fatalError != null) {
            recycle(source);
            return false;
        }
        if (!framePending.compareAndSet(false, true)) {
            recycle(source);
            return false;
        }
        executor.execute(() -> {
            Bitmap prepared = null;
            Bitmap alphaMask = null;
            Bitmap cutout = null;
            Bitmap background = null;
            Bitmap composite = null;
            try {
                if (encoder == null || fatalError != null) return;
                prepared = BitmapUtils.centerCrop(source, width, height);
                alphaMask = BitmapUtils.createAlphaMask(mask, maskWidth, maskHeight,
                        threshold, softness);
                cutout = maskFlattener.flatten(prepared, alphaMask);
                ClipBackgroundProvider provider = backgroundProvider;
                int backgroundColor = 0xFF00FF00;
                if (provider != null) {
                    background = provider.frameAt(localTimeUs, width, height);
                    backgroundColor = provider.getColor();
                }
                composite = BitmapUtils.composite(cutout, background, backgroundColor,
                        width, height, subjectScale, centerX, centerY);
                long presentationUs = Math.max(lastPresentationUs + 1_000L,
                        Math.max(0L, outputTimeUs));
                encoder.encode(composite, presentationUs);
                lastPresentationUs = presentationUs;
                encodedFrames++;
                consecutiveFrameErrors = 0;
            } catch (Exception error) {
                consecutiveFrameErrors++;
                if (consecutiveFrameErrors >= 12) {
                    fatalError = asException(error);
                }
            } finally {
                recycle(composite);
                recycle(background);
                recycle(cutout);
                recycle(alphaMask);
                recycle(prepared);
                recycle(source);
                framePending.set(false);
            }
        });
        return true;
    }

    void finish(FinishCallback callback) {
        if (closed) {
            mainHandler.post(() -> callback.onFinished(null,
                    new IOException("Encodeur temps réel déjà fermé")));
            return;
        }
        finishing = true;
        executor.execute(() -> {
            Exception error = fatalError;
            File result = null;
            try {
                if (encoder != null) {
                    if (error == null && encodedFrames > 0) {
                        encoder.finish();
                    }
                    encoder.close();
                    encoder = null;
                }
                closeBackground();
                if (error == null && encodedFrames > 0
                        && outputFile.isFile() && outputFile.length() > 0L) {
                    result = outputFile;
                } else if (error == null) {
                    error = new IOException("Aucune image temps réel encodée");
                }
            } catch (Exception finishError) {
                error = asException(finishError);
            }
            if (result == null && outputFile.exists()) outputFile.delete();
            closed = true;
            executor.shutdown();
            File finalResult = result;
            Exception finalError = error;
            mainHandler.post(() -> callback.onFinished(finalResult, finalError));
        });
    }

    private void closeBackground() {
        if (backgroundProvider != null) {
            try { backgroundProvider.close(); } catch (Exception ignored) { }
            backgroundProvider = null;
        }
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception ? (Exception) error : new IOException(error);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    @Override
    public void close() {
        if (closed) return;
        finishing = true;
        executor.execute(() -> {
            try {
                if (encoder != null) {
                    encoder.close();
                    encoder = null;
                }
            } catch (Exception ignored) { }
            closeBackground();
            if (outputFile.exists()) outputFile.delete();
            closed = true;
            executor.shutdown();
        });
    }
}
