package com.chasmet.fondvertstudio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Fournit les images de décor pendant l'encodage temps réel d'un clip. */
final class ClipBackgroundProvider implements AutoCloseable {
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

    ClipBackgroundProvider(Context context, BackgroundSpec.Type type, Uri uri,
                           int color, int maxDimension) throws IOException {
        this.type = type == null ? BackgroundSpec.Type.COLOR : type;
        this.color = color;
        if (this.type == BackgroundSpec.Type.IMAGE && uri != null) {
            image = BitmapUtils.decodeUri(context, uri, Math.max(720, maxDimension * 2));
        } else if (this.type == BackgroundSpec.Type.VIDEO && uri != null) {
            videoRetriever = new MediaMetadataRetriever();
            if ("file".equals(uri.getScheme())) {
                videoRetriever.setDataSource(uri.getPath());
            } else {
                videoRetriever.setDataSource(context, uri);
            }
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
                indexedFramesEnabled = videoFrameCount > 0 && videoDurationUs > 0L;
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
            long target = videoDurationUs <= 0L ? 0L : Math.max(0L, localTimeUs) % videoDurationUs;
            Bitmap frame = indexedFrame(target);
            if (frame != null) {
                frame = frame.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (frame == null) {
                frame = videoRetriever.getFrameAtTime(target,
                        MediaMetadataRetriever.OPTION_CLOSEST);
            }
            if (frame != null) {
                frame = orientFrame(frame, videoRotation,
                        videoOrientedWidth, videoOrientedHeight);
                Bitmap prepared = BitmapUtils.centerCrop(frame, width, height);
                if (!frame.isRecycled()) frame.recycle();
                return prepared;
            }
        }
        return null;
    }

    int getColor() {
        return color;
    }

    @SuppressLint("NewApi")
    private Bitmap indexedFrame(long timeUs) {
        if (!indexedFramesEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
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

    private void recycleCachedFrames() {
        for (Bitmap frame : cachedFrames) {
            if (frame != null && !frame.isRecycled()) frame.recycle();
        }
        cachedFrames.clear();
        cachedStart = -1;
    }

    @Override
    public void close() {
        if (image != null && !image.isRecycled()) {
            image.recycle();
            image = null;
        }
        recycleCachedFrames();
        if (videoRetriever != null) {
            try { videoRetriever.release(); } catch (IOException ignored) { }
            videoRetriever = null;
        }
    }
}
