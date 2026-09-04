package com.chasmet.fondvertstudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class H264FrameEncoder implements AutoCloseable {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private final int width;
    private final int height;
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final int[] pixelBuffer;
    private final ExecutorService colorExecutor;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private boolean finished;

    H264FrameEncoder(File output, int width, int height, int frameRate) throws IOException {
        this(output, width, height, frameRate, 0);
    }

    H264FrameEncoder(File output, int width, int height, int frameRate,
                     int orientationHint) throws IOException {
        this.width = width;
        this.height = height;
        pixelBuffer = new int[width * height];
        int workers = Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors() - 1));
        colorExecutor = Executors.newFixedThreadPool(workers);
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        // L'ancien minimum de 4 Mb/s était trop faible en 1080x1920 pour des contours détourés.
        // On privilégie la qualité du montage final : ~10-24 Mb/s selon la définition.
        int bitrate = Math.max(10_000_000,
                Math.min(24_000_000, width * height * 12));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer = new MediaMuxer(output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (orientationHint == 90 || orientationHint == 180 || orientationHint == 270) {
            muxer.setOrientationHint(orientationHint);
        }
    }

    void encode(Bitmap bitmap, long presentationTimeUs) throws IOException {
        int inputIndex = dequeueInput();
        if (inputIndex < 0) {
            throw new IOException("Encodeur vidéo saturé");
        }
        Image image = codec.getInputImage(inputIndex);
        if (image != null) {
            writeBitmapToImage(bitmap, image);
        } else {
            ByteBuffer buffer = codec.getInputBuffer(inputIndex);
            if (buffer == null) {
                throw new IOException("Tampon encodeur indisponible");
            }
            writeI420(bitmap, buffer);
        }
        codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2,
                presentationTimeUs, 0);
        drain(false);
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        int inputIndex = dequeueInput();
        if (inputIndex < 0) {
            throw new IOException("Impossible de terminer l’encodage");
        }
        codec.queueInputBuffer(inputIndex, 0, 0, 0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(true);
        finished = true;
    }

    private int dequeueInput() {
        for (int attempt = 0; attempt < 20; attempt++) {
            int index = codec.dequeueInputBuffer(50_000);
            if (index >= 0) {
                return index;
            }
            try {
                drain(false);
            } catch (IOException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private void drain(boolean endOfStream) throws IOException {
        int idleCount = 0;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(bufferInfo,
                    endOfStream ? 20_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idleCount > 250) {
                    return;
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IOException("Format vidéo modifié deux fois");
                }
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex >= 0) {
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if (output == null) {
                    throw new IOException("Sortie encodeur indisponible");
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0) {
                    if (!muxerStarted) {
                        throw new IOException("Muxeur vidéo non démarré");
                    }
                    output.position(bufferInfo.offset);
                    output.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, output, bufferInfo);
                }
                boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputIndex, false);
                if (eos) {
                    return;
                }
            }
        }
    }

    private void writeBitmapToImage(Bitmap bitmap, Image image) throws IOException {
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height);
        Image.Plane[] planes = image.getPlanes();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        runParallelRows((startRow, endRow) -> {
            ByteBuffer yBuffer = planes[0].getBuffer().duplicate();
            ByteBuffer uBuffer = planes[1].getBuffer().duplicate();
            ByteBuffer vBuffer = planes[2].getBuffer().duplicate();
            for (int y = startRow; y < endRow; y++) {
                for (int x = 0; x < width; x++) {
                    int color = pixelBuffer[y * width + x];
                    int r = Color.red(color);
                    int g = Color.green(color);
                    int b = Color.blue(color);
                    int yValue = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                    yBuffer.put(y * yRowStride + x * yPixelStride, (byte) yValue);
                    if ((x & 1) == 0 && (y & 1) == 0) {
                        int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                        int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                        int chromaX = x / 2;
                        int chromaY = y / 2;
                        uBuffer.put(chromaY * uRowStride + chromaX * uPixelStride, (byte) u);
                        vBuffer.put(chromaY * vRowStride + chromaX * vPixelStride, (byte) v);
                    }
                }
            }
        });
    }

    private void writeI420(Bitmap bitmap, ByteBuffer buffer) throws IOException {
        buffer.clear();
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height);
        int frameSize = width * height;
        int uOffset = frameSize;
        int vOffset = frameSize + frameSize / 4;
        runParallelRows((startRow, endRow) -> {
            ByteBuffer localBuffer = buffer.duplicate();
            for (int y = startRow; y < endRow; y++) {
                for (int x = 0; x < width; x++) {
                    int color = pixelBuffer[y * width + x];
                    int r = Color.red(color);
                    int g = Color.green(color);
                    int b = Color.blue(color);
                    int yValue = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                    localBuffer.put(y * width + x, (byte) yValue);
                    if ((x & 1) == 0 && (y & 1) == 0) {
                        int chroma = (y / 2) * (width / 2) + x / 2;
                        int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                        int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                        localBuffer.put(uOffset + chroma, (byte) u);
                        localBuffer.put(vOffset + chroma, (byte) v);
                    }
                }
            }
        });
    }

    private void runParallelRows(RowWriter writer) throws IOException {
        int workers = Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors() - 1));
        int rowsPerTask = (height + workers - 1) / workers;
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            int start = worker * rowsPerTask;
            int end = Math.min(height, start + rowsPerTask);
            if (start >= end) break;
            tasks.add(() -> {
                writer.write(start, end);
                return null;
            });
        }
        try {
            List<Future<Void>> futures = colorExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion vidéo interrompue", error);
        } catch (ExecutionException error) {
            throw new IOException("Conversion vidéo impossible", error.getCause());
        }
    }

    private interface RowWriter {
        void write(int startRow, int endRow);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public void close() {
        colorExecutor.shutdownNow();
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
        if (muxerStarted) {
            try {
                muxer.stop();
            } catch (Exception ignored) {
            }
        }
        muxer.release();
    }
}
