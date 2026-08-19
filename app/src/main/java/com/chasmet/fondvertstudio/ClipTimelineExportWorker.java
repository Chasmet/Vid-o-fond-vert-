package com.chasmet.fondvertstudio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Assemble les prises caméra indépendantes de la timeline en un seul MP4.
 * Le fichier reste dans le cache : seule l'Activity peut ensuite le sauvegarder dans la galerie.
 */
public final class ClipTimelineExportWorker extends Worker {
    public static final String KEY_SOURCE_TIMELINE_PATH = "source_timeline_path";
    public static final String KEY_TRANSFORM_PATH = "transform_path";
    public static final String KEY_EXTERNAL_AUDIO_URI = "external_audio_uri";
    public static final String KEY_EXTERNAL_AUDIO_START_MS = "external_audio_start_ms";
    public static final String KEY_THRESHOLD = "threshold";
    public static final String KEY_SOFTNESS = "softness";
    public static final String KEY_QUALITY = "quality";
    public static final String KEY_MIRROR_SOURCE = "mirror_source";
    public static final String KEY_TRANSFORM_SCALE = "transform_scale";
    public static final String KEY_TRANSFORM_CENTER_X = "transform_center_x";
    public static final String KEY_TRANSFORM_CENTER_Y = "transform_center_y";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_OUTPUT_FILE = "output_file";
    public static final String KEY_ERROR = "error";

    public ClipTimelineExportWorker(@NonNull Context appContext,
                                    @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String timelinePath = getInputData().getString(KEY_SOURCE_TIMELINE_PATH);
        String audioValue = getInputData().getString(KEY_EXTERNAL_AUDIO_URI);
        if (timelinePath == null) return failure("Timeline des prises absente");
        if (audioValue == null) return failure("Musique absente");

        File sourceTimelineFile = new File(timelinePath);
        ClipSourceTimeline sourceTimeline;
        try {
            sourceTimeline = ClipSourceTimeline.read(sourceTimelineFile);
        } catch (Exception error) {
            return failure("Timeline des prises illisible");
        }
        if (sourceTimeline.isEmpty()) return failure("Aucun plan à monter");

        Context context = getApplicationContext();
        Uri audioUri = Uri.parse(audioValue);
        long audioStartUs = Math.max(0L,
                getInputData().getLong(KEY_EXTERNAL_AUDIO_START_MS, 0L)) * 1000L;
        float threshold = getInputData().getFloat(KEY_THRESHOLD, 0.50f);
        float softness = getInputData().getFloat(KEY_SOFTNESS, 0.065f);
        int quality = getInputData().getInt(KEY_QUALITY, 1080);
        boolean mirrorSource = getInputData().getBoolean(KEY_MIRROR_SOURCE, false);

        String transformPath = getInputData().getString(KEY_TRANSFORM_PATH);
        File transformFile = transformPath == null ? null : new File(transformPath);
        SubjectTransformTimeline transforms = new SubjectTransformTimeline();
        if (transformFile != null && transformFile.isFile()) {
            try {
                transforms = SubjectTransformTimeline.read(transformFile);
            } catch (IOException ignored) { }
        }
        if (transforms.isEmpty()) {
            transforms.add(0L,
                    getInputData().getFloat(KEY_TRANSFORM_SCALE,
                            SubjectTransformTimeline.DEFAULT_SCALE),
                    getInputData().getFloat(KEY_TRANSFORM_CENTER_X,
                            SubjectTransformTimeline.DEFAULT_CENTER_X),
                    getInputData().getFloat(KEY_TRANSFORM_CENTER_Y,
                            SubjectTransformTimeline.DEFAULT_CENTER_Y));
        }

        File workDirectory = new File(context.getCacheDir(), "clip_timeline_exports");
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            return failure("Dossier temporaire inaccessible");
        }
        String token = String.valueOf(System.currentTimeMillis());
        File videoOnly = new File(workDirectory, "timeline_video_" + token + ".mp4");
        File finalVideo = new File(workDirectory, "ClipPret_" + token + ".mp4");

