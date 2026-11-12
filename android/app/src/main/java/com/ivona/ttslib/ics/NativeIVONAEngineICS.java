package com.ivona.ttslib.ics;

import java.util.List;

/** Synthesis. The engine calls audioAvailableClbk back on the synthesizing thread. */
public class NativeIVONAEngineICS extends NativeIVONAEngine {
    public interface Sink {
        /** PCM 16-bit mono in buffer[0, length); return false to stop. */
        boolean audio(byte[] buffer, int length);
    }

    private static final int SYNTH_DONE = 0;

    private byte[] buffer;
    private Sink sink;

    /** Takes Android's own scales: rate and pitch 100 are normal. */
    public native int setProperty(String name, String value);

    private native int synthesizeText(String text, byte[] buffer);

    private native int synthesizeText(String text, byte[] buffer, List<?> marks, int flags);

    /** Blocks until the text is spoken, stopped or failed; returns the engine's result. */
    public int speak(String text, byte[] buffer, Sink sink) {
        this.buffer = buffer;
        this.sink = sink;
        try {
            return synthesizeText(text, buffer);
        } finally {
            this.sink = null;
        }
    }

    @SuppressWarnings("unused")
    private int audioAvailableClbk(int length, int status) {
        Sink s = sink;
        if (length > 0 && (s == null || !s.audio(buffer, length))) return -1;
        return status == SYNTH_DONE ? 1 : 0;
    }
}
