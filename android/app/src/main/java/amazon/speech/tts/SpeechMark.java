package amazon.speech.tts;

/**
 * Fire OS's speech mark, which Amazon's engine creates and fills field by
 * field while speaking, after reading the type numbers below; it gives up on
 * the whole utterance if any of them is missing. Offsets are into the text.
 */
public final class SpeechMark {
    public static final int TYPE_PHONEME = 0;
    public static final int TYPE_WORD = 1;
    public static final int TYPE_SENTENCE = 2;
    public static final int TYPE_SSML = 3;
    public static final int TYPE_VISEME = 4;

    public int type;
    public long sampleOffset;
    public String name;
    public int startTextOffset;
    public int endTextOffset;
}
