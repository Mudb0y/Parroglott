package com.mudb0y.parroglott;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * The engine processes, each holding one voice, so going back and forth
 * between a few voices does not reload them. A voice goes to the process
 * that already has it, else to the one used longest ago. The bindings last as
 * long as this process: Android ends the speech service whenever its last
 * client lets go, and the voices should outlive that.
 */
final class EnginePool {
    private static final String TAG = "Parroglott";
    private static final Class<?>[] HOSTS = {EngineHost.H0.class, EngineHost.H1.class, EngineHost.H2.class};
    private static final long BIND_TIMEOUT_MS = 10000;

    private static EnginePool instance;

    static synchronized EnginePool get(Context context) {
        if (instance == null) instance = new EnginePool(context.getApplicationContext());
        return instance;
    }

    /** After voices are installed or removed: every engine reads its list of them again. */
    static void voicesChanged() {
        EnginePool p;
        synchronized (EnginePool.class) {
            p = instance;
        }
        if (p != null) p.rescan();
    }

    private final class Slot implements ServiceConnection {
        final Class<?> cls;
        volatile IEngineHost host;
        boolean bound;
        long used;

        /** Asks the process itself, which keeps its voice when the speech service restarts. */
        boolean holds(String key) {
            IEngineHost h = host;
            try {
                return h != null && key.equals(h.loaded());
            } catch (RemoteException e) {
                return false;
            }
        }

        Slot(Class<?> cls) {
            this.cls = cls;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                host = IEngineHost.Stub.asInterface(service);
                notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, cls.getSimpleName() + " died");
            synchronized (this) {
                host = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (EnginePool.this) {
                app.unbindService(this);
                bound = false;
                host = null;
            }
        }

        /** Binds if need be and waits for the host; null if it does not come up. */
        IEngineHost connect() {
            synchronized (EnginePool.this) {
                if (!bound) bound = app.bindService(new Intent(app, cls), this, Context.BIND_AUTO_CREATE);
                if (!bound) return null;
            }
            long until = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS;
            synchronized (this) {
                while (host == null) {
                    long left = until - SystemClock.uptimeMillis();
                    if (left <= 0) return null;
                    try {
                        wait(left);
                    } catch (InterruptedException e) {
                        return null;
                    }
                }
                return host;
            }
        }
    }

    interface Sink {
        void start(int sampleRate);

        /** Return false to stop. */
        boolean audio(byte[] pcm, int length);

        /** A word, as offsets into the text, begins sample samples into the speech. */
        void word(int start, int end, long sample);
    }

    private final Context app;
    private final Slot[] slots = new Slot[HOSTS.length];
    private volatile Slot speaking;

    private EnginePool(Context app) {
        this.app = app;
        for (int i = 0; i < HOSTS.length; i++) slots[i] = new Slot(HOSTS[i]);
    }

    private synchronized Slot slotFor(String voice) {
        Slot pick = null;
        for (Slot s : slots)
            if (s.holds(voice)) pick = s;
        if (pick == null)
            for (Slot s : slots)
                if (pick == null || s.used < pick.used) pick = s;
        pick.used = SystemClock.uptimeMillis();
        return pick;
    }

    /** Gets a voice loaded somewhere before it is needed; false if it will not load. */
    boolean load(VoiceStore.Voice v) {
        Slot s = slotFor(v.key);
        IEngineHost h = s.connect();
        try {
            return h != null && h.load(v.key) > 0;
        } catch (RemoteException e) {
            Log.w(TAG, "loading " + v.key, e);
            return false;
        }
    }

    /**
     * Speaks with a voice on Android's rate and pitch scales. Blocks until
     * done, stopped, or failed; returns the engine's result, negative on
     * failure, Integer.MIN_VALUE when stopped by the sink.
     */
    int speak(VoiceStore.Voice v, String text, int rate, int pitch, Sink sink) {
        Slot s = slotFor(v.key);
        IEngineHost h = s.connect();
        if (h == null) {
            Log.w(TAG, s.cls.getSimpleName() + " did not start");
            return -1;
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "creating a pipe", e);
            return -1;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), 1 << 15))) {
            speaking = s;
            try {
                h.speak(v.key, text, rate, pitch, pipe[1]);
            } finally {
                pipe[1].close();
            }
            int sampleRate = in.readInt();
            if (sampleRate <= 0) return -1;
            sink.start(sampleRate);
            byte[] buf = new byte[1 << 15];
            for (int n; (n = in.readInt()) != -1; ) {
                if (n == EngineHost.WORD) {
                    int start = in.readInt(), end = in.readInt();
                    sink.word(start, end, in.readLong());
                    continue;
                }
                if (n > buf.length) buf = new byte[n];
                in.readFully(buf, 0, n);
                if (!sink.audio(buf, n)) {
                    stopHost(h);
                    return Integer.MIN_VALUE;
                }
            }
            return in.readInt();
        } catch (RemoteException | EOFException e) {
            Log.w(TAG, s.cls.getSimpleName() + " ended mid-sentence", e);
            return -1;
        } catch (IOException e) {
            Log.w(TAG, "reading speech", e);
            return -1;
        } finally {
            speaking = null;
        }
    }

    /** Cuts short whatever is being spoken; safe from any thread. */
    void stop() {
        Slot s = speaking;
        IEngineHost h = s == null ? null : s.host;
        if (h != null) stopHost(h);
    }

    private static void stopHost(IEngineHost h) {
        try {
            h.stop();
        } catch (RemoteException e) {
            Log.w(TAG, "stopping", e);
        }
    }

    private void rescan() {
        for (Slot s : slots) {
            IEngineHost h = s.host;
            if (h == null) continue;
            try {
                h.rescan();
            } catch (RemoteException e) {
                Log.w(TAG, "rescanning " + s.cls.getSimpleName(), e);
            }
        }
    }
}
