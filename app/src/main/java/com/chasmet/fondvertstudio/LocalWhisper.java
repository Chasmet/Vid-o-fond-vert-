package com.chasmet.fondvertstudio;
final class LocalWhisper {
    static { System.loadLibrary("chkwhisper"); }
    interface Progress { void update(int value); }
    static native String[] transcribe(String model, float[] pcm, int threads, Progress progress);
    private LocalWhisper() {}
}
