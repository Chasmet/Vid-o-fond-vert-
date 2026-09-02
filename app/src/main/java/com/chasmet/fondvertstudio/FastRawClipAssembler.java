package com.chasmet.fondvertstudio;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Secours ultra rapide : concatène les prises CameraX sans décodage ni réencodage.
 * Utilisé seulement si l'encodeur temps réel n'a pas pu produire le montage détouré.
 */
final class FastRawClipAssembler {
    private FastRawClipAssembler() { }

    static File assemble(ClipSourceTimeline timeline, File output) throws IOException {
        List<ClipSourceTimeline.Segment> segments = timeline.segments();
        if (segments.isEmpty()) throw new IOException("Aucun plan vidéo disponible");
        if (segments.size() == 1) {
            copy(new File(segments.get(0).sourcePath), output);
            return output;
        }

        MediaExtractor firstExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            File first = new File(segments.get(0).sourcePath);
            if (!first.isFile()) throw new IOException("Premier plan introuvable");
            firstExtractor.setDataSource(first.getAbsolutePath());
            int firstTrack = findTrack(firstExtractor, "video/");
            if (firstTrack < 0) throw new IOException("Piste vidéo absente");
            MediaFormat outputFormat = firstExtractor.getTrackFormat(firstTrack);

            if (output.exists() && !output.delete()) {
                throw new IOException("Impossible de remplacer le secours vidéo");
            }
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rotation = readRotation(first);
            if (rotation != 0) muxer.setOrientationHint(rotation);
            int outputTrack = muxer.addTrack(outputFormat);
            muxer.start();
            started = true;

            long outputOffsetUs = 0L;
            for (ClipSourceTimeline.Segment segment : segments) {
                File source = new File(segment.sourcePath);
                if (!source.isFile()) continue;
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(source.getAbsolutePath());
                    int track = findTrack(extractor, "video/");
                    if (track < 0) continue;
                    extractor.selectTrack(track);
                    MediaFormat format = extractor.getTrackFormat(track);
                    int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                            ? Math.max(512 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                            : 4 * 1024 * 1024;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    long firstTimeUs = -1L;
                    long lastWrittenUs = outputOffsetUs;
                    while (true) {
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        long sampleTime = extractor.getSampleTime();
                        if (sampleTime < 0L) break;
                        if (firstTimeUs < 0L) firstTimeUs = sampleTime;
                        long presentationUs = outputOffsetUs
                                + Math.max(0L, sampleTime - firstTimeUs);
                        int flags = (extractor.getSampleFlags()
                                & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        info.set(0, size, presentationUs, flags);
                        muxer.writeSampleData(outputTrack, buffer, info);
                        lastWrittenUs = presentationUs;
                        if (!extractor.advance()) break;
                    }
                    outputOffsetUs = Math.max(lastWrittenUs + 33_333L,
                            outputOffsetUs + segment.durationMs * 1000L);
                } finally {
                    extractor.release();
                }
            }
        } finally {
            firstExtractor.release();
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                muxer.release();
            }
        }
        if (!output.isFile() || output.length() <= 0L) {
            throw new IOException("Secours vidéo introuvable");
        }
        return output;
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int readRotation(File source) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (IOException ignored) { }
        }
    }

    private static void copy(File source, File output) throws IOException {
        if (!source.isFile()) throw new IOException("Plan vidéo introuvable");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream destination = new FileOutputStream(output)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
            }
        }
    }
}
