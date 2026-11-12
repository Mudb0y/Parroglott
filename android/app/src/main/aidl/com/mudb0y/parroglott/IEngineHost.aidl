package com.mudb0y.parroglott;

/** One engine process, holding one loaded voice. */
interface IEngineHost {
    /** The key of the voice this process holds, or null. */
    String loaded();

    /** Loads a voice ahead of speaking; returns its sample rate, or a negative number. */
    int load(String key);

    /**
     * Queues speech and returns at once. The host writes to pipe a big-endian
     * int sample rate (negative if the voice would not load), then frames of an
     * int length and that many bytes of 16-bit mono PCM, or of -2, a word's
     * int start and end in the text and its long first sample, then -1 and
     * the engine's result, and closes it. Closing the other end stops it.
     */
    void speak(String key, String text, int rate, int pitch, in ParcelFileDescriptor pipe);

    /** Cuts short whatever is being spoken. */
    void stop();

    /** Reads the installed voices again. */
    void rescan();
}
