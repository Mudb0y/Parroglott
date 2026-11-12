package com.mudb0y.parroglott;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** What voices Amazon offers, which are installed, and how Android locales map onto them. */
final class VoiceStore {

    /** One voice in the app's catalogue. */
    static final class Voice {
        /**
         * pol.pol.jacek, or eng.usa.salli.classic for an older voice that
         * shares its name with a newer one; also its directory's name.
         */
        final String key;
        /** ISO 639-2 language, ISO 3166 alpha-3 country and name, as the engine knows the voice. */
        final String engine;
        /**
         * For a voice built on the phone: the voice whose pack downloads, and
         * the APK asset directory holding what this voice puts in its place.
         */
        final String base, overlay;
        /** One of two voices with the same name, told apart by its key's last part. */
        final boolean variant;
        final String name;
        /** BCP 47 tags; a few voices serve more than one country. */
        final List<String> languages;
        final boolean neural;
        final String url;
        final long bytes;
        final String version;

        Voice(JSONObject o) throws JSONException {
            key = o.getString("key");
            engine = o.optString("engine", key);
            base = o.optString("base", null);
            overlay = o.optString("overlay", null);
            variant = key.split("\\.").length > 3;
            name = o.getString("name");
            JSONArray l = o.getJSONArray("languages");
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < l.length(); i++) tags.add(l.getString(i));
            languages = Collections.unmodifiableList(tags);
            neural = o.getString("kind").equals("neural");
            url = o.getString("url");
            bytes = o.getLong("bytes");
            version = o.getString("version");
        }

        /** The name Android knows this voice by in one of its languages, such as pl-PL-jacek. */
        String id(String language) {
            String[] k = key.split("\\.");
            StringBuilder b = new StringBuilder(language);
            for (int i = 2; i < k.length; i++) b.append('-').append(k[i]);
            return b.toString();
        }

        String[] engineName() {
            return engine.split("\\.");
        }

        /** The name people hear, with what tells it apart from a voice of the same name. */
        String title(Context c) {
            return variant ? c.getString(R.string.voice_variant, name,
                    c.getString(neural ? R.string.kind_neural : R.string.kind_classic)) : name;
        }

        int megabytes() {
            return (int) Math.max(1, Math.round(bytes / 1e6));
        }

