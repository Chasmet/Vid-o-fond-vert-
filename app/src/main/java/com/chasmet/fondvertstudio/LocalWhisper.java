package com.chasmet.fondvertstudio;
final class LocalWhisper {
    static { System.loadLibrary("chkwhisper"); }
    static native String[] transcribe(String model, float[] pcm, int threads);
    private LocalWhisper() {}
}
