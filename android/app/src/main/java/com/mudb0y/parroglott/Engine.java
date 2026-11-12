package com.mudb0y.parroglott;

import android.content.Context;
import android.util.Log;

import com.ivona.ttslib.config.ConfigXMLDataSet;
import com.ivona.ttslib.ics.NativeIVONAEngine;
import com.ivona.ttslib.ics.NativeIVONAEngineICS;
import com.ivona.ttslib.packages.IvonaPackagesInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import sonic.Sonic;

/**
 * Amazon's Ivona engine: one per process, one voice loaded at a time, so each
 * engine process (EngineHost) has its own. Thread-safe.
 */
final class Engine {
    private static final String TAG = "Parroglott";

    /** Where a Fire tablet keeps downloaded voices, written into the engine's own strings. */
    private static final String FIRE_VOICES =
            "/data/securedStorageLocation/com.amazon.providers.contentsupport/tts/ivona";
    /** Amazon's app's private directory, also written into the engine. */
    private static final String FIRE_FILES = "/data/data/com.ivona.tts.oem/files/";
    /** Short names for the files the engine keeps there, so the paths fit where Amazon's were. */
    private static final String[][] FIRE_FILE_NAMES = {
            {"current_voice.txt", "cv"},
            {"TURN_OFF_VOICE_EXTENSION", "nx"},
            {"current_voice_extension.txt", "cvx"},
    };

    /**
     * Above this, on Android's scale where 100 is normal, the engine barely
     * speeds up (Salli flattens out near 2.8 times), so Sonic does the rest.
     */
    private static final int ENGINE_MAX_RATE = 400;

    /** The libraries Amazon built the engine and voices against, in loading order. */
    private static final String[] RUNTIME = {"libc++_shared.so", "libtensorflowlite_c.so"};

    interface Sink extends NativeIVONAEngineICS.Sink {
        void start(int sampleRate);
    }

    private static Engine instance;

    /** id names this process's working files, so engines in different processes keep apart. */
    static synchronized Engine get(Context context, String id) throws IOException {
        if (instance == null) instance = new Engine(context.getApplicationContext(), id);
        return instance;
    }

    private final NativeIVONAEngineICS ivona = new NativeIVONAEngineICS();
    private final File runtime;
    private final byte[] buffer = new byte[16384];
    private volatile String loaded;
    private int sampleRate;

    private Engine(Context app, String id) throws IOException {
        VoiceStore store = VoiceStore.get(app);
        // Short, because the engine's own paths into it must fit where Amazon's were.
        runtime = new File(app.getFilesDir(), "e" + id);
        File own = new File(runtime, "com.ivona.tts.oem/files");
        if (!own.isDirectory() && !own.mkdirs()) throw new IOException("cannot create " + own);
        // Loaded by path first, so that the engine and the voices it loads
        // find them already in place under their sonames.
        for (String name : RUNTIME) System.load(runtimeLibrary(app, name).getPath());
        System.load(library(app, id, store.root(), runtime).getPath());
        String dir = runtime.getPath() + "/";
        NativeIVONAEngine.setEnvRuntime(dir, dir);
        rescan();
        ivona.create();
    }

    /**
     * Writes the list of installed voices the engine reads, the way Amazon's
     * own app does: one line per voice with an API version above zero,
     * prefixed by its package name.
     */
    synchronized void rescan() throws IOException {
        String[] found = IvonaPackagesInfo.getOEMpackages();
        String[] names = ConfigXMLDataSet.getPackagesCfg();
        if (found == null || names == null || found.length != names.length)
            throw new IOException("the engine's voice scan disagrees with itself");
        Map<String, String> rows = new TreeMap<>();
        for (int i = 0; i < found.length; i++) {
            String[] f = found[i] == null ? new String[0] : found[i].split(";");
            Log.i(TAG, "engine found " + names[i] + ": " + found[i]);
            try {
                if (f.length >= 6 && Integer.parseInt(f[0]) > 0) rows.put(names[i], found[i]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "unreadable voice entry " + found[i]);
            }
        }
        StringBuilder csv = new StringBuilder().append(System.currentTimeMillis()).append('\n');
        for (Map.Entry<String, String> r : rows.entrySet())
            csv.append(r.getKey()).append(";0;").append(r.getValue()).append('\n');
        File list = new File(runtime, "com.ivona.tts.oem/files/ivona_pkgs.csv");
        try (OutputStream out = new FileOutputStream(list)) {
            out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        }
        loaded = null;
    }

