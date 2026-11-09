package com.robat2.mobile;

/** JNI bridge to the bundled llama.cpp runtime. No network APIs are used. */
public final class LocalModelEngine {
    static {
        System.loadLibrary("robat2_jni");
    }
    private LocalModelEngine() {}
    public static native boolean loadModel(String modelPath);
    public static native String generate(String prompt, int maxTokens);
    public static native void release();
}
