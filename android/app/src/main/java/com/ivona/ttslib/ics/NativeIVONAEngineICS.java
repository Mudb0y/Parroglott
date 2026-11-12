package com.ivona.ttslib.ics;

import amazon.speech.tts.SpeechMark;

import java.util.ArrayList;
import java.util.List;

/** Synthesis. The engine calls audioAvailableClbk back on the synthesizing thread. */
public class NativeIVONAEngineICS extends NativeIVONAEngine {
    public interface Sink {
        /** PCM 16-bit mono in buffer[0, length); return false to stop. */
        boolean audio(byte[] buffer, int length);

        /** A word, as offsets into the text, begins sample samples into the speech. */
        default void word(int start, int end, long sample) {
        }
    }

    private static final int SYNTH_DONE = 0;
    private static final int MARK_WORD = SpeechMark.TYPE_WORD;

    private byte[] buffer;
    private Sink sink;
    /** The engine empties and refills this before each piece of audio it hands over. */
    private final ArrayList<SpeechMark> marks = new ArrayList<>();

    /** Takes Android's own scales: rate and pitch 100 are normal. */
    public native int setProperty(String name, String value);

    private native int synthesizeText(String text, byte[] buffer);

    private native int synthesizeText(String text, byte[] buffer, List<?> marks, int flags);

    /**
     * Blocks until the text is spoken, stopped or failed, reporting each word
     * to the sink before the audio it starts in; returns the engine's result.
     */
    public int speak(String text, byte[] buffer, Sink sink) {
        this.buffer = buffer;
        this.sink = sink;
        marks.clear();
        try {
            return synthesizeText(text, buffer, marks, 1 << MARK_WORD);
        } finally {
            this.sink = null;
        }
    }

    @SuppressWarnings("unused")
    private int audioAvailableClbk(int length, int status) {
        Sink s = sink;
        if (s == null) return -1;
        for (SpeechMark m : marks)
            if (m.type == MARK_WORD) s.word(m.startTextOffset, m.endTextOffset, m.sampleOffset);
        marks.clear();
        if (length > 0 && !s.audio(buffer, length)) return -1;
        return status == SYNTH_DONE ? 1 : 0;
    }
}
