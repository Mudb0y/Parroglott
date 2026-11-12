package com.mudb0y.parroglott;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Debug builds only. Renders text through the system TTS path into a WAV for
 * checking off-device:
 *   adb shell am start -n com.mudb0y.parroglott/.SelfTestActivity \
 *       --es voice pl-PL-jacek --es text "Dzień dobry" --es out jacek.wav
 * The file lands in the app's external files directory; results go to logcat
 * under ParroglottTest. --ei rate N and --ei pitch N set speech rate and
 * pitch, 100 being normal;
 * --ei stopafter MS stops speech that long after its first audio. --es lang TAG
 * asks for a language instead of a voice, the way screen readers do.
 * With --es download KEY (such as pol.pol.jacek) instead, it downloads and
 * installs that voice from Amazon and reports when it is in place; --es
 * remove KEY deletes it again. With
 * --es update-base URL, the updater reads URL/stable/update.json and
 * URL/dev/update.json instead of GitHub; an empty URL undoes it. With
 * --es crash java the process dies, for testing crash reports, and
 * --es report FILE writes the crash report the app would share into FILE.
 */
public class SelfTestActivity extends Activity {
    private static final String TAG = "ParroglottTest";
    private TextToSpeech tts;
    private long started, firstAudio;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if ("java".equals(getIntent().getStringExtra("crash"))) throw new IllegalStateException("self-test crash");
        String report = getIntent().getStringExtra("report");
        if (report != null) {
            new Thread(() -> {
                try (java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), report))) {
                    w.write(CrashReports.report(getApplicationContext()));
                    Log.i(TAG, "DONE report " + report);
                } catch (IOException e) {
                    Log.e(TAG, "FAIL report: " + e);
                }
                runOnUiThread(this::finish);
            }).start();
            return;
        }
        String base = getIntent().getStringExtra("update-base");
        if (base != null) {
            Updater.prefs(this).edit().putString("testBase", base.isEmpty() ? null : base)
                    .remove("outcome").remove("json").apply();
            Log.i(TAG, "DONE update-base " + base);
            finish();
            return;
        }
        String remove = getIntent().getStringExtra("remove");
        if (remove != null) {
            VoiceStore store = VoiceStore.get(this);
            VoiceStore.Voice v = store.voice(remove);
            if (v == null) {
                Log.e(TAG, "FAIL no voice " + remove);
            } else {
                store.remove(v);
                Log.i(TAG, "DONE remove " + remove);
            }
            finish();
            return;
        }
        String download = getIntent().getStringExtra("download");
        if (download != null) {
            download(download);
            return;
        }
        String voice = getIntent().getStringExtra("voice");
        String lang = getIntent().getStringExtra("lang");
        String text = getIntent().getStringExtra("text");
        String out = getIntent().getStringExtra("out");
        int rate = getIntent().getIntExtra("rate", 100);
        int pitch = getIntent().getIntExtra("pitch", 100);
        int stopAfter = getIntent().getIntExtra("stopafter", 0);
        if (text == null || out == null) {
            Log.e(TAG, "FAIL need text and out extras");
            finish();
            return;
        }
        File file = new File(getExternalFilesDir(null), out);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "FAIL engine did not bind: " + status);
                finish();
                return;
            }
            if (voice != null) {
                Voice found = null;
                for (Voice v : tts.getVoices()) if (v.getName().equals(voice)) found = v;
                if (found == null) {
                    Log.e(TAG, "FAIL no voice " + voice);
                    finish();
                    return;
                }
                tts.setVoice(found);
            }
            if (lang != null) {
                int r = tts.setLanguage(java.util.Locale.forLanguageTag(lang));
                Voice chosen = tts.getVoice();
                Log.i(TAG, "language " + lang + ": " + r + ", voice " + (chosen == null ? null : chosen.getName()));
            }
            tts.setSpeechRate(rate / 100f);
            tts.setPitch(pitch / 100f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onAudioAvailable(String id, byte[] audio) {
                    if (firstAudio != 0) return;
                    firstAudio = SystemClock.elapsedRealtime();
                    if (stopAfter > 0) new android.os.Handler(getMainLooper()).postDelayed(() -> {
                        tts.stop();
                        Log.i(TAG, "DONE stop requested");
                        finish();
                    }, stopAfter);
                }

                @Override
                public void onRangeStart(String id, int start, int end, int frame) {
                    // Android's file path hands these over as frame, start, end.
                    if (!(start < end && end <= text.length())) {
                        int f = start;
                        start = end;
                        end = frame;
                        frame = f;
                    }
                    Log.i(TAG, "WORD " + start + "-" + end + " at frame " + frame + ": "
                            + (start < end && end <= text.length() ? text.substring(start, end) : "?"));
                }

                @Override
                public void onDone(String id) {
                    long now = SystemClock.elapsedRealtime();
                    Log.i(TAG, String.format("DONE %s first-audio %dms total %dms",
                            file, firstAudio - started, now - started));
                    finish();
                }

                @Override
                public void onError(String id) {
                    Log.e(TAG, "FAIL synthesis error");
                    finish();
                }

                @Override
                public void onError(String id, int code) {
                    Log.e(TAG, "FAIL synthesis error " + code);
                    finish();
                }
            });
            started = SystemClock.elapsedRealtime();
            tts.synthesizeToFile(text, null, file, "selftest");
        }, getPackageName());
    }

    private void download(String key) {
        VoiceStore store = VoiceStore.get(this);
        VoiceStore.Voice v = store.voice(key);
        if (v == null) {
            Log.e(TAG, "FAIL no voice " + key);
            finish();
            return;
        }
        started = SystemClock.elapsedRealtime();
        new Thread(() -> {
            try {
                store.install(v, null);
                Log.i(TAG, "DONE download " + key + " in " + (SystemClock.elapsedRealtime() - started) + "ms");
            } catch (IOException e) {
                Log.e(TAG, "FAIL download " + key + ": " + e.getMessage());
            }
            runOnUiThread(this::finish);
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
