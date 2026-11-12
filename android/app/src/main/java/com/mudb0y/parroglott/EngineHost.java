package com.mudb0y.parroglott;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs Amazon's engine in a process of its own, holding one voice. The
 * manifest declares each subclass in a separate process, so several voices
 * stay loaded at once and a crash in the engine takes only its process down.
 */
public class EngineHost extends Service {
    private static final String TAG = "Parroglott";
    /** In place of an audio length: a word's start and end offsets and its first sample follow. */
    static final int WORD = -2;

    public static class H0 extends EngineHost {
    }

    public static class H1 extends EngineHost {
    }

    public static class H2 extends EngineHost {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Engine engine;

    private synchronized Engine engine() throws IOException {
        if (engine == null) engine = Engine.get(this, getClass().getSimpleName().substring(1));
        return engine;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private final IEngineHost.Stub binder = new IEngineHost.Stub() {
        @Override
        public String loaded() {
            Engine e = engine;
            return e == null ? null : e.loadedKey();
        }

        @Override
        public int load(String key) {
            VoiceStore.Voice v = VoiceStore.get(EngineHost.this).voice(key);
            if (v == null) return -1;
            try {
                Engine e = engine();
                return e.load(v) ? e.sampleRate() : -1;
            } catch (IOException | UnsatisfiedLinkError e) {
                Log.e(TAG, "starting the engine", e);
                return -1;
            }
        }

        @Override
        public void speak(String key, String text, int rate, int pitch, ParcelFileDescriptor out) {
            worker.execute(() -> run(key, text, rate, pitch, out));
        }

        @Override
        public void stop() {
            Engine e = engine;
            if (e != null) e.stop();
        }
    };

    private void run(String key, String text, int rate, int pitch, ParcelFileDescriptor fd) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new ParcelFileDescriptor.AutoCloseOutputStream(fd), 1 << 15))) {
            VoiceStore.Voice v = VoiceStore.get(this).voice(key);
            Engine e;
            try {
                e = engine();
            } catch (IOException | UnsatisfiedLinkError ex) {
                Log.e(TAG, "starting the engine", ex);
                e = null;
            }
            if (v == null || e == null) {
                out.writeInt(-1);
                return;
            }
            boolean[] started = {false};
            int r = e.speak(v, text, rate, pitch, new Engine.Sink() {
                @Override
                public void start(int sampleRate) {
                    try {
                        out.writeInt(sampleRate);
                        started[0] = true;
                    } catch (IOException ignored) {
                        // The reader has gone; the first audio() notices.
                    }
                }

                @Override
                public void word(int start, int end, long sample) {
                    try {
                        out.writeInt(WORD);
                        out.writeInt(start);
                        out.writeInt(end);
                        out.writeLong(sample);
                    } catch (IOException ignored) {
                        // The reader has gone; the next audio() notices.
                    }
                }

                @Override
                public boolean audio(byte[] pcm, int length) {
                    try {
                        out.writeInt(length);
                        out.write(pcm, 0, length);
                        // Unflushed audio waits for more; speech should not.
                        out.flush();
                        return true;
                    } catch (IOException ex) {
                        return false;
                    }
                }
            });
            if (!started[0]) out.writeInt(-1);
            out.writeInt(-1);
            out.writeInt(r);
        } catch (IOException ignored) {
            // Stopped: the speech service closed its end.
        }
    }
}