    /** Makes a voice the engine's current one; false when the engine will not load it. */
    synchronized boolean load(VoiceStore.Voice v) {
        if (v.key.equals(loaded)) return true;
        String[] n = v.engineName();
        int r = ivona.onLoadLanguage(n[0], n[1], n[2]);
        if (r < 0) {
            Log.w(TAG, "engine would not load " + v.key + ": " + r);
            loaded = null;
            return false;
        }
        loaded = v.key;
        sampleRate = ivona.getSampleRate();
        Log.i(TAG, "loaded " + v.key + " at " + sampleRate + " Hz");
        return true;
    }

    /**
     * Speaks text with a voice, rate and pitch on Android's scales, calling
     * sink.start with the sample rate before any audio. Blocks until done or
     * stopped; returns the engine's result, negative on failure.
     */
    synchronized int speak(VoiceStore.Voice v, String text, int rate, int pitch, Sink sink) {
        if (!load(v)) return -1;
        sink.start(sampleRate);
        ivona.setProperty("rate", Integer.toString(Math.min(rate, ENGINE_MAX_RATE)));
        ivona.setProperty("pitch", Integer.toString(pitch));
        if (rate <= ENGINE_MAX_RATE) return ivona.speak(text, buffer, sink);

        float speed = rate / (float) ENGINE_MAX_RATE;
        Sonic sonic = new Sonic(sampleRate, 1);
        sonic.setSpeed(speed);
        byte[] out = new byte[buffer.length * 2];
        boolean[] open = {true};
        int r = ivona.speak(text, buffer, new NativeIVONAEngineICS.Sink() {
            @Override
            public boolean audio(byte[] pcm, int length) {
                sonic.writeBytesToStream(pcm, length);
                return open[0] = drain(sonic, out, sink);
            }

            @Override
            public void word(int start, int end, long sample) {
                sink.word(start, end, (long) (sample / speed));
            }
        });
        if (r >= 0 && open[0]) {
            sonic.flushStream();
            drain(sonic, out, sink);
        }
        return r;
    }

    private static boolean drain(Sonic sonic, byte[] out, Sink sink) {
        for (int n; (n = sonic.readBytesFromStream(out, out.length)) > 0; )
            if (!sink.audio(out, n)) return false;
        return true;
    }

    /** The loaded voice's key, or null; answers even while speaking. */
    String loadedKey() {
        return loaded;
    }

    /** The loaded voice's sample rate. */
    synchronized int sampleRate() {
        return sampleRate;
    }

    /** Ends the current speak() early; safe from any thread. */
    void stop() {
        ivona.stop();
    }