        /** The language's own name for itself, then the voice's name. */
        String sampleText(String language) {
            Locale loc = Locale.forLanguageTag(language);
            return loc.getDisplayName(loc) + ". " + name;
        }
    }

    interface Progress {
        /** Return false to cancel. */
        boolean update(long done, long total);
    }

    /** When only a language is asked for, the country that speaks for it. */
    private static final Map<String, String> PREFERRED = new HashMap<>();

    static {
        PREFERRED.put("eng", "en-US");
        PREFERRED.put("spa", "es-ES");
        PREFERRED.put("fra", "fr-FR");
        PREFERRED.put("por", "pt-BR");
        PREFERRED.put("nld", "nl-NL");
    }

    private static VoiceStore instance;

    static synchronized VoiceStore get(Context context) {
        if (instance == null) instance = new VoiceStore(context.getApplicationContext());
        return instance;
    }

    private final File root;
    private final File state;
    private final AssetManager assets;
    private final List<Voice> catalog = new ArrayList<>();
    private final SharedPreferences defaults;

    private VoiceStore(Context app) {
        root = new File(app.getFilesDir(), "voices");
        state = new File(app.getFilesDir(), "installed");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        state.mkdirs();
        defaults = app.getSharedPreferences("defaults", Context.MODE_PRIVATE);
        assets = app.getAssets();
        try (InputStream in = assets.open("catalog.json")) {
            JSONArray a = new JSONObject(readAll(in)).getJSONArray("voices");
            for (int i = 0; i < a.length(); i++) catalog.add(new Voice(a.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("APK assets are incomplete", e);
        }
    }

    /** Where an installed voice's files are. */
    File folder(Voice v) {
        return new File(root, v.key);
    }

    List<Voice> catalog() {
        return Collections.unmodifiableList(catalog);
    }

    Voice voice(String key) {
        for (Voice v : catalog)
            if (v.key.equals(key)) return v;
        return null;
    }

    boolean installed(Voice v) {
        return new File(state, v.key).isFile() && new File(root, v.key).isDirectory();
    }

    List<Voice> installed() {
        List<Voice> out = new ArrayList<>();
        for (Voice v : catalog)
            if (installed(v)) out.add(v);
        return out;
    }

    /** An installed voice by its Android name, and the language that name is for. */
    Object[] find(String id) {
        if (id == null) return null;
        for (Voice v : installed())
            for (String l : v.languages)
                if (v.id(l).equals(id)) return new Object[] {v, l};
        return null;
    }

    void setDefault(String language, Voice v) {
        defaults.edit().putString(language, v.key).commit();
    }

    /** The voice a language speaks with when no voice is named. */
    Voice best(String language) {
        Voice chosen = voice(defaults.getString(language, null));
        if (chosen != null && chosen.languages.contains(language) && installed(chosen)) return chosen;
        for (Voice v : installed())
            if (v.languages.contains(language)) return v;
        return null;
    }

    /**
     * Writes down what best() answers for each of these languages, so that
     * installing another voice later never changes what a screen reader
     * speaks with; only the person choosing a voice does.
     */
    private void settle(List<String> languages) {
        SharedPreferences.Editor e = defaults.edit();
        for (String l : languages) {
            Voice b = best(l);
            if (b == null) e.remove(l);
            else e.putString(l, b.key);
        }
        e.commit();
    }

    /**
     * The language tag serving an ISO 639 / 3166 pair (either letter count),
     * with the TextToSpeech LANG_* level it reaches; LANG_MISSING_DATA when
     * Amazon has a voice for it that is not downloaded; null when unsupported.
     */
    Object[] match(String lang, String country) {
        Object[] ready = match(lang, country, true);
        if (ready != null) return ready;
        Object[] offered = match(lang, country, false);
        return offered == null ? null : new Object[] {offered[0], TextToSpeech.LANG_MISSING_DATA};
    }

    private Object[] match(String lang, String country, boolean installedOnly) {
        String l = iso3Language(lang);
        String c = iso3Country(country);
        if (l.isEmpty()) return null;
        List<String> tags = new ArrayList<>();
        for (Voice v : installedOnly ? installed() : catalog)
            for (String t : v.languages)
                if (!tags.contains(t)) tags.add(t);
        String langOnly = null;
        for (String tag : tags) {
            Locale loc = Locale.forLanguageTag(tag);
            if (!iso3Language(loc.getLanguage()).equals(l)) continue;
            if (!c.isEmpty() && loc.getISO3Country().equals(c))
                return new Object[] {tag, TextToSpeech.LANG_COUNTRY_AVAILABLE};
            if (langOnly == null) langOnly = tag;
        }
        if (langOnly == null) return null;
        String preferred = PREFERRED.get(l);
        if (preferred != null && tags.contains(preferred)) langOnly = preferred;
        return new Object[] {langOnly, TextToSpeech.LANG_AVAILABLE};
    }

    static String iso3Language(String lang) {
        if (lang == null || lang.isEmpty()) return "";
        String l = lang.toLowerCase(Locale.ROOT);
        try {
            return new Locale(l).getISO3Language();
        } catch (java.util.MissingResourceException e) {
            return l;
        }
    }

    static String iso3Country(String country) {
        if (country == null || country.isEmpty()) return "";
        try {
            return new Locale("", country).getISO3Country();
        } catch (java.util.MissingResourceException e) {
            return country.toUpperCase(Locale.ROOT);
        }
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private File downloadFile(Voice v) {
        return new File(root.getParentFile(), "download-" + v.key + ".jar");
    }

    /** Throws away a partial download, which install() otherwise resumes. */
    void discardDownload(Voice v) {
        //noinspection ResultOfMethodCallIgnored
        downloadFile(v).delete();
    }

    /**
     * Downloads a voice from Amazon, checks every file against the pack's own
     * manifest, and puts it where the engine looks. A download that fails
     * part-way resumes next time; one that is cancelled throws Cancelled.
     */
    void install(Voice v, Progress progress) throws IOException {
        // A language's first voice becomes its default; settle() keeps it so.
        settle(v.languages);
        File jar = downloadFile(v);
        // Unpacked beside the voices, so the engine never scans half a voice.
        File tmp = new File(root.getParentFile(), "unpack-" + v.key);
        download(v, jar, progress);
        try {
            deleteTree(tmp);
            unpack(jar, tmp);
            if (v.base != null) adapt(v, tmp);
            synchronized (this) {
                File dest = new File(root, v.key);
                deleteTree(dest);
                if (!tmp.renameTo(dest)) throw new IOException("cannot move " + tmp + " to " + dest);
                try (OutputStream out = new FileOutputStream(new File(state, v.key))) {
                    out.write(v.version.getBytes(StandardCharsets.UTF_8));
                }
            }
            settle(v.languages);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            jar.delete();
            deleteTree(tmp);
        }
    }

    /**
     * Turns the unpacked pack of v's base voice into v: the base's own
     * metadata gives way to v's from the APK, and the base's name in its
     * identity library becomes v's, which the catalogue keeps the same length.
     */
    private void adapt(Voice v, File dir) throws IOException {
        String from = v.base.substring(v.base.lastIndexOf('.') + 1);
        String to = v.engine.substring(v.engine.lastIndexOf('.') + 1);
        if (from.length() != to.length()) throw new IOException(v.key + " cannot take " + from + "'s place");
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("cannot list " + dir);
        for (File f : files)
            if (f.isFile() && f.getName().contains("_" + from)) //noinspection ResultOfMethodCallIgnored
                f.delete();

        File lib = new File(dir, "libtiver.so");
        byte[] b;
        try (InputStream in = new FileInputStream(lib)) {
            b = readBytes(in);
        }
        if (replaceAll(b, "_" + from, "_" + to) + replaceAll(b, "." + from, "." + to) == 0)
            throw new IOException(lib + " does not name " + from);
        //noinspection ResultOfMethodCallIgnored
        lib.delete();
        try (OutputStream out = new FileOutputStream(lib)) {
            out.write(b);
        }
        //noinspection ResultOfMethodCallIgnored
        lib.setReadOnly();

        copyAssets(v.overlay, dir);
    }

    /** Replaces one string with another of the same length throughout b; returns how many. */
    private static int replaceAll(byte[] b, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.US_ASCII), t = to.getBytes(StandardCharsets.US_ASCII);
        int n = 0;
        outer:
        for (int i = 0; i + f.length <= b.length; i++) {
            for (int j = 0; j < f.length; j++)
                if (b[i + j] != f[j]) continue outer;
            System.arraycopy(t, 0, b, i, t.length);
            n++;
        }
        return n;
    }

    private void copyAssets(String path, File dest) throws IOException {
        String[] kids = assets.list(path);
        if (kids == null || kids.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(dest)) {
                out.write(readBytes(in));
            }
            return;
        }
        for (String k : kids) copyAssets(path + "/" + k, new File(dest, k));
    }

    void remove(Voice v) {
        synchronized (this) {
            //noinspection ResultOfMethodCallIgnored
            new File(state, v.key).delete();
            deleteTree(new File(root, v.key));
        }
        settle(v.languages);
    }

    private static void download(Voice v, File dest, Progress progress) throws IOException {
        long have = dest.isFile() ? dest.length() : 0;
        if (have > v.bytes) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            have = 0;
        }
        if (have == v.bytes) return;
        HttpURLConnection c = (HttpURLConnection) new URL(v.url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            boolean append = code == HttpURLConnection.HTTP_PARTIAL;
            if (code != HttpURLConnection.HTTP_OK && !append) throw new IOException("Amazon answered " + code);
            if (!append) have = 0;
            byte[] buf = new byte[1 << 16];
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest, append)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    have += n;
                    if (progress != null && !progress.update(have, v.bytes)) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                        throw new Cancelled();
                    }
                }
            }
            if (have != v.bytes) throw new IOException("the download ended early");
        } finally {
            c.disconnect();
        }
    }

    private static void unpack(File jar, File dest) throws IOException {
        if (!dest.mkdirs()) throw new IOException("cannot create " + dest);
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
            if (mf == null) throw new IOException("the pack has no manifest");
            Map<String, String[]> digests;
            try (InputStream in = zip.getInputStream(mf)) {
                digests = manifestDigests(in);
            }
            byte[] buf = new byte[1 << 16];
            int files = 0;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                String name = z.getName();
                if (z.isDirectory() || name.startsWith("META-INF/")) continue;
                if (name.startsWith("/") || name.contains("..")) throw new IOException("the pack names " + name);
                String[] want = digests.get(name);
                if (want == null) throw new IOException(name + " is not in the pack's manifest");
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance(want[0]);
                } catch (NoSuchAlgorithmException ex) {
                    throw new IOException(ex);
                }
                File f = new File(dest, name);
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(z); OutputStream out = new FileOutputStream(f)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                if (!Base64.getEncoder().encodeToString(md.digest()).equals(want[1]))
                    throw new IOException(name + " does not match the pack's manifest");
                // Android wants code it loads from app storage to be unwritable.
                if (name.endsWith(".so")) //noinspection ResultOfMethodCallIgnored
                    f.setReadOnly();
                files++;
            }
            if (files != digests.size()) throw new IOException("the pack is missing files its manifest lists");
        }
    }

    /** Each named file's digest algorithm and Base64 value, from a JAR manifest. */
    private static Map<String, String[]> manifestDigests(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        for (String line; (line = r.readLine()) != null; ) {
            if (line.startsWith(" ") && !lines.isEmpty())
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + line.substring(1));
            else
                lines.add(line);
        }
        Map<String, String[]> out = new HashMap<>();
        String name = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                name = null;
            } else if (line.startsWith("Name: ")) {
                name = line.substring(6);
            } else if (name != null && line.startsWith("SHA-256-Digest: ")) {
                out.put(name, new String[] {"SHA-256", line.substring(16).trim()});
            } else if (name != null && line.startsWith("SHA1-Digest: ") && !out.containsKey(name)) {
                out.put(name, new String[] {"SHA-1", line.substring(13).trim()});
            }
        }
        return out;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
