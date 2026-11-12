#!/usr/bin/env bash
# Prepare everything the Android build takes from Amazon: the engine, its two
# runtime libraries, and the voice catalogue. Run inside `nix develop`, after
# fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware/fireos8
build=$root/work/build/android

rm -rf "$build"
mkdir -p "$build/jniLibs/arm64-v8a" "$build/assets/engine"
# The runtime libraries load from the APK like any app's; the engine itself is
# rewritten on the phone before it loads, so it travels as an asset.
cp "$fw/libc++_shared.so" "$fw/libtensorflowlite_c.so" "$build/jniLibs/arm64-v8a/"
cp "$fw/libttsivona.so" "$build/assets/engine/"
"$PG_PYTHON" -I "$root/scripts/make-catalog.py" "$fw/contentsupport.json" \
    "$root/android/app/src/main/assets/catalog.json"
echo "stage-android: $build"
