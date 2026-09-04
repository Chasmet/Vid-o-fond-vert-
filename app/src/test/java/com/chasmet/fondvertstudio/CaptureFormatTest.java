package com.chasmet.fondvertstudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CaptureFormatTest {
    @Test
    public void createsVerticalSizes() {
        assertEquals(1080, CaptureFormat.videoWidth(false, 1080));
        assertEquals(1920, CaptureFormat.videoHeight(false, 1080));
        assertEquals(720, CaptureFormat.videoWidth(false, 720));
        assertEquals(1280, CaptureFormat.videoHeight(false, 720));
    }

    @Test
    public void createsHorizontalSizes() {
        assertEquals(1920, CaptureFormat.videoWidth(true, 1080));
        assertEquals(1080, CaptureFormat.videoHeight(true, 1080));
        assertEquals(1280, CaptureFormat.videoWidth(true, 720));
        assertEquals(720, CaptureFormat.videoHeight(true, 720));
    }

    @Test
    public void sanitizesUnknownFormat() {
        assertEquals(CaptureFormat.VERTICAL, CaptureFormat.sanitize("square"));
        assertEquals(CaptureFormat.HORIZONTAL,
                CaptureFormat.sanitize(CaptureFormat.HORIZONTAL));
    }
}
