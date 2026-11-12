#!/usr/bin/env bash
# Fetch the pieces the build takes from Amazon into work/firmware, each checked
# against a pinned hash:
#   fireos8: the Ivona engine from Fire OS 8.3.3.8 (Fire HD 10, 13th
#     generation), the two runtime libraries it was built against, and the
#     voice catalogue.
#   fireos8-2024: the catalogue of Fire OS 8.3.3.2, which still lists the
#     classic voices that 8.3.3.8 replaced with neural ones.
#   kindle: Joanna, a neural voice only Kindle e-readers carry, from the
#     Kindle Paperwhite (12th generation) 5.20.1 update.
# Run inside `nix develop`; parts already in place stay. FIREOS_UPDATE,
# FIREOS_2024_UPDATE and KINDLE_UPDATE may name local copies of the updates.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
firmware=$root/work/firmware

fireos8=https://fireos-tablet-src.s3.us-west-2.amazonaws.com/4amHNOvwW4KDYd5xDz75MnJ9nn/update-Fire_HD10_13th_Gen-RS8338_user_3339_0030132734852.bin
fireos8_2024=https://fireos-tablet-src.s3.us-west-2.amazonaws.com/3omHNOvwW4KDYd5xZz75MnJ3np/update-kindle-Fire_HD10_13th_Gen-RS8332_user_3115_0025837710212.bin
kindle=https://s3.amazonaws.com/firmwaredownloads/update_kindle_all_new_paperwhite_12th_5.20.1.bin
app=//system/system_ext/priv-app/IvonaTTS_tablet/lib/arm64

declare -A pinned=(
    [fireos8/libttsivona.so]=73de06887aa14178867a521d4674ca72c72d2f7f29c5030094894fdef99e02b4
    [fireos8/libtensorflowlite_c.so]=0ac9c38be2cae55c2078dd2e8e5964cec8434c16d9257ed8d10cda4ab330785a
    [fireos8/libc++_shared.so]=218ecc677aa79e1974f3968d2e0ecd0172c4f517188d04ebd7d45cbb285b5d03
    [fireos8/contentsupport.json]=1ad8cad5feb7a28b0d86e3e989229f70ac6088530f3322c50ca2fdc3b74835e6
    [fireos8-2024/contentsupport.json]=5d52e883f86421e0eda657b39f0e998adf134782ff2a3959c45957baec3126a7
    [kindle/en_us_joanna24_a11y.mpa]=d004c47131c3684a15daf795e30c81eaf33d39c721cd5425652faed590aa9d7d
    [kindle/en_us_joanna24_a11y.json]=2f6e087cf0f3efd5bfe85fe2cdab5c08e7600c9e226438dce622eb1f6bf136e6
    [kindle/en_us_joanna_addenda.lex]=4c4d1ea5a74933ea653bfc0732b1f24b3b583b726895e1e9efc16dedab707754
)

# matches DIR PART: whether DIR holds every pinned file of PART.
matches() {
    local key
    for key in "${!pinned[@]}"; do
        [ "${key%%/*}" = "$2" ] || continue
        [ "$( { sha256sum < "$1/${key#*/}"; } 2> /dev/null | cut -d' ' -f1)" = "${pinned[$key]}" ] || return 1
    done
}

# fetch PART DESCRIPTION COMMAND...: run COMMAND with $tmp as its output
# directory, then move the result into place if it matches the pins.
fetch() {
    local part=$1 what=$2
    shift 2
    matches "$firmware/$part" "$part" && return 0
    mkdir -p "$firmware"
    tmp=$(mktemp -d "$firmware/.fetch.XXXXXX")
    trap 'rm -rf "$tmp"' EXIT
    echo "fetch-firmware: reading $what"
    "$@"
    matches "$tmp" "$part" || { echo "fetch-firmware: $part does not match the pinned hashes" >&2; exit 1; }
    rm -rf "${firmware:?}/$part"
    mv "$tmp" "$firmware/$part"
    trap - EXIT
    echo "fetch-firmware: $firmware/$part"
}

fireos8() {
    "$PG_PYTHON" -I "$root/scripts/fireos-extract.py" "${FIREOS_UPDATE:-$fireos8}" "$tmp" \
        "$app/libttsivona.so" "$app/libtensorflowlite_c.so" "$app/libc++_shared.so" \
        //system/etc/contentsupport.json
}

fireos8_2024() {
    "$PG_PYTHON" -I "$root/scripts/fireos-extract.py" "${FIREOS_2024_UPDATE:-$fireos8_2024}" "$tmp" \
        //system/etc/contentsupport.json
}

kindle() {
    "$PG_PYTHON" -I "$root/scripts/kindle-extract.py" "${KINDLE_UPDATE:-$kindle}" "$tmp" \
        data/voice/english/en_us_joanna24_a11y.mpa \
        tts/english/en_us_joanna24_a11y.json tts/english/en_us_joanna_addenda.lex
}

fetch fireos8 "Fire OS 8.3.3.8 from ${FIREOS_UPDATE:-Amazon}" fireos8
fetch fireos8-2024 "Fire OS 8.3.3.2 from ${FIREOS_2024_UPDATE:-Amazon}" fireos8_2024
fetch kindle "Kindle 5.20.1 from ${KINDLE_UPDATE:-Amazon}, about 410 MB" kindle
