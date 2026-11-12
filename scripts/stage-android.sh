#!/usr/bin/env bash
# Prepare everything the Android build takes from Amazon: the engine, its two
# runtime libraries, and the voice catalogue. Run inside `nix develop`, after
# fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware/fireos8
build=$root/work/build/android

rm -rf "$build"
mkdir -p "$build/assets/engine"
# All three load from app storage, the engine after being rewritten on the
# phone, since none is aligned for 16 KB pages.
cp "$fw/libc++_shared.so" "$fw/libtensorflowlite_c.so" "$fw/libttsivona.so" "$build/assets/engine/"
"$PG_PYTHON" -I "$root/scripts/make-catalog.py" "$fw/contentsupport.json" \
    "$root/android/app/src/main/assets/catalog.json"
echo "stage-android: $build"
