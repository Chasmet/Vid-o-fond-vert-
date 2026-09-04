package com.chasmet.fondvertstudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Décode une piste audio en crêtes normalisées destinées à la timeline visuelle. */
final class AudioWaveformExtractor {
    private static final long CODEC_TIMEOUT_US = 20_000L;
    private static final int MAX_IDLE_DEQUEUES = 500;
    private static final float ABSOLUTE_PEAK_THRESHOLD = 0.015f;
    private static final int DISPLAY_SAMPLE_RATE = 8_000;
    private static final String KEY_PCM_ENCODING_COMPAT = "pcm-encoding";

    static final class Analysis {
        final float[] waveform;
        final int detectedStartMs;

        Analysis(float[] waveform, int detectedStartMs) {
            this.waveform = waveform == null ? new float[0] : waveform;
            this.detectedStartMs = Math.max(0, detectedStartMs);
        }
    }

    private AudioWaveformExtractor() {
    }

    static float[] extract(Context context, Uri uri, long knownDurationMs, int bucketCount)
            throws IOException {
        return analyze(context, uri, knownDurationMs, bucketCount).waveform;
    }

    /** Décode une seule fois le morceau pour produire la forme d'onde et le départ AUTO. */
    static Analysis analyze(Context context, Uri uri, long knownDurationMs, int bucketCount)
            throws IOException {
        if (context == null || uri == null || bucketCount <= 0) {
            return new Analysis(new float[0], 0);
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
            int pcmEncoding = inputFormat.containsKey(KEY_PCM_ENCODING_COMPAT)
                    ? inputFormat.getInteger(KEY_PCM_ENCODING_COMPAT)
                    : AudioFormat.ENCODING_PCM_16BIT;
            boolean inputEnded = false;
            boolean outputEnded = false;
            int idleDequeues = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputEnded && idleDequeues < MAX_IDLE_DEQUEUES) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Analyse audio annulée");
                }
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
                    if (outputFormat.containsKey(KEY_PCM_ENCODING_COMPAT)) {
                        pcmEncoding = outputFormat.getInteger(KEY_PCM_ENCODING_COMPAT);
                    }
                    continue;
                }
                if (outputIndex < 0) continue;

                ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0) {
                    ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    pcm.position(info.offset);
                    pcm.limit(info.offset + info.size);
                    collectPeaks(
                            pcm.slice().order(ByteOrder.LITTLE_ENDIAN),
                            Math.max(1, sampleRate), Math.max(1, channels), pcmEncoding,
                            Math.max(0L, info.presentationTimeUs), durationUs, peaks);
                }
                outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outputIndex, false);
            }
            return new Analysis(normalize(peaks), detectStartMs(peaks, durationUs));
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
        int bytesPerSample = bytesPerSample(pcmEncoding);
        int frameBytes = Math.max(1, bytesPerSample * channels);
        int frameCount = pcm.remaining() / frameBytes;
        int firstByte = pcm.position();
        int stride = Math.max(1, sampleRate / DISPLAY_SAMPLE_RATE);
        for (int frame = 0; frame < frameCount; frame += stride) {
            float peak = 0f;
            int frameOffset = firstByte + frame * frameBytes;
            for (int channel = 0; channel < channels; channel++) {
                int sampleOffset = frameOffset + channel * bytesPerSample;
                if (sampleOffset + bytesPerSample > pcm.limit()) break;
                float value;
                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    value = Math.abs(pcm.getFloat(sampleOffset));
                } else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
                    value = Math.abs((pcm.get(sampleOffset) & 0xFF) - 128) / 128f;
                } else if (pcmEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                    int sample = (pcm.get(sampleOffset) & 0xFF)
                            | ((pcm.get(sampleOffset + 1) & 0xFF) << 8)
                            | (pcm.get(sampleOffset + 2) << 16);
                    value = Math.abs(sample / 8_388_608f);
                } else if (pcmEncoding == AudioFormat.ENCODING_PCM_32BIT) {
                    value = Math.abs(pcm.getInt(sampleOffset) / 2_147_483_648f);
                } else {
                    value = Math.abs((int) pcm.getShort(sampleOffset)) / 32768f;
                }
                if (Float.isFinite(value)) {
                    value = Math.min(1f, value);
                    peak = Math.max(peak, value);
                }
            }
            long timeUs = startUs + frame * 1_000_000L / sampleRate;
            int bucket = (int) Math.min(peaks.length - 1,
                    Math.max(0L, timeUs * peaks.length / durationUs));
            peaks[bucket] = Math.max(peaks[bucket], peak);
        }
    }

    static int detectStartMs(float[] rawPeaks, long durationUs) {
        if (rawPeaks == null || rawPeaks.length == 0 || durationUs <= 0L) return 0;
        int noiseBuckets = (int) Math.max(1L, Math.min(rawPeaks.length,
                500_000L * rawPeaks.length / durationUs));
        float noiseFloor = 0f;
        for (int index = 0; index < noiseBuckets; index++) {
            noiseFloor += rawPeaks[index];
        }
        noiseFloor /= noiseBuckets;
        float threshold = Math.max(ABSOLUTE_PEAK_THRESHOLD, noiseFloor * 4f + 0.005f);
        float bucketDurationMs = durationUs / 1000f / rawPeaks.length;
        int requiredBuckets = rawPeaks.length == 1 ? 1 : Math.max(2,
                Math.min(4, (int) Math.ceil(80f / Math.max(1f, bucketDurationMs))));
        int activeBuckets = 0;
        int candidate = 0;
        for (int index = 0; index < rawPeaks.length; index++) {
            if (rawPeaks[index] >= threshold) {
                if (activeBuckets == 0) candidate = index;
                activeBuckets++;
                if (activeBuckets >= requiredBuckets) {
                    long startMs = Math.round(candidate * bucketDurationMs) - 30L;
                    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, startMs));
                }
            } else {
                activeBuckets = 0;
            }
        }
        return 0;
    }

    private static int bytesPerSample(int pcmEncoding) {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) return 1;
        if (pcmEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 3;
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                || pcmEncoding == AudioFormat.ENCODING_PCM_32BIT) return 4;
        return 2;
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
