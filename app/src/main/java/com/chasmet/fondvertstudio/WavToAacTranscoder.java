package com.chasmet.fondvertstudio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Convertisseur WAV PCM/Float -> AAC entièrement natif Android.
 *
 * Il ne dépend pas du Transformer Media3 pour les WAV. Cela évite les échecs
 * observés sur certains téléphones avec des WAV de mastering (16/24/32 bits,
 * float et WAVE_FORMAT_EXTENSIBLE).
 */
final class WavToAacTranscoder {
    private static final int FORMAT_PCM = 0x0001;
    private static final int FORMAT_IEEE_FLOAT = 0x0003;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;
    private static final String AAC_MIME = "audio/mp4a-latm";
    private static final long CODEC_TIMEOUT_US = 20_000L;

    private WavToAacTranscoder() {
    }

    static boolean isWav(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             BufferedInputStream input = raw == null ? null : new BufferedInputStream(raw, 64 * 1024)) {
            if (input == null) return false;
            byte[] header = new byte[12];
            if (readFully(input, header, 0, header.length) != header.length) return false;
            String riff = ascii(header, 0, 4);
            String wave = ascii(header, 8, 4);
            return ("RIFF".equals(riff) || "RF64".equals(riff)) && "WAVE".equals(wave);
        } catch (Exception ignored) {
            return false;
        }
    }

    static void transcode(Context context, Uri inputUri, File outputFile,
                          long startUs, long durationUs) throws IOException {
        if (context == null || inputUri == null) {
            throw new IOException("Source WAV absente");
        }
        if (outputFile == null) {
            throw new IOException("Destination AAC absente");
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Dossier audio temporaire inaccessible");
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Impossible de remplacer l'audio temporaire");
        }

        try (InputStream raw = context.getContentResolver().openInputStream(inputUri);
             BufferedInputStream input = raw == null ? null : new BufferedInputStream(raw, 256 * 1024)) {
            if (input == null) throw new IOException("Impossible d'ouvrir le WAV");
            WavInfo info = parseHeader(input);
            encodePcmStream(input, info, outputFile,
                    Math.max(0L, startUs), Math.max(1L, durationUs));
        } catch (IOException error) {
            if (outputFile.exists()) outputFile.delete();
            throw error;
        } catch (Exception error) {
            if (outputFile.exists()) outputFile.delete();
            String message = error.getMessage();
            throw new IOException(message == null || message.trim().isEmpty()
                    ? "Conversion WAV vers AAC impossible"
                    : "Conversion WAV vers AAC impossible : " + message, error);
        }

        if (!outputFile.isFile() || outputFile.length() <= 0L) {
            throw new IOException("AAC WAV temporaire introuvable");
        }
    }

    /**
     * Lit le header RIFF jusqu'au début du chunk data. Le flux retourné par
     * l'appelant reste positionné exactement au premier octet audio.
     */
    private static WavInfo parseHeader(BufferedInputStream input) throws IOException {
        byte[] riff = new byte[12];
        if (readFully(input, riff, 0, riff.length) != riff.length) {
            throw new IOException("WAV tronqué");
        }
        String riffId = ascii(riff, 0, 4);
        if (!"RIFF".equals(riffId) && !"RF64".equals(riffId)) {
            throw new IOException("Fichier non RIFF");
        }
        if (!"WAVE".equals(ascii(riff, 8, 4))) {
            throw new IOException("Fichier non WAVE");
        }

        WavInfo info = null;
        long rf64DataSize = -1L;

        while (true) {
            byte[] chunkHeader = new byte[8];
            if (readFully(input, chunkHeader, 0, 8) != 8) {
                throw new IOException("Chunk WAV data introuvable");
            }
            String id = ascii(chunkHeader, 0, 4);
            long size = uint32le(chunkHeader, 4);

            if ("ds64".equals(id)) {
                if (size < 28L) {
                    skipFully(input, size + (size & 1L));
                    continue;
                }
                byte[] ds64 = new byte[(int) Math.min(size, 64L)];
                int read = readFully(input, ds64, 0, ds64.length);
                if (read >= 16) {
                    rf64DataSize = int64le(ds64, 8);
                }
                long left = size - read;
                skipFully(input, left + (size & 1L));
                continue;
            }

            if ("fmt ".equals(id)) {
                if (size < 16L || size > 1024L * 1024L) {
                    throw new IOException("Bloc WAV fmt invalide");
                }
                byte[] fmt = new byte[(int) size];
                if (readFully(input, fmt, 0, fmt.length) != fmt.length) {
                    throw new IOException("Bloc WAV fmt tronqué");
                }
                if ((size & 1L) != 0L) skipFully(input, 1L);
                info = parseFormat(fmt);
                continue;
            }

            if ("data".equals(id)) {
                if (info == null) {
                    throw new IOException("WAV sans bloc fmt avant data");
                }
                long dataSize = size;
                if (dataSize == 0xFFFFFFFFL && rf64DataSize > 0L) {
                    dataSize = rf64DataSize;
                }
                info.dataSize = dataSize;
                return info;
            }

            skipFully(input, size + (size & 1L));
        }
    }

    static WavInfo parseFormat(byte[] fmt) throws IOException {
        int format = uint16le(fmt, 0);
        int channels = uint16le(fmt, 2);
        int sampleRate = (int) uint32le(fmt, 4);
        int blockAlign = uint16le(fmt, 12);
        int bits = uint16le(fmt, 14);

        if (format == FORMAT_EXTENSIBLE && fmt.length >= 40) {
            // SubFormat GUID : les 4 premiers octets reprennent le code WAVE classique.
            format = (int) uint32le(fmt, 24);
        }

        if (format != FORMAT_PCM && format != FORMAT_IEEE_FLOAT) {
            throw new IOException("WAV compressé non pris en charge (" + format + ")");
        }
        if (channels < 1 || channels > 8) {
            throw new IOException("Nombre de canaux WAV non pris en charge : " + channels);
        }
        if (sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IOException("Fréquence WAV non prise en charge : " + sampleRate);
        }
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32 && bits != 64) {
            throw new IOException("Profondeur WAV non prise en charge : " + bits + " bits");
        }
        if (format == FORMAT_IEEE_FLOAT && bits != 32 && bits != 64) {
            throw new IOException("WAV float invalide : " + bits + " bits");
        }

        int bytesPerSample = Math.max(1, bits / 8);
        int minimumAlign = channels * bytesPerSample;
        if (blockAlign < minimumAlign) blockAlign = minimumAlign;

        WavInfo info = new WavInfo();
        info.format = format;
        info.channels = channels;
        info.outputChannels = Math.min(channels, 2);
        info.sampleRate = sampleRate;
        info.bitsPerSample = bits;
        info.bytesPerSample = bytesPerSample;
        info.blockAlign = blockAlign;
        return info;
    }

    private static void encodePcmStream(BufferedInputStream input, WavInfo info,
                                        File output, long startUs, long durationUs)
            throws IOException {
        long totalFrames = info.dataSize < 0L
                ? Long.MAX_VALUE
                : info.dataSize / Math.max(1, info.blockAlign);
        long startFrame = safeMulDiv(startUs, info.sampleRate, 1_000_000L);
        startFrame = Math.min(Math.max(0L, startFrame), totalFrames);

        long requestedFrames = Math.max(1L,
                safeMulDiv(durationUs, info.sampleRate, 1_000_000L));
        long availableFrames = totalFrames == Long.MAX_VALUE
                ? requestedFrames
                : Math.max(0L, totalFrames - startFrame);
        long framesToEncode = Math.min(requestedFrames, availableFrames);

        long bytesToSkip = safeMultiply(startFrame, info.blockAlign);
        skipFully(input, bytesToSkip);

        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int muxTrack = -1;

        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    AAC_MIME, info.sampleRate, info.outputChannels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE,
                    info.outputChannels == 1 ? 112_000 : 192_000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

            encoder = MediaCodec.createEncoderByType(AAC_MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            long framesQueued = 0L;
            boolean inputEnded = false;
            boolean outputEnded = false;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            byte[] sourceBuffer = new byte[256 * 1024];
            byte[] pcm16Buffer = new byte[256 * 1024];

            while (!outputEnded) {
                if (!inputEnded) {
                    int inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer codecInput = encoder.getInputBuffer(inputIndex);
                        if (codecInput == null) {
                            throw new IOException("Buffer AAC d'entrée indisponible");
                        }
                        codecInput.clear();

                        long remainingFrames = Math.max(0L, framesToEncode - framesQueued);
                        int maxFramesByCodec = codecInput.capacity()
                                / Math.max(1, info.outputChannels * 2);
                        int maxFramesBySource = sourceBuffer.length
                                / Math.max(1, info.blockAlign);
                        int frameCount = (int) Math.min(remainingFrames,
                                Math.max(1, Math.min(maxFramesByCodec, maxFramesBySource)));

                        if (remainingFrames <= 0L || frameCount <= 0) {
                            long pts = safeMulDiv(framesQueued, 1_000_000L, info.sampleRate);
                            encoder.queueInputBuffer(inputIndex, 0, 0, pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            int sourceBytesWanted = frameCount * info.blockAlign;
                            int sourceBytesRead = readFullyAtMost(
                                    input, sourceBuffer, 0, sourceBytesWanted);
                            int actualFrames = sourceBytesRead / Math.max(1, info.blockAlign);
                            if (actualFrames <= 0) {
                                long pts = safeMulDiv(framesQueued, 1_000_000L, info.sampleRate);
                                encoder.queueInputBuffer(inputIndex, 0, 0, pts,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                int pcmBytes = convertToPcm16(
                                        sourceBuffer, actualFrames, info, pcm16Buffer);
                                if (pcmBytes > codecInput.capacity()) {
                                    throw new IOException("Buffer AAC trop petit");
                                }
                                codecInput.put(pcm16Buffer, 0, pcmBytes);
                                long pts = safeMulDiv(framesQueued, 1_000_000L, info.sampleRate);
                                encoder.queueInputBuffer(inputIndex, 0, pcmBytes, pts, 0);
                                framesQueued += actualFrames;
                            }
                        }
                    }
                }

                while (true) {
                    int outputIndex = encoder.dequeueOutputBuffer(
                            bufferInfo, inputEnded ? CODEC_TIMEOUT_US : 0L);
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new IOException("Format AAC modifié deux fois");
                        }
                        muxTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (outputIndex >= 0) {
                        ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                        if (encoded == null) {
                            throw new IOException("Buffer AAC de sortie indisponible");
                        }

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        if (bufferInfo.size > 0) {
                            if (!muxerStarted || muxTrack < 0) {
                                throw new IOException("Muxer AAC non démarré");
                            }
                            encoded.position(bufferInfo.offset);
                            encoded.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(muxTrack, encoded, bufferInfo);
                        }
                        outputEnded = (bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(outputIndex, false);
                        if (outputEnded) break;
                    }
                }
            }
        } catch (Exception error) {
            String message = error.getMessage();
            throw new IOException(message == null || message.trim().isEmpty()
                    ? "Encodeur AAC indisponible"
                    : message, error);
        } finally {
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                try { encoder.release(); } catch (Exception ignored) { }
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static int convertToPcm16(byte[] source, int frames,
                                      WavInfo info, byte[] output) throws IOException {
        int out = 0;
        for (int frame = 0; frame < frames; frame++) {
            int frameBase = frame * info.blockAlign;
            for (int outChannel = 0; outChannel < info.outputChannels; outChannel++) {
                int inChannel = outChannel;
                int offset = frameBase + inChannel * info.bytesPerSample;
                short sample = decodeSample(source, offset, info.format, info.bitsPerSample);
                output[out++] = (byte) (sample & 0xFF);
                output[out++] = (byte) ((sample >>> 8) & 0xFF);
            }
        }
        return out;
    }

    private static short decodeSample(byte[] data, int offset,
                                      int format, int bits) throws IOException {
        if (format == FORMAT_IEEE_FLOAT) {
            if (bits == 32) {
                int raw = (data[offset] & 0xFF)
                        | ((data[offset + 1] & 0xFF) << 8)
                        | ((data[offset + 2] & 0xFF) << 16)
                        | ((data[offset + 3] & 0xFF) << 24);
                float value = Float.intBitsToFloat(raw);
                value = Math.max(-1f, Math.min(1f, value));
                return (short) Math.round(value * 32767f);
            }
            if (bits == 64) {
                long raw = ((long) data[offset] & 0xFFL)
                        | (((long) data[offset + 1] & 0xFFL) << 8)
                        | (((long) data[offset + 2] & 0xFFL) << 16)
                        | (((long) data[offset + 3] & 0xFFL) << 24)
                        | (((long) data[offset + 4] & 0xFFL) << 32)
                        | (((long) data[offset + 5] & 0xFFL) << 40)
                        | (((long) data[offset + 6] & 0xFFL) << 48)
                        | (((long) data[offset + 7] & 0xFFL) << 56);
                double value = Double.longBitsToDouble(raw);
                value = Math.max(-1d, Math.min(1d, value));
                return (short) Math.round(value * 32767d);
            }
            throw new IOException("WAV float " + bits + " bits non pris en charge");
        }

        switch (bits) {
            case 8:
                return (short) (((data[offset] & 0xFF) - 128) << 8);
            case 16: {
                int v = (data[offset] & 0xFF)
                        | (data[offset + 1] << 8);
                return (short) v;
            }
            case 24: {
                int v = (data[offset] & 0xFF)
                        | ((data[offset + 1] & 0xFF) << 8)
                        | ((data[offset + 2] & 0xFF) << 16);
                if ((v & 0x00800000) != 0) v |= 0xFF000000;
                return (short) (v >> 8);
            }
            case 32: {
                int v = (data[offset] & 0xFF)
                        | ((data[offset + 1] & 0xFF) << 8)
                        | ((data[offset + 2] & 0xFF) << 16)
                        | ((data[offset + 3] & 0xFF) << 24);
                return (short) (v >> 16);
            }
            default:
                throw new IOException("PCM " + bits + " bits non pris en charge");
        }
    }

    private static int readFullyAtMost(InputStream input, byte[] buffer,
                                       int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(buffer, offset + total, length - total);
            if (read < 0) break;
            if (read == 0) continue;
            total += read;
        }
        return total;
    }

    private static int readFully(InputStream input, byte[] buffer,
                                 int offset, int length) throws IOException {
        return readFullyAtMost(input, buffer, offset, length);
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = Math.max(0L, bytes);
        byte[] scratch = new byte[16 * 1024];
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            int read = input.read(scratch, 0,
                    (int) Math.min((long) scratch.length, remaining));
            if (read < 0) throw new IOException("WAV tronqué pendant le déplacement");
            remaining -= read;
        }
    }

    private static String ascii(byte[] data, int offset, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) (data[offset + i] & 0xFF));
        }
        return builder.toString();
    }

    private static int uint16le(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long uint32le(byte[] data, int offset) {
        return ((long) data[offset] & 0xFFL)
                | (((long) data[offset + 1] & 0xFFL) << 8)
                | (((long) data[offset + 2] & 0xFFL) << 16)
                | (((long) data[offset + 3] & 0xFFL) << 24);
    }

    private static long int64le(byte[] data, int offset) {
        return ((long) data[offset] & 0xFFL)
                | (((long) data[offset + 1] & 0xFFL) << 8)
                | (((long) data[offset + 2] & 0xFFL) << 16)
                | (((long) data[offset + 3] & 0xFFL) << 24)
                | (((long) data[offset + 4] & 0xFFL) << 32)
                | (((long) data[offset + 5] & 0xFFL) << 40)
                | (((long) data[offset + 6] & 0xFFL) << 48)
                | (((long) data[offset + 7] & 0xFFL) << 56);
    }

    private static long safeMulDiv(long value, long multiplier, long divisor) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        if (value > Long.MAX_VALUE / multiplier) {
            double result = ((double) value * (double) multiplier) / (double) divisor;
            return result >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) result;
        }
        return (value * multiplier) / divisor;
    }

    private static long safeMultiply(long a, long b) throws IOException {
        if (a <= 0L || b <= 0L) return 0L;
        if (a > Long.MAX_VALUE / b) {
            throw new IOException("WAV trop volumineux");
        }
        return a * b;
    }

    static final class WavInfo {
        int format;
        int channels;
        int outputChannels;
        int sampleRate;
        int bitsPerSample;
        int bytesPerSample;
        int blockAlign;
        long dataSize = -1L;
    }
}
