package com.mudb0y.parroglott;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A crash-report row when Parroglott last ended badly, the updates row, the
 * import row, then one row per language; a language opens its voices, a
 * voice opens what can be done with it.
 */
public class VoicesActivity extends Activity {
    private VoiceStore store;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private final List<String> languages = new ArrayList<>();
    private static final int PICK_DATA_FILE = 1;
    private TextToSpeech tts;
    private final Runnable refresh = () -> runOnUiThread(this::fill);
    /** Read once per visit, not on every download tick. */
    private boolean crashed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = VoiceStore.get(this);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int lead = crashed ? 3 : 2;
            if (crashed && position == 0) startActivity(new Intent(this, CrashReportActivity.class));
            else if (position == lead - 2) startActivity(new Intent(this, UpdatesActivity.class));
            else if (position == lead - 1) pickDataFile();
            else language(languages.get(position - lead));
        });
        setContentView(list);
        fill();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        tts = new TextToSpeech(this, status -> { }, getPackageName());
        Updater.schedule(this);
        if (saved == null) Updater.check(this, false, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.addListener(refresh);
        Updater.addListener(refresh);
        crashed = CrashReports.count(this) > 0;
        fill();
    }

    @Override
    protected void onPause() {
        DownloadService.removeListener(refresh);
        Updater.removeListener(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }

    private static String languageName(String tag) {
        return Locale.forLanguageTag(tag).getDisplayName();
    }

    private List<VoiceStore.Voice> voices(String language) {
        List<VoiceStore.Voice> out = new ArrayList<>();
        for (VoiceStore.Voice v : store.catalog())
            if (v.languages.contains(language)) out.add(v);
        return out;
    }

    private void fill() {
        languages.clear();
        for (VoiceStore.Voice v : store.catalog())
            for (String l : v.languages)
                if (!languages.contains(l)) languages.add(l);
        Collator order = Collator.getInstance();
        languages.sort((a, b) -> order.compare(languageName(a), languageName(b)));
        rows.clear();
        if (crashed) rows.add(getString(R.string.crash_row));
        rows.add(Updater.summary(this));
        rows.add(getString(R.string.row_import));
        for (String lang : languages) {
            List<VoiceStore.Voice> all = voices(lang);
            int ready = 0;
            for (VoiceStore.Voice v : all) if (store.installed(v)) ready++;
            VoiceStore.Voice def = store.best(lang);
            rows.add(def == null
                    ? getString(R.string.row_language, languageName(lang), ready, all.size())
                    : getString(R.string.row_language_default, languageName(lang), ready, all.size(), def.title(this)));
        }
        adapter.notifyDataSetChanged();
    }

    private boolean busy(VoiceStore.Voice v) {
        DownloadService.State s = DownloadService.state(v);
        return s != null && s.phase != DownloadService.Phase.FAILED;
    }

    private String line(VoiceStore.Voice v, VoiceStore.Voice def) {
        boolean isDefault = def != null && def.key.equals(v.key);
        if (v.imported) {
            StringBuilder b = new StringBuilder(v.name).append(", ").append(getString(R.string.kind_imported));
            if (isDefault) b.append(", ").append(getString(R.string.state_default));
            return b.append('.').toString();
        }
        StringBuilder b = new StringBuilder(v.name).append(", ")
                .append(getString(v.neural ? R.string.kind_neural : R.string.kind_classic)).append(". ");
        DownloadService.State s = DownloadService.state(v);
        if (s != null && s.phase == DownloadService.Phase.FAILED) {
            b.append(getString(R.string.state_failed, s.error));
        } else if (s != null && s.phase == DownloadService.Phase.INSTALLING) {
            b.append(getString(R.string.state_installing));
        } else if (s != null) {
            b.append(getString(R.string.state_downloading, s.percent));
        } else if (store.installed(v)) {
            b.append(getString(R.string.state_downloaded));
        } else {
            b.append(getString(R.string.state_available, v.megabytes()));
        }
        if (isDefault) b.append(", ").append(getString(R.string.state_default));
        return b.append('.').toString();
    }

    private void pickDataFile() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), PICK_DATA_FILE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DATA_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                Importer.Plan p = Importer.inspect(this, uri);
                runOnUiThread(() -> confirmImport(p, uri));
            } catch (IOException e) {
                String message = e instanceof Importer.Refused ? e.getMessage()
                        : getString(R.string.import_unreadable, e.getMessage());
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.row_import)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            }
        }).start();
    }

    private void confirmImport(Importer.Plan p, Uri uri) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.row_import)
                .setMessage(getString(R.string.import_confirm, p.name, languageName(p.language()),
                        Math.max(1, Math.round(p.size / 1e6)), p.donor.name))
                .setPositiveButton(R.string.action_import, (d, w) -> startForegroundService(
                        new Intent(this, DownloadService.class)
                                .setAction(DownloadService.ACTION_IMPORT)
                                .setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void language(String lang) {
        List<VoiceStore.Voice> voices = voices(lang);
        VoiceStore.Voice def = store.best(lang);
        String[] lines = new String[voices.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = line(voices.get(i), def);
        new AlertDialog.Builder(this)
                .setTitle(languageName(lang))
                .setItems(lines, (d, which) -> voice(voices.get(which), lang))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void voice(VoiceStore.Voice v, String lang) {
        List<String> labels = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        VoiceStore.Voice def = store.best(lang);
        if (store.installed(v) && !busy(v)) {
            labels.add(getString(R.string.action_hear));
            runs.add(() -> hear(v, lang));
            if (def == null || !def.key.equals(v.key)) {
                labels.add(getString(R.string.action_default, languageName(lang)));
                runs.add(() -> {
                    store.setDefault(lang, v);
                    fill();
                });
            }
            labels.add(getString(v.imported ? R.string.action_remove_voice : R.string.action_remove));
            runs.add(() -> confirmRemove(v));
        } else if (busy(v)) {
            labels.add(getString(R.string.action_cancel_download));
            runs.add(() -> startService(new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_VOICE, v.key)));
        } else {
            labels.add(getString(R.string.action_download, v.megabytes()));
            runs.add(() -> startForegroundService(new Intent(this, DownloadService.class)
                    .putExtra(DownloadService.EXTRA_VOICE, v.key)));
        }
        new AlertDialog.Builder(this)
                .setTitle(line(v, def))
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(VoiceStore.Voice v) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_remove, DownloadService.label(this, v)))
                .setPositiveButton(R.string.action_remove, (d, w) -> {
                    new Thread(() -> {
                        store.remove(v);
                        runOnUiThread(this::fill);
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void hear(VoiceStore.Voice v, String lang) {
        Set<Voice> all = tts.getVoices();
        if (all == null) return;
        for (Voice av : all) {
            if (av.getName().equals(v.id(lang))) {
                tts.setVoice(av);
                tts.speak(v.sampleText(lang), TextToSpeech.QUEUE_FLUSH, null, "sample");
                return;
            }
        }
    }
}
