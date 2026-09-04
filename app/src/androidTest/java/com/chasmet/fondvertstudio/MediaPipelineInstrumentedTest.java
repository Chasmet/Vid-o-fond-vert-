package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class MediaPipelineInstrumentedTest {
    @Test
    public void transcodesWav16_24_32Bits() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int bits : new int[]{16, 24, 32}) {
            File wav = createPcmWav(context, "pcm-" + bits + ".wav", bits, 400);
            File aac = new File(context.getCacheDir(), "pcm-" + bits + ".m4a");
            WavToAacTranscoder.transcode(context, Uri.fromFile(wav), aac,
                    0L, 300_000L);
            assertTrue(aac.isFile() && aac.length() > 0L);
            assertTrue(MuxerUtils.isAacAudio(context, Uri.fromFile(aac)));
            wav.delete();
            aac.delete();
        }
    }

    @Test
    public void analyzesWaveformAndAutomaticStartInOneDecodePass() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = createPcmWav(context, "waveform-analysis.wav", 16, 2_000, 800);
        try {
            AudioWaveformExtractor.Analysis analysis = AudioWaveformExtractor.analyze(
                    context, Uri.fromFile(wav), 2_000L, 120);
            assertEquals(120, analysis.waveform.length);
            assertTrue(analysis.detectedStartMs >= 300);
            assertTrue(analysis.detectedStartMs <= 1_100);
            float maximum = 0f;
            for (float peak : analysis.waveform) maximum = Math.max(maximum, peak);
            assertTrue(maximum > 0.1f);
        } finally {
            wav.delete();
        }
    }

    @Test
    public void playbackAnimationDoesNotChangeTheSelectedAudioStart() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger selectionCallbacks = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AudioTimelineView timeline = new AudioTimelineView(context);
            timeline.setDurationMs(30_000);
            timeline.setPositionMs(5_000);
            timeline.setOnPositionChangeListener(new AudioTimelineView.OnPositionChangeListener() {
                @Override
                public void onPositionChanged(int positionMs, boolean fromUser) {
                    selectionCallbacks.incrementAndGet();
                }

                @Override
                public void onScrubFinished(int positionMs) {
                    selectionCallbacks.incrementAndGet();
                }
            });
            timeline.setPlaybackPositionMs(8_500);
            assertEquals(8_500, timeline.getPositionMs());
            assertTrue(timeline.isPlaybackActive());
        });
        assertEquals(0, selectionCallbacks.get());
    }

    @Test
    public void assemblesMultiplePlansAndMuxesAudio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File first = new File(context.getCacheDir(), "plan-1.mp4");
        File second = new File(context.getCacheDir(), "plan-2.mp4");
        File assembled = new File(context.getCacheDir(), "assembled.mp4");
        File wav = createPcmWav(context, "mux.wav", 16, 800);
        File aac = new File(context.getCacheDir(), "mux.m4a");
        File finalVideo = new File(context.getCacheDir(), "muxed.mp4");
        try {
            createVideo(first, Color.BLUE);
            createVideo(second, Color.RED);
            ClipSourceTimeline timeline = new ClipSourceTimeline();
            timeline.add(new ClipSourceTimeline.Segment(first.getAbsolutePath(),
                    BackgroundSpec.Type.COLOR, null, Color.GREEN, "Bleu", 200L));
            timeline.add(new ClipSourceTimeline.Segment(second.getAbsolutePath(),
                    BackgroundSpec.Type.COLOR, null, Color.GREEN, "Rouge", 200L));
            FastRawClipAssembler.assemble(timeline, assembled);
            assertTrue(MuxerUtils.hasVideoTrack(assembled));

            WavToAacTranscoder.transcode(context, Uri.fromFile(wav), aac,
                    0L, 600_000L);
            MuxerUtils.addAudio(context, assembled, Uri.fromFile(aac), finalVideo,
                    350_000L, 0L);
            assertTrue(MuxerUtils.hasVideoTrack(finalVideo));
            assertTrue(MuxerUtils.hasAudioTrack(finalVideo));
        } finally {
            first.delete();
            second.delete();
            assembled.delete();
            wav.delete();
            aac.delete();
            finalVideo.delete();
        }
    }

    @Test
    public void preservesPortraitRotationWhileMuxingImportedAudio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cameraVideo = new File(context.getCacheDir(), "portrait-camera.mp4");
        File wav = createPcmWav(context, "portrait-mux.wav", 16, 800);
        File aac = new File(context.getCacheDir(), "portrait-mux.m4a");
        File finalVideo = new File(context.getCacheDir(), "portrait-final.mp4");
        try {
            createVideo(cameraVideo, Color.BLUE, 90);
            WavToAacTranscoder.transcode(context, Uri.fromFile(wav), aac,
                    0L, 600_000L);
            MuxerUtils.addAudio(context, cameraVideo, Uri.fromFile(aac), finalVideo,
                    250_000L, 0L);

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(finalVideo.getAbsolutePath());
                assertEquals("90", retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } finally {
                retriever.release();
            }
            assertTrue(MuxerUtils.hasAudioTrack(finalVideo));
        } finally {
            cameraVideo.delete();
            wav.delete();
            aac.delete();
            finalVideo.delete();
        }
    }

    private static void createVideo(File output, int color) throws Exception {
        createVideo(output, color, 0);
    }

    private static void createVideo(File output, int color, int orientationHint)
            throws Exception {
        H264FrameEncoder encoder = new H264FrameEncoder(
                output, 320, 240, 30, orientationHint);
        Bitmap bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try {
            for (int frame = 0; frame < 8; frame++) {
                encoder.encode(bitmap, frame * 33_333L);
            }
            encoder.finish();
        } finally {
            bitmap.recycle();
            encoder.close();
        }
    }

    private static File createPcmWav(Context context, String name,
                                     int bits, int durationMs) throws Exception {
        return createPcmWav(context, name, bits, durationMs, 100);
    }

    private static File createPcmWav(Context context, String name,
                                     int bits, int durationMs, int silenceMs) throws Exception {
        int sampleRate = 8_000;
        int channels = 1;
        int bytesPerSample = bits / 8;
        int frames = sampleRate * durationMs / 1000;
        int dataSize = frames * channels * bytesPerSample;
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{'R', 'I', 'F', 'F'});
            writeIntLe(output, 36 + dataSize);
            output.write(new byte[]{'W', 'A', 'V', 'E'});
            output.write(new byte[]{'f', 'm', 't', ' '});
            writeIntLe(output, 16);
            writeShortLe(output, 1);
            writeShortLe(output, channels);
            writeIntLe(output, sampleRate);
            writeIntLe(output, sampleRate * channels * bytesPerSample);
            writeShortLe(output, channels * bytesPerSample);
            writeShortLe(output, bits);
            output.write(new byte[]{'d', 'a', 't', 'a'});
            writeIntLe(output, dataSize);
            byte[] samples = new byte[dataSize];
            int silenceFrames = sampleRate * Math.max(0, silenceMs) / 1000;
            for (int frame = silenceFrames; frame < frames; frame++) {
                double wave = Math.sin(frame * 2d * Math.PI * 440d / sampleRate) * 0.25d;
                long sample = Math.round(wave * ((1L << (bits - 1)) - 1L));
                int offset = frame * bytesPerSample;
                for (int index = 0; index < bytesPerSample; index++) {
                    samples[offset + index] = (byte) (sample >> (index * 8));
                }
            }
            output.write(samples);
        }
        return file;
    }

    private static void writeShortLe(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void writeIntLe(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }
}
