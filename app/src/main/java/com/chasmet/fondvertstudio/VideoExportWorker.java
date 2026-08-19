package com.chasmet.fondvertstudio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VideoExportWorker extends Worker {
    public static final String KEY_SOURCE_URI = "source_uri";
    public static final String KEY_BACKGROUND_TYPE = "background_type";
    public static final String KEY_BACKGROUND_URI = "background_uri";
    public static final String KEY_BACKGROUND_COLOR = "background_color";
    public static final String KEY_THRESHOLD = "threshold";
    public static final String KEY_SOFTNESS = "softness";
    public static final String KEY_QUALITY = "quality";
    public static final String KEY_MIRROR_SOURCE = "mirror_source";
    public static final String KEY_TRANSFORM_PATH = "transform_path";
    public static final String KEY_TRANSFORM_SCALE = "transform_scale";
    public static final String KEY_TRANSFORM_CENTER_X = "transform_center_x";
    public static final String KEY_TRANSFORM_CENTER_Y = "transform_center_y";
    public static final String KEY_EXTERNAL_AUDIO_URI = "external_audio_uri";
    public static final String KEY_EXTERNAL_AUDIO_START_MS = "external_audio_start_ms";
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
        if (sourceValue == null) return failure("Vidéo source absente");
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
        float threshold = getInputData().getFloat(KEY_THRESHOLD, 0.50f);
        float softness = getInputData().getFloat(KEY_SOFTNESS, 0.065f);
        int quality = getInputData().getInt(KEY_QUALITY, 1080);
        boolean mirrorSource = getInputData().getBoolean(KEY_MIRROR_SOURCE, false);
        float fallbackScale = getInputData().getFloat(KEY_TRANSFORM_SCALE,
                SubjectTransformTimeline.DEFAULT_SCALE);
        float fallbackCenterX = getInputData().getFloat(KEY_TRANSFORM_CENTER_X,
                SubjectTransformTimeline.DEFAULT_CENTER_X);
        float fallbackCenterY = getInputData().getFloat(KEY_TRANSFORM_CENTER_Y,
                SubjectTransformTimeline.DEFAULT_CENTER_Y);
        String transformPath = getInputData().getString(KEY_TRANSFORM_PATH);
        String externalAudioValue = getInputData().getString(KEY_EXTERNAL_AUDIO_URI);
        Uri externalAudioUri = externalAudioValue == null ? null : Uri.parse(externalAudioValue);
        long externalAudioStartUs = Math.max(0L,
                getInputData().getLong(KEY_EXTERNAL_AUDIO_START_MS, 0L)) * 1000L;

        File transformFile = transformPath == null ? null : new File(transformPath);
        SubjectTransformTimeline transformTimeline = new SubjectTransformTimeline();
        if (transformFile != null && transformFile.isFile()) {
            try {
                transformTimeline = SubjectTransformTimeline.read(transformFile);
            } catch (IOException ignored) {
            }
        }
        if (transformTimeline.isEmpty()) {
            transformTimeline.add(0L, fallbackScale, fallbackCenterX, fallbackCenterY);
        }

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
            if (durationMs <= 0L) throw new IOException("Durée vidéo invalide");
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
            int sourceFrameCount = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? readInt(sourceRetriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, 0) : 0;
            double detectedFrameRate = sourceFrameCount > 0
                    ? sourceFrameCount * 1000d / durationMs : 30d;
            int frameRate = Math.max(15, Math.min(30, (int) Math.round(detectedFrameRate)));
            int frameCount = Math.max(1, (int) Math.ceil(durationMs * frameRate / 1000d));
            long durationUs = durationMs * 1000L;

            segmenter = new SegmentationEngine(context);
            backgroundProvider = new BackgroundProvider(context, backgroundType,
                    backgroundUri, backgroundColor, Math.max(width, height));
            encoder = new H264FrameEncoder(videoOnly, width, height, frameRate);
            BitmapUtils.AlphaMaskFlattener maskFlattener =
                    new BitmapUtils.AlphaMaskFlattener(width, height);

            int encodedFrames;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sourceFrameCount > 0) {
                encodedFrames = encodeIndexedFrames(sourceRetriever, sourceFrameCount,
                        frameCount, frameRate, rotation, metadataWidth, metadataHeight,
                        mirrorSource, width, height, threshold, softness,
                        segmenter, backgroundProvider, transformTimeline,
                        maskFlattener, encoder);
            } else {
                encodedFrames = encodeTimedFrames(sourceRetriever, frameCount, frameRate,
                        durationUs, rotation, metadataWidth, metadataHeight, mirrorSource,
                        width, height, threshold, softness,
                        segmenter, backgroundProvider, transformTimeline,
                        maskFlattener, encoder);
            }
            if (encodedFrames == 0) throw new IOException("Aucune image vidéo décodable");
            encoder.finish();
            encoder.close();
            encoder = null;
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, 97).build());

            if (externalAudioUri != null) {
                MuxerUtils.addAudio(context, videoOnly, externalAudioUri, finalVideo,
                        durationUs, externalAudioStartUs);
            } else {
                MuxerUtils.addSourceAudio(context, videoOnly, sourceUri, finalVideo, durationUs);
            }

            String name = String.format(Locale.US, externalAudioUri == null
                            ? "FondVert_%d.mp4" : "ClipMusique_%d.mp4",
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
            if (backgroundProvider != null) backgroundProvider.close();
            if (segmenter != null) segmenter.close();
            if (encoder != null) encoder.close();
            if (videoOnly.exists()) videoOnly.delete();
            if (finalVideo.exists()) finalVideo.delete();
            if (transformFile != null && transformFile.exists()) transformFile.delete();
        }
    }

    private Result failure(String message) {
        return Result.failure(new Data.Builder().putString(KEY_ERROR, message).build());
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private int encodeIndexedFrames(MediaMetadataRetriever retriever,
                                    int sourceFrameCount, int targetFrameCount,
                                    int frameRate, int rotation, int metadataWidth,
                                    int metadataHeight, boolean mirrorSource,
                                    int width, int height, float threshold, float softness,
                                    SegmentationEngine segmenter,
                                    BackgroundProvider backgroundProvider,
                                    SubjectTransformTimeline transformTimeline,
                                    BitmapUtils.AlphaMaskFlattener maskFlattener,
                                    H264FrameEncoder encoder) throws Exception {
        MediaMetadataRetriever.BitmapParams bitmapParams =
                new MediaMetadataRetriever.BitmapParams();
        bitmapParams.setPreferredConfig(Bitmap.Config.ARGB_8888);
        final int batchSize = 6;
        double sourceStep = sourceFrameCount / (double) targetFrameCount;
        double nextSourceIndex = 0d;
        int outputIndex = 0;

        for (int batchStart = 0; batchStart < sourceFrameCount
                && outputIndex < targetFrameCount; batchStart += batchSize) {
            if (isStopped()) throw new IOException("Export annulé");
            int count = Math.min(batchSize, sourceFrameCount - batchStart);
            List<Bitmap> frames = retriever.getFramesAtIndex(batchStart, count, bitmapParams);
            for (int item = 0; item < frames.size(); item++) {
                Bitmap frame = frames.get(item);
                int sourceIndex = batchStart + item;
                if (frame == null) continue;
                if (sourceIndex + 0.5d < nextSourceIndex) {
                    frame.recycle();
                    continue;
                }
                long timeUs = outputIndex * 1_000_000L / frameRate;
                encodeOneFrame(frame, timeUs, rotation, metadataWidth, metadataHeight,
                        mirrorSource, width, height, threshold, softness,
                        segmenter, backgroundProvider, transformTimeline,
                        maskFlattener, encoder);
                outputIndex++;
                nextSourceIndex = outputIndex * sourceStep;
            }
            int progress = Math.min(96,
                    Math.round(Math.min(sourceFrameCount, batchStart + count)
                            * 96f / sourceFrameCount));
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, progress).build());
        }
        return outputIndex;
    }

    private int encodeTimedFrames(MediaMetadataRetriever retriever,
                                  int frameCount, int frameRate, long durationUs,
                                  int rotation, int metadataWidth, int metadataHeight,
                                  boolean mirrorSource, int width, int height,
                                  float threshold, float softness,
                                  SegmentationEngine segmenter,
                                  BackgroundProvider backgroundProvider,
                                  SubjectTransformTimeline transformTimeline,
                                  BitmapUtils.AlphaMaskFlattener maskFlattener,
                                  H264FrameEncoder encoder) throws Exception {
        int encoded = 0;
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            if (isStopped()) throw new IOException("Export annulé");
            long timeUs = frameIndex * 1_000_000L / frameRate;
            Bitmap frame = retriever.getFrameAtTime(Math.min(timeUs, durationUs - 1),
                    MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame != null) {
                encodeOneFrame(frame, timeUs, rotation, metadataWidth, metadataHeight,
                        mirrorSource, width, height, threshold, softness,
                        segmenter, backgroundProvider, transformTimeline,
                        maskFlattener, encoder);
                encoded++;
            }
            if (frameIndex % 3 == 0 || frameIndex == frameCount - 1) {
                int progress = Math.min(96,
                        Math.round((frameIndex + 1) * 96f / frameCount));
                setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, progress).build());
            }
        }
        return encoded;
    }

    private static void encodeOneFrame(Bitmap frame, long timeUs,
                                       int rotation, int metadataWidth, int metadataHeight,
                                       boolean mirrorSource, int width, int height,
                                       float threshold, float softness,
                                       SegmentationEngine segmenter,
                                       BackgroundProvider backgroundProvider,
                                       SubjectTransformTimeline transformTimeline,
                                       BitmapUtils.AlphaMaskFlattener maskFlattener,
                                       H264FrameEncoder encoder) throws Exception {
        frame = orientFrame(frame, rotation, metadataWidth, metadataHeight);
        if (mirrorSource) frame = BitmapUtils.rotateAndMirror(frame, 0, true);
        Bitmap prepared = BitmapUtils.centerCrop(frame, width, height);
        frame.recycle();

        SegmentationEngine.Result segmented = segmenter.processStillBlocking(
                prepared, threshold, softness);
        Bitmap background = backgroundProvider.frameAt(timeUs, width, height);
        SubjectTransformTimeline.Transform transform = transformTimeline.at(timeUs);
        Bitmap cutout = maskFlattener.flatten(segmented.source, segmented.alphaMask);
        Bitmap composite = BitmapUtils.composite(cutout, background,
                backgroundProvider.getColor(), width, height,
                transform.scale, transform.centerX, transform.centerY);
        encoder.encode(composite, timeUs);

        composite.recycle();
        cutout.recycle();
        segmented.alphaMask.recycle();
        segmented.source.recycle();
        if (background != null) background.recycle();
    }

    private static Bitmap orientFrame(Bitmap bitmap, int rotation,
                                      int orientedWidth, int orientedHeight) {
        boolean expectedPortrait = orientedHeight > orientedWidth;
        boolean actualPortrait = bitmap.getHeight() > bitmap.getWidth();
        if ((rotation == 90 || rotation == 270) && expectedPortrait != actualPortrait) {
            return BitmapUtils.rotateAndMirror(bitmap, rotation, false);
        }
        if (rotation == 180) return BitmapUtils.rotateAndMirror(bitmap, 180, false);
        return bitmap;
    }

    private static void setDataSource(MediaMetadataRetriever retriever,
                                      Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) retriever.setDataSource(uri.getPath());
        else retriever.setDataSource(context, uri);
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
        private int videoRotation;
        private int videoOrientedWidth;
        private int videoOrientedHeight;
        private int videoFrameCount;
        private double videoFrameRate;
        private boolean indexedFramesEnabled;
        private int cachedStart = -1;
        private final List<Bitmap> cachedFrames = new ArrayList<>();

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
                int width = readInt(videoRetriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 720);
                int height = readInt(videoRetriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 1280);
                videoRotation = readInt(videoRetriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
                if (videoRotation == 90 || videoRotation == 270) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
                videoOrientedWidth = width;
                videoOrientedHeight = height;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    videoFrameCount = readInt(videoRetriever,
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, 0);
                    indexedFramesEnabled = videoFrameCount > 0 && videoDurationUs > 0;
                    if (indexedFramesEnabled) {
                        videoFrameRate = videoFrameCount * 1_000_000d / videoDurationUs;
                    }
                }
            }
        }

        Bitmap frameAt(long timeUs, int width, int height) {
            if (type == BackgroundSpec.Type.IMAGE && image != null) {
                return BitmapUtils.centerCrop(image, width, height);
            }
            if (type == BackgroundSpec.Type.VIDEO && videoRetriever != null) {
                long target = videoDurationUs <= 0 ? 0 : timeUs % videoDurationUs;
                Bitmap frame = indexedFrame(target);
                if (frame != null) frame = frame.copy(Bitmap.Config.ARGB_8888, false);
                if (frame == null) {
                    frame = videoRetriever.getFrameAtTime(target,
                            MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame != null) {
                    frame = orientFrame(frame, videoRotation,
                            videoOrientedWidth, videoOrientedHeight);
                    Bitmap prepared = BitmapUtils.centerCrop(frame, width, height);
                    frame.recycle();
                    return prepared;
                }
            }
            return null;
        }

        @SuppressLint("NewApi")
        private Bitmap indexedFrame(long timeUs) {
            if (!indexedFramesEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
            int targetIndex = Math.max(0, Math.min(videoFrameCount - 1,
                    (int) Math.floor(timeUs * videoFrameRate / 1_000_000d)));
            if (cachedStart >= 0 && targetIndex >= cachedStart
                    && targetIndex < cachedStart + cachedFrames.size()) {
                return cachedFrames.get(targetIndex - cachedStart);
            }
            recycleCachedFrames();
            int count = Math.min(6, videoFrameCount - targetIndex);
            try {
                MediaMetadataRetriever.BitmapParams params =
                        new MediaMetadataRetriever.BitmapParams();
                params.setPreferredConfig(Bitmap.Config.ARGB_8888);
                cachedFrames.addAll(videoRetriever.getFramesAtIndex(targetIndex, count, params));
                cachedStart = targetIndex;
                return cachedFrames.isEmpty() ? null : cachedFrames.get(0);
            } catch (Exception ignored) {
                indexedFramesEnabled = false;
                recycleCachedFrames();
                return null;
            }
        }

        private void recycleCachedFrames() {
            for (Bitmap frame : cachedFrames) {
                if (frame != null && !frame.isRecycled()) frame.recycle();
            }
            cachedFrames.clear();
            cachedStart = -1;
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
                recycleCachedFrames();
                try {
                    videoRetriever.release();
                } catch (IOException ignored) {
                }
                videoRetriever = null;
            }
        }
    }
}
