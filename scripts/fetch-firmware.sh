#!/usr/bin/env bash
# Fetch the pieces the build takes from Amazon into work/firmware/fireos8, each
# checked against a pinned hash: the Ivona engine from Fire OS 8.3.3.8 (Fire HD
# 10, 13th generation), the two runtime libraries it was built against, and the
# voice catalogue. Run inside `nix develop`; files already in place stay.
# FIREOS_UPDATE may name a local copy of the update instead of Amazon's URL.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out=$root/work/firmware/fireos8
url=https://fireos-tablet-src.s3.us-west-2.amazonaws.com/4amHNOvwW4KDYd5xDz75MnJ9nn/update-Fire_HD10_13th_Gen-RS8338_user_3339_0030132734852.bin
app=//system/system_ext/priv-app/IvonaTTS_tablet/lib/arm64

declare -A pinned=(
    [libttsivona.so]=73de06887aa14178867a521d4674ca72c72d2f7f29c5030094894fdef99e02b4
    [libtensorflowlite_c.so]=0ac9c38be2cae55c2078dd2e8e5964cec8434c16d9257ed8d10cda4ab330785a
    [libc++_shared.so]=218ecc677aa79e1974f3968d2e0ecd0172c4f517188d04ebd7d45cbb285b5d03
    [contentsupport.json]=1ad8cad5feb7a28b0d86e3e989229f70ac6088530f3322c50ca2fdc3b74835e6
)

matches() {
    local name
    for name in "${!pinned[@]}"; do
        [ "$( { sha256sum < "$1/$name"; } 2> /dev/null | cut -d' ' -f1)" = "${pinned[$name]}" ] || return 1
    done
}

matches "$out" && exit 0

mkdir -p "$root/work/firmware"
tmp=$(mktemp -d "$root/work/firmware/.fetch.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

echo "fetch-firmware: reading Fire OS 8.3.3.8 from ${FIREOS_UPDATE:-Amazon}"
"$PG_PYTHON" -I "$root/scripts/fireos-extract.py" "${FIREOS_UPDATE:-$url}" "$tmp" \
    "$app/libttsivona.so" "$app/libtensorflowlite_c.so" "$app/libc++_shared.so" \
    //system/etc/contentsupport.json
matches "$tmp" || { echo "fetch-firmware: extracted files do not match the pinned hashes" >&2; exit 1; }
rm -rf "$out"
mv "$tmp" "$out"
trap - EXIT
echo "fetch-firmware: $out"