        SegmentationEngine segmenter = null;
        H264FrameEncoder encoder = null;
        boolean keepFinal = false;
        try {
            ArrayList<SourceInfo> infos = inspectSources(sourceTimeline);
            SourceInfo first = infos.get(0);
            int maxWidth = first.width >= first.height ? quality * 16 / 9 : quality;
            int maxHeight = first.width >= first.height ? quality : quality * 16 / 9;
            int[] outputSize = BitmapUtils.fitInside(first.width, first.height,
                    maxWidth, maxHeight);
            int outputWidth = outputSize[0];
            int outputHeight = outputSize[1];
            int frameRate = 30;

            long totalFrames = 0L;
            for (SourceInfo info : infos) {
                totalFrames += Math.max(1L,
                        (long) Math.ceil(info.durationUs * frameRate / 1_000_000d));
            }
            if (totalFrames <= 0L) throw new IOException("Durée de montage invalide");

            segmenter = new SegmentationEngine(context);
            encoder = new H264FrameEncoder(videoOnly, outputWidth, outputHeight, frameRate);
            BitmapUtils.AlphaMaskFlattener maskFlattener =
                    new BitmapUtils.AlphaMaskFlattener(outputWidth, outputHeight);

            long globalFrame = 0L;
            for (int segmentIndex = 0; segmentIndex < infos.size(); segmentIndex++) {
                if (isStopped()) throw new IOException("Montage annulé");
                SourceInfo info = infos.get(segmentIndex);
                ClipSourceTimeline.Segment segment = info.segment;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                BackgroundProvider background = null;
                try {
                    retriever.setDataSource(segment.sourcePath);
                    background = new BackgroundProvider(context, segment.backgroundType,
                            segment.backgroundUri, segment.backgroundColor,
                            Math.max(outputWidth, outputHeight));
                    long segmentFrames = Math.max(1L,
                            (long) Math.ceil(info.durationUs * frameRate / 1_000_000d));
                    for (long localFrame = 0L; localFrame < segmentFrames; localFrame++) {
                        if (isStopped()) throw new IOException("Montage annulé");
                        long localTimeUs = Math.min(info.durationUs - 1L,
                                localFrame * 1_000_000L / frameRate);
                        long outputTimeUs = globalFrame * 1_000_000L / frameRate;
                        Bitmap frame = retriever.getFrameAtTime(Math.max(0L, localTimeUs),
                                MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame != null) {
                            encodeOneFrame(frame, outputTimeUs, localTimeUs,
                                    info.rotation, info.width, info.height, mirrorSource,
                                    outputWidth, outputHeight, threshold, softness,
                                    segmenter, background, transforms, maskFlattener, encoder);
                            globalFrame++;
                        }
                        if (globalFrame % 3L == 0L || localFrame == segmentFrames - 1L) {
                            int progress = Math.min(96,
                                    Math.round(globalFrame * 96f / totalFrames));
                            setProgressAsync(new Data.Builder()
                                    .putInt(KEY_PROGRESS, progress).build());
                        }
                    }
                } finally {
                    try { retriever.release(); } catch (IOException ignored) { }
                    if (background != null) background.close();
                }
            }

            if (globalFrame == 0L) throw new IOException("Aucune image vidéo décodable");
            encoder.finish();
            encoder.close();
            encoder = null;
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, 97).build());

            long outputDurationUs = Math.max(1L,
                    globalFrame * 1_000_000L / frameRate);
            MuxerUtils.addAudio(context, videoOnly, audioUri, finalVideo,
                    outputDurationUs, audioStartUs);
            if (!finalVideo.isFile() || finalVideo.length() == 0L) {
                throw new IOException("Clip final introuvable");
            }
            keepFinal = true;
            setProgressAsync(new Data.Builder().putInt(KEY_PROGRESS, 100).build());
            return Result.success(new Data.Builder()
                    .putString(KEY_OUTPUT_FILE, finalVideo.getAbsolutePath()).build());
        } catch (Exception error) {
            return failure(error.getMessage() == null
                    ? "Échec du montage du clip" : error.getMessage());
        } finally {
            if (segmenter != null) segmenter.close();
            if (encoder != null) encoder.close();
            if (videoOnly.exists()) videoOnly.delete();
            if (!keepFinal && finalVideo.exists()) finalVideo.delete();
            if (transformFile != null && transformFile.exists()) transformFile.delete();
            if (sourceTimelineFile.exists()) sourceTimelineFile.delete();
            for (ClipSourceTimeline.Segment segment : sourceTimeline.segments()) {
                File source = new File(segment.sourcePath);
                if (source.exists()) source.delete();
            }
        }
    }

    private Result failure(String message) {
        return Result.failure(new Data.Builder().putString(KEY_ERROR, message).build());
    }

    private static ArrayList<SourceInfo> inspectSources(ClipSourceTimeline timeline)
            throws IOException {
        ArrayList<SourceInfo> infos = new ArrayList<>();
        for (ClipSourceTimeline.Segment segment : timeline.segments()) {
            File file = new File(segment.sourcePath);
            if (!file.isFile()) throw new IOException("Un plan vidéo est introuvable");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                long durationUs = readLong(retriever,
                        MediaMetadataRetriever.METADATA_KEY_DURATION,
                        segment.durationMs) * 1000L;
                int width = readInt(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 720);
                int height = readInt(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 1280);
                int rotation = readInt(retriever,
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
                if (rotation == 90 || rotation == 270) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
                infos.add(new SourceInfo(segment, Math.max(1L, durationUs),
                        width, height, rotation));
            } finally {
                try { retriever.release(); } catch (IOException ignored) { }
            }
        }
        if (infos.isEmpty()) throw new IOException("Aucun plan vidéo valide");
        return infos;
    }

    private static final class SourceInfo {
        final ClipSourceTimeline.Segment segment;
        final long durationUs;
        final int width;
        final int height;
        final int rotation;

        SourceInfo(ClipSourceTimeline.Segment segment, long durationUs,
                   int width, int height, int rotation) {
            this.segment = segment;
            this.durationUs = durationUs;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }

    private static void encodeOneFrame(Bitmap frame, long outputTimeUs, long localTimeUs,
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
        Bitmap background = backgroundProvider.frameAt(localTimeUs, width, height);
        SubjectTransformTimeline.Transform transform = transformTimeline.at(outputTimeUs);
        Bitmap cutout = maskFlattener.flatten(segmented.source, segmented.alphaMask);
        Bitmap composite = BitmapUtils.composite(cutout, background,
                backgroundProvider.getColor(), width, height,
                transform.scale, transform.centerX, transform.centerY);
        encoder.encode(composite, outputTimeUs);

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
            this.type = type == null ? BackgroundSpec.Type.COLOR : type;
            this.color = color;
            if (this.type == BackgroundSpec.Type.IMAGE && uri != null) {
                image = BitmapUtils.decodeUri(context, uri, maxDimension * 2);
            } else if (this.type == BackgroundSpec.Type.VIDEO && uri != null) {
                videoRetriever = new MediaMetadataRetriever();
                if ("file".equals(uri.getScheme())) videoRetriever.setDataSource(uri.getPath());
                else videoRetriever.setDataSource(context, uri);
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

        Bitmap frameAt(long localTimeUs, int width, int height) {
            if (type == BackgroundSpec.Type.IMAGE && image != null) {
                return BitmapUtils.centerCrop(image, width, height);
            }
            if (type == BackgroundSpec.Type.VIDEO && videoRetriever != null) {
                long target = videoDurationUs <= 0 ? 0 : localTimeUs % videoDurationUs;
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
                try { videoRetriever.release(); } catch (IOException ignored) { }
                videoRetriever = null;
            }
        }
    }
}
