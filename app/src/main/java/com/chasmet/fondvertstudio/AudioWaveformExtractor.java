package com.chasmet.fondvertstudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Décode une piste audio en crêtes normalisées destinées à la timeline visuelle. */
final class AudioWaveformExtractor {
    private static final long CODEC_TIMEOUT_US = 20_000L;
    private static final int MAX_IDLE_DEQUEUES = 500;

    private AudioWaveformExtractor() {
    }

    static float[] extract(Context context, Uri uri, long knownDurationMs, int bucketCount)
            throws IOException {
        if (context == null || uri == null || bucketCount <= 0) {
            return new float[0];
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            if ("file".equals(uri.getScheme())) {
                extractor.setDataSource(uri.getPath());
            } else {
                extractor.setDataSource(context, uri, null);
            }

            int track = findAudioTrack(extractor);
            if (track < 0) throw new IOException("Aucune piste audio pour la forme d'onde");
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Format audio inconnu");

            long formatDurationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            long durationUs = knownDurationMs > 0L
                    ? knownDurationMs * 1000L : formatDurationUs;
            durationUs = Math.max(1_000L, durationUs);

            extractor.selectTrack(track);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            float[] peaks = new float[bucketCount];
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            boolean inputEnded = false;
            boolean outputEnded = false;
            int idleDequeues = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputEnded && idleDequeues < MAX_IDLE_DEQUEUES) {
                if (!inputEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Buffer audio indisponible");
                        input.clear();
                        long sampleTimeUs = extractor.getSampleTime();
                        int size = sampleTimeUs < 0L ? -1 : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0,
                                    Math.max(0L, sampleTimeUs),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, sampleTimeUs,
                                    extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    idleDequeues++;
                    continue;
                }
                idleDequeues = 0;
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    continue;
                }
                if (outputIndex < 0) continue;

                ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0) {
                    ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    pcm.position(info.offset);
                    pcm.limit(info.offset + info.size);
                    collectPeaks(pcm.slice().order(ByteOrder.LITTLE_ENDIAN),
                            Math.max(1, sampleRate), Math.max(1, channels), pcmEncoding,
                            Math.max(0L, info.presentationTimeUs), durationUs, peaks);
                }
                outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outputIndex, false);
            }
            return normalize(peaks);
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Création de la forme d'onde impossible", error);
        } finally {
            extractor.release();
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                try { decoder.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static void collectPeaks(ByteBuffer pcm, int sampleRate, int channels,
                                     int pcmEncoding, long startUs, long durationUs,
                                     float[] peaks) {
        int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4
                : pcmEncoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
        int frameBytes = Math.max(1, bytesPerSample * channels);
        int frameCount = pcm.remaining() / frameBytes;
        for (int frame = 0; frame < frameCount; frame++) {
            float peak = 0f;
            for (int channel = 0; channel < channels && pcm.remaining() >= bytesPerSample;
                 channel++) {
                float value;
                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    value = Math.abs(pcm.getFloat());
                } else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
                    value = Math.abs((pcm.get() & 0xFF) - 128) / 128f;
                } else {
                    value = Math.abs((int) pcm.getShort()) / 32768f;
                }
                if (Float.isFinite(value)) peak = Math.max(peak, Math.min(1f, value));
            }
            long timeUs = startUs + frame * 1_000_000L / sampleRate;
            int bucket = (int) Math.min(peaks.length - 1,
                    Math.max(0L, timeUs * peaks.length / durationUs));
            peaks[bucket] = Math.max(peaks[bucket], peak);
        }
    }

    static float[] normalize(float[] rawPeaks) {
        if (rawPeaks == null || rawPeaks.length == 0) return new float[0];
        float[] sorted = rawPeaks.clone();
        Arrays.sort(sorted);
        int percentileIndex = Math.min(sorted.length - 1,
                Math.max(0, Math.round((sorted.length - 1) * 0.95f)));
        float reference = Math.max(0.04f, sorted[percentileIndex]);
        float[] normalized = new float[rawPeaks.length];
        for (int index = 0; index < rawPeaks.length; index++) {
            float current = rawPeaks[index];
            float previous = index == 0 ? current : rawPeaks[index - 1];
            float next = index == rawPeaks.length - 1 ? current : rawPeaks[index + 1];
            float smoothed = Math.max(current, (previous + current + next) / 3f);
            normalized[index] = Math.min(1f,
                    (float) Math.sqrt(Math.max(0f, smoothed) / reference));
        }
        return normalized;
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        return -1;
    }
}
