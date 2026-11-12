package com.mudb0y.parroglott;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/** Answers the old CHECK_TTS_DATA query: downloaded languages are available, the rest of Amazon's are not. */
public class CheckDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        VoiceStore store = VoiceStore.get(this);
        ArrayList<String> available = new ArrayList<>();
        ArrayList<String> unavailable = new ArrayList<>();
        for (VoiceStore.Voice v : store.catalog()) {
            for (String tag : v.languages) {
                Locale l = Locale.forLanguageTag(tag);
                String code = l.getISO3Language() + "-" + l.getISO3Country();
                if (store.installed(v)) {
                    unavailable.remove(code);
                    if (!available.contains(code)) available.add(code);
                } else if (!available.contains(code) && !unavailable.contains(code)) {
                    unavailable.add(code);
                }
            }
        }
        Intent result = new Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable);
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }
}
