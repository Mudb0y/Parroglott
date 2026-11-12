package com.mudb0y.parroglott;

import android.app.Application;
import android.os.Build;
import android.os.Process;

/** Runs first in every process start: the speech service's and each engine process's. */
public class ParroglottApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            CrashReports.saveJava(this, t, e);
            if (next != null) {
                next.uncaughtException(t, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
        // The engine processes start and die often; one announcer is enough.
        if (Build.VERSION.SDK_INT >= 28 && getPackageName().equals(Application.getProcessName()))
            CrashReports.announce(this);
    }
}
