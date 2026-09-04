package com.chasmet.fondvertstudio;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

public final class WavFormatTest {
    @Test
    public void acceptsPcm16() throws Exception {
        WavToAacTranscoder.WavInfo info = WavToAacTranscoder.parseFormat(
                formatBlock(1, 2, 44_100, 16));
        assertEquals(2, info.outputChannels);
        assertEquals(44_100, info.sampleRate);
        assertEquals(16, info.bitsPerSample);
    }

    @Test
    public void acceptsPcm24() throws Exception {
        WavToAacTranscoder.WavInfo info = WavToAacTranscoder.parseFormat(
                formatBlock(1, 1, 48_000, 24));
        assertEquals(3, info.bytesPerSample);
        assertEquals(3, info.blockAlign);
    }

    @Test
    public void acceptsPcm32() throws Exception {
        WavToAacTranscoder.WavInfo info = WavToAacTranscoder.parseFormat(
                formatBlock(1, 2, 96_000, 32));
        assertEquals(4, info.bytesPerSample);
        assertEquals(8, info.blockAlign);
    }

    private static byte[] formatBlock(int format, int channels,
                                      int sampleRate, int bits) {
        int bytes = bits / 8;
        int blockAlign = channels * bytes;
        ByteBuffer value = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        value.putShort((short) format);
        value.putShort((short) channels);
        value.putInt(sampleRate);
        value.putInt(sampleRate * blockAlign);
        value.putShort((short) blockAlign);
        value.putShort((short) bits);
        return value.array();
    }
}
