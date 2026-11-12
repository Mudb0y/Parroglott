package com.mudb0y.parroglott;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
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

    /** One voice pack from Amazon's catalogue. */
    static final class Voice {
        /** pol.pol.jacek: ISO 639-2 language, ISO 3166 alpha-3 country, name, as the engine names it. */
        final String key;
        final String name;
        /** BCP 47 tags; a few voices serve more than one country. */
        final List<String> languages;
        final boolean neural;
        final String url;
        final long bytes;
        final String version;

        Voice(JSONObject o) throws JSONException {
            key = o.getString("key");
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
            return language + "-" + key.substring(key.lastIndexOf('.') + 1);
        }

        String[] engineName() {
            return key.split("\\.");
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
        try (InputStream in = app.getAssets().open("catalog.json")) {
            JSONArray a = new JSONObject(readAll(in)).getJSONArray("voices");
            for (int i = 0; i < a.length(); i++) catalog.add(new Voice(a.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("APK assets are incomplete", e);
        }
    }

    /** The directory the engine scans; each installed voice is a directory named by its key. */
    File root() {
        return root;
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
        defaults.edit().putString(language, v.key).apply();
    }

    /** The voice a language speaks with when no voice is named: the one chosen in the app, else the first installed. */
    Voice best(String language) {
        Voice chosen = voice(defaults.getString(language, null));
        if (chosen != null && chosen.languages.contains(language) && installed(chosen)) return chosen;
        for (Voice v : installed())
            if (v.languages.contains(language)) return v;
        return null;
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
        File jar = downloadFile(v);
        // Unpacked beside the voices, so the engine never scans half a voice.
        File tmp = new File(root.getParentFile(), "unpack-" + v.key);
        download(v, jar, progress);
        try {
            deleteTree(tmp);
            unpack(jar, tmp);
            synchronized (this) {
                File dest = new File(root, v.key);
                deleteTree(dest);
                if (!tmp.renameTo(dest)) throw new IOException("cannot move " + tmp + " to " + dest);
                try (OutputStream out = new FileOutputStream(new File(state, v.key))) {
                    out.write(v.version.getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            jar.delete();
            deleteTree(tmp);
        }
        Engine.voicesChanged();
    }

    void remove(Voice v) {
        synchronized (this) {
            //noinspection ResultOfMethodCallIgnored
            new File(state, v.key).delete();
            deleteTree(new File(root, v.key));
        }
        Engine.voicesChanged();
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
