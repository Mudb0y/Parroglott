package com.ivona.ttslib.packages;

/** The voices the engine finds on disk, bound by name to libttsivona.so. */
public final class IvonaPackagesInfo {
    private IvonaPackagesInfo() {
    }

    /** api version;state of files;voice name;engine version;directory;vox file, per voice. */
    public static native String[] getOEMpackages();
}
