#!/usr/bin/env bash
# Prepare everything the Android build takes from Amazon: the engine, its two
# runtime libraries, the voice catalogue, and Joanna's model and metadata. Run
# inside `nix develop`, after fetch-firmware.sh.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
build=$root/work/build/android

rm -rf "$build"
mkdir -p "$build/assets/engine"
# All three load from app storage, the engine after being rewritten on the
# phone, since none is aligned for 16 KB pages.
cp "$fw/fireos8/libc++_shared.so" "$fw/fireos8/libtensorflowlite_c.so" "$fw/fireos8/libttsivona.so" "$build/assets/engine/"
"$PG_PYTHON" -I "$root/scripts/make-catalog.py" "$fw/fireos8/contentsupport.json" \
    "$fw/fireos8-2024/contentsupport.json" "$fw/kindle" \
    "$root/android/app/src/main/assets/catalog.json" "$build/assets"
echo "stage-android: $build"