    /**
     * Amazon's engine, rewritten to look for voices and keep its files in this
     * app's storage instead of a Fire tablet's. The APK carries Amazon's file
     * unchanged; the copy that loads is rebuilt whenever it would differ.
     */
    private static File library(Context app, String id, File voices, File runtime) throws IOException {
        byte[] lib;
        try (InputStream in = app.getAssets().open("engine/libttsivona.so")) {
            lib = VoiceStore.readBytes(in);
        }
        String v = voices.getPath();
        replaceString(lib, "<?xml version", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ivona_config>\n"
                + "  <location notify_unused=\"1\">\n"
                + "    <voice_data>" + v + "/</voice_data>\n"
                + "    <voice_lib>" + v + "/</voice_lib>\n"
                + "  </location>\n"
                + "</ivona_config>");
        for (int at : strings(lib, FIRE_VOICES)) {
            String old = cString(lib, at);
            replaceString(lib, old, v + old.substring(FIRE_VOICES.length()));
        }
        String files = runtime.getPath() + "/";
        for (String[] f : FIRE_FILE_NAMES) {
            try {
                replaceString(lib, FIRE_FILES + f[0], files + f[1]);
            } catch (IOException e) {
                // The engine only reads these for Amazon's voice extensions.
                Log.w(TAG, "left " + f[0] + " where Amazon put it: " + e.getMessage());
            }
        }

        File dir = new File(app.getCodeCacheDir(), "engine" + id);
        File out = new File(dir, "libttsivona.so");
        if (out.isFile()) {
            try (InputStream in = new FileInputStream(out)) {
                if (Arrays.equals(VoiceStore.readBytes(in), lib)) return out;
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, "libttsivona.so.tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(lib);
        }
        // Android wants code it loads from app storage to be unwritable.
        if (!tmp.setReadOnly()) throw new IOException("cannot make " + tmp + " read-only");
        if (!tmp.renameTo(out)) throw new IOException("cannot move " + tmp + " to " + out);
        return out;
    }

    /**
     * One of Amazon's runtime libraries, copied out of the APK once per
     * installed build. Engine processes may race to do it, so each writes
     * its own temporary file and renames it into place.
     */
    private static File runtimeLibrary(Context app, String name) throws IOException {
        File dir = new File(app.getCodeCacheDir(), "runtime");
        File out = new File(dir, name);
        File stamp = new File(dir, name + ".build");
        String build;
        try {
            build = Long.toString(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
        if (out.isFile() && stamp.isFile()) {
            try (InputStream in = new FileInputStream(stamp)) {
                if (VoiceStore.readAll(in).equals(build)) return out;
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        String pid = Integer.toString(android.os.Process.myPid());
        File tmp = new File(dir, name + "." + pid);
        try (InputStream in = app.getAssets().open("engine/" + name); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        if (!tmp.setReadOnly()) throw new IOException("cannot make " + tmp + " read-only");
        if (!tmp.renameTo(out)) throw new IOException("cannot move " + tmp + " to " + out);
        File stampTmp = new File(dir, name + ".build." + pid);
        try (OutputStream o = new FileOutputStream(stampTmp)) {
            o.write(build.getBytes(StandardCharsets.UTF_8));
        }
        if (!stampTmp.renameTo(stamp)) throw new IOException("cannot move " + stampTmp + " to " + stamp);
        return out;
    }

    /** Offsets of every C string in b that starts with prefix. */
    private static int[] strings(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        int[] found = new int[0];
        outer:
        for (int i = 0; i + p.length <= b.length; i++) {
            if (i > 0 && b[i - 1] != 0) continue;
            for (int k = 0; k < p.length; k++)
                if (b[i + k] != p[k]) continue outer;
            found = Arrays.copyOf(found, found.length + 1);
            found[found.length - 1] = i;
        }
        return found;
    }

    private static String cString(byte[] b, int at) {
        int end = at;
        while (b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.US_ASCII);
    }

    /** Overwrites the one C string starting with prefix, padding with NULs; it must fit. */
    private static void replaceString(byte[] b, String prefix, String with) throws IOException {
        int[] at = strings(b, prefix);
        if (at.length != 1) throw new IOException(at.length + " strings start with " + prefix);
        int end = at[0];
        while (b[end] != 0) end++;
        byte[] w = with.getBytes(StandardCharsets.US_ASCII);
        if (w.length > end - at[0]) throw new IOException("no room for " + with);
        System.arraycopy(w, 0, b, at[0], w.length);
        Arrays.fill(b, at[0] + w.length, end, (byte) 0);
    }
}
