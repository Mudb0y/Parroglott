#!/usr/bin/env bash
# Download every voice in the catalogue on the connected device or emulator
# (ANDROID_SERIAL), have it speak with word marks, then remove it again unless
# it was installed before. Needs a debug build. Prints one line per voice and
# exits nonzero if any failed. Usage: sweep-voices.sh [KEY...]
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
pkg=com.mudb0y.parroglott
catalog=$root/android/app/src/main/assets/catalog.json
out=$root/work/out/sweep
mkdir -p "$out"

# run EXTRAS...: start the self-test and print its DONE or FAIL line.
run() {
    adb logcat -c
    adb shell am start -W -n "$pkg/.SelfTestActivity" "$@" > /dev/null
    for _ in $(seq 1200); do
        line=$(adb logcat -d | grep -aoE 'ParroglottTest: (DONE|FAIL).*' | tail -1 || true)
        [ -n "$line" ] && { echo "${line#ParroglottTest: }"; return; }
        sleep 0.5
    done
    echo "FAIL no result after 600 s"
}

keys=("$@")
[ ${#keys[@]} -gt 0 ] || mapfile -t keys < <(jq -r '.voices[].key' "$catalog")
failed=0
for key in "${keys[@]}"; do
    lang=$(jq -r --arg k "$key" '.voices[] | select(.key == $k) | .languages[0]' "$catalog")
    name=$(jq -r --arg k "$key" '.voices[] | select(.key == $k) | .name' "$catalog")
    had=$(adb shell run-as $pkg test -f files/installed/$key && echo yes || echo no)
    r=$(run --es download "$key")
    case $r in DONE*) ;; *) echo "sweep: $key: download: $r"; failed=1; continue ;; esac
    id=$lang-${key##*.}
    r=$(run --es voice "$id" --es text "'$name. 1, 2, 3.'" --es out sweep.wav)
    words=$(adb logcat -d | grep -ac 'ParroglottTest: WORD' || true)
    adb pull "/sdcard/Android/data/$pkg/files/sweep.wav" "$out/$id.wav" > /dev/null 2>&1 || true
    secs=$(soxi -D "$out/$id.wav" 2> /dev/null || echo 0)
    if [[ $r == DONE* ]] && awk "BEGIN { exit !($secs > 0.5) }"; then
        echo "sweep: $id: ${secs}s, $words words, ${r#*first-audio }"
    else
        echo "sweep: $id: $r, ${secs}s"
        failed=1
    fi
    [ "$had" = yes ] || run --es remove "$key" > /dev/null
done
exit $failed
