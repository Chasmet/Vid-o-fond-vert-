package com.chasmet.fondvertstudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AudioWaveformExtractorTest {
    @Test
    public void normalizesWaveformWithoutLosingSilence() {
        float[] normalized = AudioWaveformExtractor.normalize(
                new float[]{0f, 0f, 0.04f, 0.16f, 0.50f, 1f});

        assertEquals(6, normalized.length);
        assertEquals(0f, normalized[0], 0.0001f);
        assertTrue(normalized[3] > normalized[2]);
        assertTrue(normalized[5] <= 1f);
        assertTrue(normalized[5] >= 0.99f);
    }

    @Test
    public void detectsSustainedStartAndIgnoresAnIsolatedPeak() {
        float[] peaks = new float[100];
        peaks[20] = 0.9f;
        for (int index = 60; index < 66; index++) peaks[index] = 0.25f;

        int detected = AudioWaveformExtractor.detectStartMs(peaks, 10_000_000L);

        assertTrue(detected >= 5_900);
        assertTrue(detected <= 6_050);
    }
}
