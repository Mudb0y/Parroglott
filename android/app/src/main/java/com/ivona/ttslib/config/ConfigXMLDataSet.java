package com.ivona.ttslib.config;

/** The engine's view of where voices live, bound by name to libttsivona.so. */
public final class ConfigXMLDataSet {
    private ConfigXMLDataSet() {
    }

    public static native String[] getNotifyUnusedDirs();

    /** One package name per entry of IvonaPackagesInfo.getOEMpackages(), in the same order. */
    public static native String[] getPackagesCfg();

    public static native String[] getSettingsCfg();

    public static native String[] getSystemDirs();
}
