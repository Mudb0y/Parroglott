package com.mudb0y.parroglott;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Fetches voices from Amazon, one at a time, while showing progress. */
public class DownloadService extends Service {
    static final String EXTRA_VOICE = "voice";
    static final String ACTION_CANCEL = "com.mudb0y.parroglott.CANCEL";
    private static final String CHANNEL = "downloads";
    private static final int ONGOING_ID = 1;
    private static final String TAG = "Parroglott";

    enum Phase { QUEUED, DOWNLOADING, INSTALLING, FAILED }

    static final class State {
        volatile Phase phase = Phase.QUEUED;
        volatile int percent;
        volatile String error;
    }

    private static final Map<String, State> states = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    static State state(VoiceStore.Voice v) {
        return states.get(v.key);
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) r.run();
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger doneIds = new AtomicInteger(100);
    private NotificationManager nm;
    private VoiceStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        store = VoiceStore.get(this);
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String key = intent == null ? null : intent.getStringExtra(EXTRA_VOICE);
        VoiceStore.Voice v = key == null ? null : store.voice(key);
        if (v == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.add(key);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        State s = states.get(key);
        if (s != null && s.phase != Phase.FAILED) return START_NOT_STICKY;
        cancelled.remove(key);
        states.put(key, new State());
        changed();
        pending.incrementAndGet();
        Notification n = progress(getString(R.string.notif_downloading, label(v)), 0);
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else
            startForeground(ONGOING_ID, n);
        worker.submit(() -> run(v));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    static String label(android.content.Context c, VoiceStore.Voice v) {
        return c.getString(R.string.voice_label, v.name,
                Locale.forLanguageTag(v.languages.get(0)).getDisplayName());
    }

    private String label(VoiceStore.Voice v) {
        return label(this, v);
    }

    private void run(VoiceStore.Voice v) {
        String key = v.key;
        State s = states.get(key);
        int[] shown = {-1};
        try {
            s.phase = Phase.DOWNLOADING;
            changed();
            store.install(v, (done, total) -> {
                if (cancelled.contains(key)) return false;
                int pct = (int) (done * 100 / total);
                if (pct != s.percent) {
                    s.percent = pct;
                    changed();
                }
                if (done == total) {
                    s.phase = Phase.INSTALLING;
                    changed();
                    nm.notify(ONGOING_ID, progress(getString(R.string.notif_installing, label(v)), -1));
                } else if (pct / 5 != shown[0]) {
                    shown[0] = pct / 5;
                    nm.notify(ONGOING_ID, progress(getString(R.string.notif_downloading, label(v)), pct));
                }
                return true;
            });
            states.remove(key);
            finished(getString(R.string.notif_installed, label(v)));
        } catch (Exception e) {
            if (cancelled.remove(key) || e instanceof VoiceStore.Cancelled) {
                store.discardDownload(v);
                states.remove(key);
            } else {
                Log.w(TAG, "downloading " + key, e);
                s.phase = Phase.FAILED;
                s.error = e.getMessage();
                finished(getString(R.string.notif_failed, label(v), e.getMessage()));
            }
        } finally {
            changed();
            if (pending.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private Notification progress(String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, VoicesActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(text)
                .setContentText(percent >= 0 ? percent + "%" : null)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void finished(String text) {
        nm.notify(doneIds.incrementAndGet(), new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, VoicesActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    private void stopIfIdle() {
        if (pending.get() == 0) stopSelf();
    }
}
