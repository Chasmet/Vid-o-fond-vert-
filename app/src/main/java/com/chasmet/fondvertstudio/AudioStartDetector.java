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

/**
 * Détecte le premier passage audio réellement audible afin d'ignorer le silence
 * placé avant une chanson. L'utilisateur garde toujours la main via la timeline.
 */
final class AudioStartDetector {
    private static final long CODEC_TIMEOUT_US = 20_000L;
    private static final long DEFAULT_SCAN_LIMIT_US = 120_000_000L;
    private static final double ABSOLUTE_RMS_THRESHOLD = 0.0075d;
    private static final int REQUIRED_ACTIVE_BUFFERS = 3;

    private AudioStartDetector() {
    }

    static int detectStartMs(Context context, Uri uri, long knownDurationMs)
            throws IOException {
        if (context == null || uri == null) return 0;

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            if ("file".equals(uri.getScheme())) {
                extractor.setDataSource(uri.getPath());
            } else {
                extractor.setDataSource(context, uri, null);
            }

            int audioTrack = findAudioTrack(extractor);
            if (audioTrack < 0) return 0;
            MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) return 0;

            extractor.selectTrack(audioTrack);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            long durationLimitUs = knownDurationMs > 0L
                    ? Math.min(DEFAULT_SCAN_LIMIT_US, knownDurationMs * 1000L)
                    : DEFAULT_SCAN_LIMIT_US;
            boolean inputEnded = false;
            boolean outputEnded = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            double initialNoiseRms = 0d;
            int initialNoiseCount = 0;
            int activeBuffers = 0;
            long candidateStartUs = 0L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputEnded) {
                if (!inputEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Buffer audio indisponible");
                        input.clear();

                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs < 0L || sampleTimeUs > durationLimitUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0,
                                    Math.max(0L, sampleTimeUs),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            int size = extractor.readSampleData(input, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0,
                                        Math.max(0L, sampleTimeUs),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size,
                                        sampleTimeUs, extractor.getSampleFlags());
                                if (!extractor.advance()) inputEnded = true;
                            }
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputEnded) continue;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (Build.VERSION.SDK_INT >= 24
                            && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    continue;
                }
                if (outputIndex < 0) continue;

                ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    double rms = calculateRms(output.slice().order(ByteOrder.LITTLE_ENDIAN),
                            pcmEncoding);

                    long ptsUs = Math.max(0L, info.presentationTimeUs);
                    if (ptsUs <= 500_000L && rms < 0.05d) {
                        initialNoiseRms += rms;
                        initialNoiseCount++;
                    }
                    double noiseFloor = initialNoiseCount > 0
                            ? initialNoiseRms / initialNoiseCount : 0d;
                    double threshold = Math.max(
                            ABSOLUTE_RMS_THRESHOLD,
                            noiseFloor * 4.0d + 0.002d);

                    if (rms >= threshold) {
                        if (activeBuffers == 0) candidateStartUs = ptsUs;
                        activeBuffers++;
                        if (activeBuffers >= REQUIRED_ACTIVE_BUFFERS) {
                            long detectedUs = Math.max(0L, candidateStartUs - 30_000L);
                            decoder.releaseOutputBuffer(outputIndex, false);
                            return (int) Math.min(Integer.MAX_VALUE, detectedUs / 1000L);
                        }
                    } else {
                        activeBuffers = 0;
                    }
                }

                outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outputIndex, false);
            }
            return 0;
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Détection du début audio impossible", error);
        } finally {
            extractor.release();
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                try { decoder.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static double calculateRms(ByteBuffer buffer, int pcmEncoding) {
        if (buffer == null || !buffer.hasRemaining()) return 0d;

        double sum = 0d;
        long count = 0L;

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (buffer.remaining() >= 4) {
                float value = buffer.getFloat();
                if (!Float.isFinite(value)) value = 0f;
                double sample = Math.max(-1d, Math.min(1d, value));
                sum += sample * sample;
                count++;
            }
        } else {
            while (buffer.remaining() >= 2) {
                short value = buffer.getShort();
                double sample = value / 32768d;
                sum += sample * sample;
                count++;
            }
        }

        return count == 0L ? 0d : Math.sqrt(sum / count);
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }
}
