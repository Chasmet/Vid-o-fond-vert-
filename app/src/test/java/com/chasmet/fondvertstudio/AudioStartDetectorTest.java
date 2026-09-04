package com.chasmet.fondvertstudio;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

public final class AudioStartDetectorTest {
    @Test
    public void measuresPcm16Silence() {
        ByteBuffer samples = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        while (samples.hasRemaining()) samples.putShort((short) 0);
        samples.flip();
        assertEquals(0d, AudioStartDetector.calculateRms(
                samples, AudioFormat.ENCODING_PCM_16BIT), 0.000001d);
    }

    @Test
    public void measuresPcm16Signal() {
        ByteBuffer samples = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        samples.putShort((short) 16384);
        samples.putShort((short) -16384);
        samples.putShort((short) 16384);
        samples.putShort((short) -16384);
        samples.flip();
        assertEquals(0.5d, AudioStartDetector.calculateRms(
                samples, AudioFormat.ENCODING_PCM_16BIT), 0.0001d);
    }
}
