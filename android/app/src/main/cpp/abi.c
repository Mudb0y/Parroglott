/*
 * Nothing calls into this library. Android picks an app's CPU architecture
 * from the native libraries in its APK, and every library Parroglott runs is
 * loaded from app storage instead, so without this one an x86 device that
 * translates ARM code would not run the app as arm64.
 */
const char parroglott_abi[] = "arm64-v8a";
