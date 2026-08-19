package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class VideoExportWorker extends Worker {
    public static final String KEY_SOURCE_URI = "source_uri";
    public static final String KEY_BACKGROUND_TYPE = "background_type";
    public static final String KEY_BACKGROUND_URI = "background_uri";
    public static final String KEY_BACKGROUND_COLOR = "background_color";
    public static final String KEY_THRESHOLD = "threshold";
    public static final String KEY_SOFTNESS = "softness";
    public static final String KEY_QUALITY = "quality";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_OUTPUT_URI = "output_uri";
    public static final String KEY_ERROR = "error";

    public VideoExportWorker(@NonNull Context appContext,
                             @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String sourceValue = getInputData().getString(KEY_SOURCE_URI);
        if (sourceValue == null) {
            return failure("Vidéo source absente");
        }
        Uri sourceUri = Uri.parse(sourceValue);
        String typeValue = getInputData().getString(KEY_BACKGROUND_TYPE);
        BackgroundSpec.Type backgroundType;
        try {
            backgroundType = BackgroundSpec.Type.valueOf(typeValue == null
                    ? BackgroundSpec.Type.TRANSPARENT.name() : typeValue);
        } catch (IllegalArgumentException ignored) {
            backgroundType = BackgroundSpec.Type.TRANSPARENT;
        }
        String backgroundValue = getInputData().getString(KEY_BACKGROUND_URI);
        Uri backgroundUri = backgroundValue == null ? null : Uri.parse(backgroundValue);
        int backgroundColor = getInputData().getInt(KEY_BACKGROUND_COLOR, Color.GREEN);
        if (backgroundType == BackgroundSpec.Type.TRANSPARENT) {
            backgroundColor = Color.rgb(0, 255, 0);
        }
        float threshold = getInputData().getFloat(KEY_THRESHOLD, 0.58f);
        float softness = getInputData().getFloat(KEY_SOFTNESS, 0.16f);
        int quality = getInputData().getInt(KEY_QUALITY, 720);

        Context context = getApplicationContext();
        File workDirectory = new File(context.getCacheDir(), "video_exports");
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            return failure("Dossier temporaire inaccessible");
        }
        String token = String.valueOf(System.currentTimeMillis());
        File videoOnly = new File(workDirectory, "video_" + token + ".mp4");
        File finalVideo = new File(workDirectory, "final_" + token + ".mp4");

        MediaMetadataRetriever sourceRetriever = new MediaMetadataRetriever();
        BackgroundProvider backgroundProvider = null;
        SegmentationEngine segmenter = null;
        H264FrameEncoder encoder = null;
        try {
            setDataSource(sourceRetriever, context, sourceUri);
            long durationMs = readLong(sourceRetriever,
                    MediaMetadataRetriever.METADATA_KEY_DURATION, 0L);
            if (durationMs <= 0L) {
                throw new IOException("Durée vidéo invalide");
            }
            int metadataWidth = readInt(sourceRetriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 720);
            int metadataHeight = readInt(sourceRetriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 1280);
            int rotation = readInt(sourceRetriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
            if (rotation == 90 || rotation == 270) {
                int swap = metadataWidth;
                metadataWidth = metadataHeight;
                metadataHeight = swap;
            }
            int maxWidth = metadataWidth >= metadataHeight ? quality * 16 / 9 : quality;
            int maxHeight = metadataWidth >= metadataHeight ? quality : quality * 16 / 9;
            int[] outputSize = BitmapUtils.fitInside(metadataWidth, metadataHeight,
                    maxWidth, maxHeight);
            int width = outputSize[0];
            int height = outputSize[1];
            int frameRate = 24;
            int frameCount = Math.max(1, (int) Math.ceil(durationMs * frameRate / 1000d));
            long durationUs = durationMs * 1000L;

            segmenter = new SegmentationEngine(context);
            backgroundProvider = new BackgroundProvider(context, backgroundType,
                    backgroundUri, backgroundColor, Math.max(width, height));
            encoder = new H264FrameEncoder(videoOnly, width, height, frameRate);

            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                if (isStopped()) {
                    throw new IOException("Export annulé");
                }
                long timeUs = frameIndex * 1_000_000L / frameRate;
                Bitmap frame = sourceRetriever.getFrameAtTime(
                        Math.min(timeUs, durationUs - 1),
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    continue;
                }
                frame = orientFrame(frame, rotation, metadataWidth, metadataHeight);
                Bitmap prepared = BitmapUtils.centerCrop(frame, width, height);
                frame.recycle();

                SegmentationEngine.Result segmented = segmenter.processStillBlocking(
                        prepared, threshold, softness);
                Bitmap background = backgroundProvider.frameAt(timeUs, width, height);
                Bitmap composite = BitmapUtils.composite(segmented.cutout, background,
                        backgroundProvider.getColor(), width, height);
                encoder.encode(composite, timeUs);

                composite.recycle();
                segmented.cutout.recycle();
                segmented.source.recycle();
                if (background != null) {
                    background.recycle();
                }
                if (frameIndex % 3 == 0 || frameIndex == frameCount - 1) {
                    int progress = Math.min(96,
                            Math.round((frameIndex + 1) * 96f / frameCount));
                    setProgressAsync(new Data.Builder()
                            .putInt(KEY_PROGRESS, progress).build());
                }
            }
            encoder.finish();
            encoder.close();
            encoder = null;
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, 97).build());

            MuxerUtils.addSourceAudio(context, videoOnly, sourceUri, finalVideo, durationUs);
            String name = String.format(Locale.US, "FondVert_%d.mp4",
                    System.currentTimeMillis());
            Uri outputUri = MediaStoreSaver.saveVideo(context, finalVideo, name);
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, 100).build());
            return Result.success(new Data.Builder()
                    .putString(KEY_OUTPUT_URI, outputUri.toString()).build());
        } catch (Exception error) {
            return failure(error.getMessage() == null
                    ? "Échec de l’export vidéo" : error.getMessage());
        } finally {
            try {
                sourceRetriever.release();
            } catch (IOException ignored) {
            }
            if (backgroundProvider != null) {
                backgroundProvider.close();
            }
            if (segmenter != null) {
                segmenter.close();
            }
            if (encoder != null) {
                encoder.close();
            }
            if (videoOnly.exists()) {
                //noinspection ResultOfMethodCallIgnored
                videoOnly.delete();
            }
            if (finalVideo.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalVideo.delete();
            }
        }
    }

    private Result failure(String message) {
        return Result.failure(new Data.Builder().putString(KEY_ERROR, message).build());
    }

    private static Bitmap orientFrame(Bitmap bitmap, int rotation,
                                      int orientedWidth, int orientedHeight) {
        boolean expectedPortrait = orientedHeight > orientedWidth;
        boolean actualPortrait = bitmap.getHeight() > bitmap.getWidth();
        if ((rotation == 90 || rotation == 270) && expectedPortrait != actualPortrait) {
            return BitmapUtils.rotateAndMirror(bitmap, rotation, false);
        }
        if (rotation == 180) {
            return BitmapUtils.rotateAndMirror(bitmap, 180, false);
        }
        return bitmap;
    }

    private static void setDataSource(MediaMetadataRetriever retriever,
                                      Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            retriever.setDataSource(uri.getPath());
        } else {
            retriever.setDataSource(context, uri);
        }
    }

    private static int readInt(MediaMetadataRetriever retriever, int key, int fallback) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long readLong(MediaMetadataRetriever retriever, int key, long fallback) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? fallback : Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class BackgroundProvider implements AutoCloseable {
        private final BackgroundSpec.Type type;
        private final int color;
        private Bitmap image;
        private MediaMetadataRetriever videoRetriever;
        private long videoDurationUs;

        BackgroundProvider(Context context, BackgroundSpec.Type type, Uri uri,
                           int color, int maxDimension) throws IOException {
            this.type = type;
            this.color = color;
            if (type == BackgroundSpec.Type.IMAGE && uri != null) {
                image = BitmapUtils.decodeUri(context, uri, maxDimension * 2);
            } else if (type == BackgroundSpec.Type.VIDEO && uri != null) {
                videoRetriever = new MediaMetadataRetriever();
                setDataSource(videoRetriever, context, uri);
                videoDurationUs = readLong(videoRetriever,
                        MediaMetadataRetriever.METADATA_KEY_DURATION, 1L) * 1000L;
            }
        }

        Bitmap frameAt(long timeUs, int width, int height) {
            if (type == BackgroundSpec.Type.IMAGE && image != null) {
                return BitmapUtils.centerCrop(image, width, height);
            }
            if (type == BackgroundSpec.Type.VIDEO && videoRetriever != null) {
                long target = videoDurationUs <= 0 ? 0 : timeUs % videoDurationUs;
                Bitmap frame = videoRetriever.getFrameAtTime(target,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    Bitmap prepared = BitmapUtils.centerCrop(frame, width, height);
                    frame.recycle();
                    return prepared;
                }
            }
            return null;
        }

        int getColor() {
            return color;
        }

        @Override
        public void close() {
            if (image != null) {
                image.recycle();
                image = null;
            }
            if (videoRetriever != null) {
                try {
                    videoRetriever.release();
                } catch (IOException ignored) {
                }
                videoRetriever = null;
            }
        }
    }
}
