package com.chasmet.fondvertstudio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class MuxerUtils {
    private static final String MIME_AAC = "audio/mp4a-latm";

    private MuxerUtils() {
    }

    static void addSourceAudio(Context context, File videoOnly, Uri sourceUri,
                               File output, long durationUs) throws IOException {
        addAudio(context, videoOnly, sourceUri, output, durationUs, 0L);
    }

    static void addAudio(Context context, File videoOnly, Uri audioUri,
                         File output, long durationUs, long audioStartUs) throws IOException {
        String sourceAudioMime = inspectAudioMime(context, audioUri);
        if (sourceAudioMime == null) {
            copyFile(videoOnly, output);
            return;
        }

        File convertedAudio = null;
        Uri muxAudioUri = audioUri;
        long muxStartUs = Math.max(0L, audioStartUs);
        try {
            // Android MediaMuxer ne peut pas ajouter une piste WAV/PCM dans un MP4.
            // On convertit uniquement les formats non AAC afin de ne pas ralentir les MP3/AAC
            // déjà compatibles inutilement.
            if (!MIME_AAC.equalsIgnoreCase(sourceAudioMime)) {
                convertedAudio = createTemporaryAacFile(context);
                AudioCompatibilityTranscoder.transcodeToAac(
                        context, audioUri, convertedAudio, muxStartUs, durationUs);
                muxAudioUri = Uri.fromFile(convertedAudio);
                muxStartUs = 0L;
            }

            try {
                muxTracks(context, videoOnly, muxAudioUri, output, durationUs, muxStartUs);
            } catch (IllegalArgumentException | IllegalStateException directMuxError) {
                // Certains téléphones refusent malgré tout une piste annoncée AAC avec des
                // paramètres de conteneur inhabituels. Une conversion propre élimine ces clés.
                if (convertedAudio == null) {
                    if (output.exists()) output.delete();
                    convertedAudio = createTemporaryAacFile(context);
                    AudioCompatibilityTranscoder.transcodeToAac(
                            context, audioUri, convertedAudio,
                            Math.max(0L, audioStartUs), durationUs);
                    muxTracks(context, videoOnly, Uri.fromFile(convertedAudio),
                            output, durationUs, 0L);
                } else {
                    throw new IOException("Assemblage audio/vidéo impossible", directMuxError);
                }
            }
        } finally {
            if (convertedAudio != null && convertedAudio.exists()) {
                convertedAudio.delete();
            }
        }
    }

    private static void muxTracks(Context context, File videoOnly, Uri audioUri,
                                  File output, long durationUs, long audioStartUs)
            throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(videoOnly.getAbsolutePath());
            setDataSource(audioExtractor, context, audioUri);
            int videoTrack = findTrack(videoExtractor, "video/");
            int audioTrack = findTrack(audioExtractor, "audio/");
            if (videoTrack < 0) {
                throw new IOException("Piste vidéo absente");
            }
            if (audioTrack < 0) {
                copyFile(videoOnly, output);
                return;
            }

            if (output.exists() && !output.delete()) {
                throw new IOException("Impossible de remplacer le montage temporaire");
            }
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack));
            int outputAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            muxer.start();
            muxerStarted = true;
            copyTrack(videoExtractor, videoTrack, muxer, outputVideoTrack, durationUs,
                    0L, false);
            copyTrack(audioExtractor, audioTrack, muxer, outputAudioTrack, durationUs,
                    Math.max(0L, audioStartUs), true);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }
                muxer.release();
            }
        }
    }

    private static File createTemporaryAacFile(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "audio_transcodes");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Dossier audio temporaire inaccessible");
        }
        return new File(directory, "audio_aac_" + System.nanoTime() + ".m4a");
    }

    private static String inspectAudioMime(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            setDataSource(extractor, context, uri);
            int audioTrack = findTrack(extractor, "audio/");
            if (audioTrack < 0) return null;
            return extractor.getTrackFormat(audioTrack).getString(MediaFormat.KEY_MIME);
        } finally {
            extractor.release();
        }
    }

    private static void copyTrack(MediaExtractor extractor, int inputTrack,
                                  MediaMuxer muxer, int outputTrack,
                                  long durationUs, long startUs,
                                  boolean seekToStart) {
        extractor.selectTrack(inputTrack);
        if (seekToStart && startUs > 0L) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (extractor.getSampleTime() >= 0L && extractor.getSampleTime() < startUs) {
                if (!extractor.advance()) break;
            }
        }
        MediaFormat format = extractor.getTrackFormat(inputTrack);
        int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? Math.max(512 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                : 4 * 1024 * 1024;
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long firstTimeUs = -1L;
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            long sampleTime = extractor.getSampleTime();
            if (sampleTime < 0L) break;
            if (seekToStart && sampleTime < startUs) {
                if (!extractor.advance()) break;
                continue;
            }
            if (firstTimeUs < 0L) {
                firstTimeUs = seekToStart ? startUs : sampleTime;
            }
            long normalizedTime = Math.max(0L, sampleTime - firstTimeUs);
            if (normalizedTime > durationUs) break;
            int codecFlags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            info.set(0, size, normalizedTime, codecFlags);
            muxer.writeSampleData(outputTrack, buffer, info);
            if (!extractor.advance()) break;
        }
        extractor.unselectTrack(inputTrack);
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void setDataSource(MediaExtractor extractor, Context context, Uri uri)
            throws IOException {
        if ("file".equals(uri.getScheme())) extractor.setDataSource(uri.getPath());
        else extractor.setDataSource(context, uri, null);
    }

    private static void copyFile(File source, File output) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream destination = new FileOutputStream(output)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
            }
        }
    }
}
