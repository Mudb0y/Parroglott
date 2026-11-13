package com.mudb0y.parroglott;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Builds a voice from the data file of an IVONA desktop voice. IVONA's
 * desktop and Fire releases of a generation share the database format; only
 * the code differs, and a desktop voice's code is for Windows. So the
 * database speaks through the code of an Amazon voice of the same language
 * and generation, the donor, fetched from Amazon: the database takes the
 * donor's name, and the donor's library is told the database's build stamp,
 * which is the one thing it checks the database by.
 */
final class Importer {
    /** IVONA's language codes, as a database's name carries them, and the voice lending its code. */
    private static final Map<String, String> DONORS = new HashMap<>();

    static {
        DONORS.put("en_us", "eng.usa.joey");
        DONORS.put("en_gb", "eng.gbr.brian");
    }

    /** Where a database's header keeps its name and its build stamp. */
    private static final int NAME_AT = 4, NAME_LENGTH = 32, STAMP_AT = 0x2c, HEADER = 0x30;

    /** A data file Parroglott can take, and what it takes to build it. */
    static final class Plan {
        final String key, name;
        final VoiceStore.Voice donor;
        final long stamp, size;

        Plan(String key, String name, VoiceStore.Voice donor, long stamp, long size) {
            this.key = key;
            this.name = name;
            this.donor = donor;
            this.stamp = stamp;
            this.size = size;
        }

        String language() {
            return donor.languages.get(0);
        }
    }

