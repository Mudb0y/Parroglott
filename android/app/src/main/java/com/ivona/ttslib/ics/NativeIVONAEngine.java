package com.ivona.ttslib.ics;

/**
 * The engine's entry points, bound by name to libttsivona.so. The engine
 * behind them is a single global instance whatever object calls them.
 */
public class NativeIVONAEngine {
    public static native void loadMissingVoiceExtension();

    /** Both directories end in a slash; the first holds com.ivona.tts.oem/files/. */
    public static native void setEnvRuntime(String dataDir, String externalDir);

    public native void create();

    public native void destroy();

    public native String[] evaluateVoiceByLanguage(String language, String country, String variant);

    public native int getSampleRate();

    public native String[] getVersion();

    public native String[] onGetLanguage();

    /** ISO 639-2 language, lower-case ISO 3166 alpha-3 country, voice name; negative on failure. */
    public native int onLoadLanguage(String language, String country, String voice);

    public native int setContrast(int on);

    public native void stop();
}
