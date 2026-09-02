package com.chasmet.fondvertstudio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

@OptIn(markerClass = UnstableApi.class)
final class MuxerUtils {
    private static final String MIME_AAC = "audio/mp4a-latm";

    private MuxerUtils() {
    }

    static void addSourceAudio(Context context, File videoOnly, Uri sourceUri,
                               File output, long durationUs) throws IOException {
        addAudio(context, videoOnly, sourceUri, output, durationUs, 0L);
    }

    /** Retourne vrai si la source peut être muxée directement comme AAC. */
    static boolean isAacAudio(Context context, Uri audioUri) throws IOException {
        if (audioUri == null) return false;
        return MIME_AAC.equalsIgnoreCase(inspectAudioMime(context, audioUri));
    }

    /**
     * Prépare à l'avance une musique non AAC. L'étape finale du clip n'a ainsi plus
     * à attendre une conversion WAV/PCM de plusieurs minutes.
     */
    static Uri prepareAudioForFastMux(Context context, Uri sourceUri,
                                      File destination, long fullDurationUs)
            throws IOException {
        if (sourceUri == null) throw new IOException("Musique absente");
        String mime = inspectAudioMime(context, sourceUri);
        if (mime == null) throw new IOException("Aucune piste audio lisible");
        if (MIME_AAC.equalsIgnoreCase(mime)) return sourceUri;
        if (destination == null) throw new IOException("Fichier audio temporaire absent");
        AudioCompatibilityTranscoder.transcodeToAac(
                context, sourceUri, destination, 0L, Math.max(1L, fullDurationUs));
        return Uri.fromFile(destination);
    }

    static boolean addAudio(Context context, File videoOnly, Uri audioUri,
                            File output, long durationUs, long audioStartUs) throws IOException {
        if (audioUri == null) {
            throw new IOException("Musique absente : export annulé pour éviter une vidéo silencieuse");
        }

        File convertedAudio = null;
        try {
            String sourceAudioMime = inspectAudioMime(context, audioUri);
            if (sourceAudioMime == null) {
                throw new IOException("La musique sélectionnée ne contient aucune piste audio lisible");
            }

            Uri muxAudioUri = audioUri;
            long muxStartUs = Math.max(0L, audioStartUs);

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

            if (!hasAudioTrack(output)) {
                if (output.exists()) output.delete();
                throw new IOException("La piste audio n'a pas été intégrée au montage final");
            }
            return true;
        } catch (IOException error) {
            if (output.exists()) output.delete();
            throw error;
        } catch (Exception error) {
            if (output.exists()) output.delete();
            String message = error.getMessage();
            throw new IOException(message == null || message.trim().isEmpty()
                    ? "Échec de l'intégration de la musique"
                    : "Échec de l'intégration de la musique : " + message, error);
        } finally {
            if (convertedAudio != null && convertedAudio.exists()) convertedAudio.delete();
        }
    }

    static boolean hasAudioTrack(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0L) return false;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            return findTrack(extractor, "audio/") >= 0;
        } finally {
            extractor.release();
        }
    }

    static boolean hasVideoTrack(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0L) return false;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            return findTrack(extractor, "video/") >= 0;
        } finally {
            extractor.release();
        }
    }

    /** Copie brute utilisée comme dernier filet de sécurité : une vidéo n'est jamais perdue. */
    static void copyVideoOnly(File source, File output) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0L) {
            throw new IOException("Vidéo source absente");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Dossier vidéo inaccessible");
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream destination = new FileOutputStream(output)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
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
            if (videoTrack < 0) throw new IOException("Piste vidéo absente");
            if (audioTrack < 0) throw new IOException("Piste audio absente après préparation");

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
                    try { muxer.stop(); } catch (Exception ignored) { }
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
            if (firstTimeUs < 0L) firstTimeUs = seekToStart ? startUs : sampleTime;
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
}