    /** Why a file cannot be imported, worded for the person who chose it. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    interface Progress {
        void update(int percent);
    }

    private Importer() {
    }

    /** Reads a data file's header and decides whether and how to import it. */
    static Plan inspect(Context c, Uri uri) throws IOException {
        byte[] head = new byte[HEADER];
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null || readFully(in, head) < HEADER) throw new Refused(c.getString(R.string.import_not_ivona));
        }
        if (head[1] != 'D' || head[2] != 'X' || head[3] != 'V') throw new Refused(c.getString(R.string.import_not_ivona));
        String vox = cString(head, NAME_AT, NAME_LENGTH);
        int cut = vox.lastIndexOf('_');
        if (!vox.startsWith("vox_") || cut <= 4) throw new Refused(c.getString(R.string.import_not_ivona));
        String code = vox.substring(4, cut);
        String name = vox.substring(cut + 1);
        if (!name.matches("[a-z]+")) throw new Refused(c.getString(R.string.import_not_ivona));
        String title = Character.toUpperCase(name.charAt(0)) + name.substring(1);

        VoiceStore store = VoiceStore.get(c);
        VoiceStore.Voice donor = store.voice(DONORS.get(code));
        if (donor == null) throw new Refused(c.getString(R.string.import_language, title));
        String key = donor.engine.substring(0, donor.engine.lastIndexOf('.') + 1) + name;
        VoiceStore.Voice have = store.voice(key);
        if (have != null && !have.imported) throw new Refused(c.getString(R.string.import_have, title));
        return new Plan(key, title, donor, stamp(head), size(c, uri));
    }

    /**
     * Builds the voice a plan describes and installs it, then has an engine
     * load it, removing it again if the engine will not.
     */
    static VoiceStore.Voice run(Context c, Plan p, Uri uri, Progress progress) throws IOException {
        VoiceStore store = VoiceStore.get(c);
        File tmp = new File(c.getFilesDir(), "unpack-" + p.key);
        VoiceStore.deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        VoiceStore.Voice v;
        try {
            RemoteZip donor = new RemoteZip(p.donor.url);
            Map<String, String[]> digests = VoiceStore.manifestDigests(
                    new ByteArrayInputStream(donor.read("META-INF/MANIFEST.MF", -1)));
            String database = null;
            for (String name : donor.names()) {
                if (name.startsWith("vox_")) database = name;
                else if (!name.endsWith("/") && !name.startsWith("META-INF/")) fetch(donor, name, digests, tmp);
            }
            if (database == null) throw new IOException(p.donor.name + "'s pack has no voice database");
            byte[] donorHead = donor.read(database, HEADER);

            copy(c, uri, new File(tmp, database), donorHead, p.size, progress);
            retune(tmp, stamp(donorHead), p.stamp);

            JSONObject o = new JSONObject()
                    .put("key", p.key)
                    .put("name", p.name)
                    .put("languages", new JSONArray().put(p.language()))
                    .put("kind", "classic")
                    .put("engine", p.donor.engine)
                    .put("url", p.donor.url)
                    .put("bytes", p.size)
                    .put("version", "imported, stamp " + p.stamp)
                    .put("imported", true);
            v = store.addImported(o, tmp);
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            VoiceStore.deleteTree(tmp);
        }
        if (!EnginePool.get(c).load(v)) {
            store.remove(v);
            throw new Refused(c.getString(R.string.import_unusable, p.name));
        }
        return v;
    }

    /** A donor file, checked against the donor pack's own manifest. */
    private static void fetch(RemoteZip zip, String name, Map<String, String[]> digests, File dir) throws IOException {
        String[] want = digests.get(name);
        if (want == null) throw new IOException(name + " is not in the pack's manifest");
        byte[] data = zip.read(name, -1);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(want[0]);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!Base64.getEncoder().encodeToString(md.digest(data)).equals(want[1]))
            throw new IOException(name + " does not match the pack's manifest");
        File f = new File(dir, name);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        // Android wants code it loads from app storage to be unwritable.
        if (name.endsWith(".so")) //noinspection ResultOfMethodCallIgnored
            f.setReadOnly();
    }

    /** Copies the chosen database in under the donor's file name, with the donor's name in its header. */
    private static void copy(Context c, Uri uri, File dest, byte[] donorHead, long size, Progress progress)
            throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int shown = -1;
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("cannot open the data file");
            int n = readFully(in, buf);
            if (n < HEADER) throw new IOException("the data file ended early");
            System.arraycopy(donorHead, NAME_AT, buf, NAME_AT, NAME_LENGTH);
            do {
                out.write(buf, 0, n);
                done += n;
                int pct = size > 0 ? (int) (done * 100 / size) : -1;
                if (pct != shown && progress != null) progress.update(shown = pct);
            } while ((n = in.read(buf)) > 0);
        }
        if (size > 0 && done != size) throw new IOException("the data file changed while it was copied");
    }

    /** Has the donor's library accept the imported database by its build stamp. */
    private static void retune(File dir, long from, long to) throws IOException {
        File[] files = dir.listFiles((d, n) -> n.startsWith("libvoice_") && n.endsWith(".so"));
        if (files == null || files.length != 1) throw new IOException("the donor has no single voice library");
        File lib = files[0];
        byte[] b;
        try (InputStream in = new FileInputStream(lib)) {
            b = VoiceStore.readBytes(in);
        }
        byte[] f = (from + ";").getBytes(StandardCharsets.US_ASCII);
        byte[] t = (to + ";").getBytes(StandardCharsets.US_ASCII);
        if (f.length != t.length) throw new IOException("the data file's build stamp does not fit the donor's");
        int at = -1, found = 0;
        outer:
        for (int i = 0; i + f.length <= b.length; i++) {
            for (int j = 0; j < f.length; j++)
                if (b[i + j] != f[j]) continue outer;
            at = i;
            found++;
        }
        if (found != 1) throw new IOException("the donor's library does not name its database once");
        System.arraycopy(t, 0, b, at, t.length);
        //noinspection ResultOfMethodCallIgnored
        lib.delete();
        try (OutputStream out = new FileOutputStream(lib)) {
            out.write(b);
        }
        //noinspection ResultOfMethodCallIgnored
        lib.setReadOnly();
    }

    private static long stamp(byte[] head) {
        return ByteBuffer.wrap(head, STAMP_AT, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
    }

    private static String cString(byte[] b, int at, int max) {
        int end = at;
        while (end < at + max && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.US_ASCII);
    }

    private static long size(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getLong(0);
        } catch (RuntimeException e) {
            // Some providers do not answer; the size is only for progress.
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).length();
        return -1;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
        return n;
    }

    /** Single files out of a zip on a web server, through range requests. */
    static final class RemoteZip {
        private final String url;
        private final Map<String, long[]> entries = new LinkedHashMap<>();

        RemoteZip(String url) throws IOException {
            this.url = url;
            byte[] tail = get("bytes=-65557");
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--)
                if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                    eocd = i;
                    break;
                }
            if (eocd < 0) throw new IOException("the pack on Amazon's server is not a zip");
            ByteBuffer e = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            long cdSize = e.getInt(eocd + 12) & 0xffffffffL, cdOffset = e.getInt(eocd + 16) & 0xffffffffL;
            ByteBuffer cd = ByteBuffer.wrap(get("bytes=" + cdOffset + "-" + (cdOffset + cdSize - 1)))
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int p = 0; p + 46 <= cd.limit() && cd.getInt(p) == 0x02014b50; ) {
                int method = cd.getShort(p + 10) & 0xffff;
                long csize = cd.getInt(p + 20) & 0xffffffffL, usize = cd.getInt(p + 24) & 0xffffffffL;
                int nlen = cd.getShort(p + 28) & 0xffff, xlen = cd.getShort(p + 30) & 0xffff;
                int clen = cd.getShort(p + 32) & 0xffff;
                long local = cd.getInt(p + 42) & 0xffffffffL;
                String name = new String(cd.array(), p + 46, nlen, StandardCharsets.UTF_8);
                entries.put(name, new long[] {method, csize, usize, local});
                p += 46 + nlen + xlen + clen;
            }
        }

        Iterable<String> names() {
            return entries.keySet();
        }

        /** An entry's contents, or only its first limit bytes when limit is not negative. */
        byte[] read(String name, int limit) throws IOException {
            long[] e = entries.get(name);
            if (e == null) throw new IOException(name + " is not in the pack");
            ByteBuffer local = ByteBuffer.wrap(get("bytes=" + e[3] + "-" + (e[3] + 29))).order(ByteOrder.LITTLE_ENDIAN);
            long data = e[3] + 30 + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff);
            long span;
            if (limit < 0) span = e[1];
            else if (e[0] == 0) span = Math.min(e[1], limit);
            // 64 KB of deflated input yields far more than a header's worth.
            else span = Math.min(e[1], 1 << 16);
            if (span == 0) return new byte[0];
            byte[] raw = get("bytes=" + data + "-" + (data + span - 1));
            InputStream in = e[0] == 8
                    ? new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(true))
                    : new ByteArrayInputStream(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            long want = limit < 0 ? e[2] : Math.min(limit, e[2]);
            try (InputStream i = in) {
                for (int n; out.size() < want && (n = i.read(buf, 0, (int) Math.min(buf.length, want - out.size()))) > 0; )
                    out.write(buf, 0, n);
            } catch (EOFException ex) {
                // A partial deflate stream ends abruptly once the wanted bytes are out.
            }
            if (out.size() != want) throw new IOException(name + " came back short from Amazon's server");
            return out.toByteArray();
        }

        private byte[] get(String range) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Range", range);
            try {
                if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL)
                    throw new IOException("Amazon answered " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    return VoiceStore.readBytes(in);
                }
            } finally {
                c.disconnect();
            }
        }
    }
}
