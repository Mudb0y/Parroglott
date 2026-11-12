#!/usr/bin/env bash
# Drive a debug build on the connected phone or emulator (ANDROID_SERIAL picks one).
#   device-selftest.sh download KEY
#       download voice KEY (such as pol.pol.jacek) from Amazon and install it
#   device-selftest.sh say VOICE_ID WHISPER_LANG TEXT [RATE]
#       render TEXT with VOICE_ID through the system TTS path, pull the WAV
#       and print whisper's transcription
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
pkg=com.mudb0y.parroglott
out=$root/work/out/device
mkdir -p "$out"

# result SECONDS: wait for the activity's DONE or FAIL line in logcat.
result() {
    local line=
    for _ in $(seq $(($1 * 2))); do
        line=$(adb logcat -d -s ParroglottTest:* | grep -E 'DONE|FAIL' | tail -1 || true)
        [ -n "$line" ] && break
        sleep 0.5
    done
    echo "device-selftest: ${line:-no result after $1 s}"
    case $line in *DONE*) ;; *) adb logcat -d -s Parroglott:* AndroidRuntime:* DEBUG:* linker:* | tail -40; exit 1 ;; esac
}

case ${1:-} in
download)
    key=${2:?usage: device-selftest.sh download KEY}
    adb logcat -c
    adb shell am start -W -n "$pkg/.SelfTestActivity" --es download "$key" > /dev/null
    result 600
    ;;
say)
    voice=${2:?usage: device-selftest.sh say VOICE_ID WHISPER_LANG TEXT [RATE]}
    lang=${3:?}
    text=${4:?}
    rate=${5:-100}
    file=$voice-$rate.wav
    adb logcat -c
    adb shell am start -W -n "$pkg/.SelfTestActivity" --es voice "$voice" --es text "$(printf %q "$text")" \
        --ei rate "$rate" --es out "$file" > /dev/null
    result 60
    adb pull "/sdcard/Android/data/$pkg/files/$file" "$out/$file" > /dev/null
    "$root/scripts/transcribe.sh" "$out/$file" "$lang"
    ;;
*)
    sed -n '2,8p' "$0" >&2
    exit 1
    ;;
esac
