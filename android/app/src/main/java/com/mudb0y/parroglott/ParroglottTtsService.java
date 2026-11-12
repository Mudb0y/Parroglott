package com.mudb0y.parroglott;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class ParroglottTtsService extends TextToSpeechService {
    private static final String TAG = "Parroglott";

    private VoiceStore store;
    private EnginePool pool;
    private volatile boolean stopped;
    private volatile long stopAt;
    private volatile String[] currentLanguage = {"eng", "USA", ""};

    @Override
    public void onCreate() {
        store = VoiceStore.get(this);
        pool = EnginePool.get(this);
        // The engine runs whenever a screen reader does, even if the app is never opened.
        Updater.schedule(this);
        super.onCreate();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        return m == null ? TextToSpeech.LANG_NOT_SUPPORTED : (Integer) m[1];
    }

    @Override
    protected String[] onGetLanguage() {
        return currentLanguage;
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        if (m == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        int level = (Integer) m[1];
        if (level >= 0) currentLanguage = iso3Triple((String) m[0]);
        return level;
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        for (VoiceStore.Voice v : store.installed())
            for (String l : v.languages) out.add(toAndroid(v, l));
        return out;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        return store.find(name) != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) {
        Object[] f = store.find(name);
        if (f == null) return TextToSpeech.ERROR;
        return pool.load((VoiceStore.Voice) f[0]) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        if (m == null) return null;
        VoiceStore.Voice v = store.best((String) m[0]);
        return v == null ? null : v.id((String) m[0]);
    }

    @Override
    protected void onStop() {
        stopAt = SystemClock.elapsedRealtime();
        stopped = true;
        pool.stop();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback cb) {
        VoiceStore.Voice v;
        Object[] named = store.find(request.getVoiceName());
        if (named != null) {
            v = (VoiceStore.Voice) named[0];
        } else {
            Object[] m = store.match(request.getLanguage(), request.getCountry());
            v = m == null ? null : store.best((String) m[0]);
        }
        if (v == null) {
            cb.error(TextToSpeech.ERROR_NOT_INSTALLED_YET);
            return;
        }
        String text = request.getCharSequenceText().toString();
        int[] chars = charOffsets(text);
        stopped = false;
        int r = pool.speak(v, text, request.getSpeechRate(), request.getPitch(), new EnginePool.Sink() {
            @Override
            public void start(int sampleRate) {
                cb.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1);
            }

            @Override
            public void word(int start, int end, long sample) {
                if (start < 0 || end >= chars.length || start >= end) return;
                cb.rangeStart((int) sample, chars[start], chars[end]);
            }

            @Override
            public boolean audio(byte[] pcm, int length) {
                int max = cb.getMaxBufferSize();
                for (int off = 0; off < length; off += max)
                    if (cb.audioAvailable(pcm, off, Math.min(max, length - off)) != TextToSpeech.SUCCESS)
                        return false;
                return true;
            }
        });
        if (stopped) Log.i(TAG, "stopped in " + (SystemClock.elapsedRealtime() - stopAt) + "ms");
        if (r < 0 && !stopped) {
            Log.w(TAG, "synthesis failed: " + r);
            cb.error(TextToSpeech.ERROR_SYNTHESIS);
        } else {
            cb.done();
        }
    }

    /**
     * The engine counts word positions in bytes of the text as Android's JNI
     * hands it over: UTF-8, but with NUL as two bytes and an unpaired
     * surrogate as three. This maps each such byte to its UTF-16 index.
     */
    private static int[] charOffsets(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i)))
            bytes += utf8Length(text.codePointAt(i));
        int[] map = new int[bytes + 1];
        int b = 0;
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i)))
            for (int n = utf8Length(text.codePointAt(i)); n > 0; n--) map[b++] = i;
        map[bytes] = text.length();
        return map;
    }

    private static int utf8Length(int c) {
        return c == 0 ? 2 : c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
    }

    private static String[] iso3Triple(String tag) {
        Locale l = Locale.forLanguageTag(tag);
        return new String[] {l.getISO3Language(), l.getISO3Country(), ""};
    }

    private static Voice toAndroid(VoiceStore.Voice v, String language) {
        return new Voice(v.id(language), Locale.forLanguageTag(language),
                v.neural ? Voice.QUALITY_VERY_HIGH : Voice.QUALITY_HIGH,
                Voice.LATENCY_LOW, false, new HashSet<>());
    }
}
