#!/bin/sh
#
#     tools/sign.sh                    # every unsigned APK from the last build
#     tools/sign.sh some-build.apk     # or name them
#
# For APKs that arrive already built. When keystore.properties has the two
# password lines, Gradle signs during assembleRelease and this is unnecessary.
# Passwords are asked for once and passed through the environment, never argv.

set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
properties="$root/keystore.properties"

# Read a key out of keystore.properties. Deliberately not a general INI parser:
# this file has four keys and Gradle's Properties loader defines the format.
property() {
    [ -f "$properties" ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$properties" | head -1
}

keystore=$(property storeFile); : "${keystore:=${RESTICDROID_KEYSTORE:-}}"
alias=$(property keyAlias);     : "${alias:=${RESTICDROID_KEY_ALIAS:-resticdroid}}"

if [ -z "$keystore" ]; then
    echo "sign: no keystore." >&2
    echo "Set storeFile in $properties, or export RESTICDROID_KEYSTORE." >&2
    exit 2
fi
if [ ! -f "$keystore" ]; then
    echo "sign: $keystore does not exist." >&2
    exit 2
fi

apksigner=$(command -v apksigner || true)
if [ -z "$apksigner" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
        sdk=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$root/local.properties" | head -1)
    fi
    : "${sdk:=$HOME/Android/Sdk}"
    apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
fi
if [ -z "${apksigner:-}" ]; then
    echo "sign: no apksigner on PATH and none under $sdk/build-tools." >&2
    echo "Install your distribution's Android build-tools package, or set" >&2
    echo "ANDROID_HOME to an SDK that has one." >&2
    exit 2
fi

# Passwords go through the environment, never argv: apksigner's command line is
# readable by every process on a desktop.
store_password=$(property storePassword); : "${store_password:=${RESTICDROID_KEYSTORE_PASSWORD:-}}"
key_password=$(property keyPassword);     : "${key_password:=${RESTICDROID_KEY_PASSWORD:-}}"

if [ -z "$store_password" ] && [ -r /dev/tty ] && [ -w /dev/tty ]; then
    # Echo goes off before the prompt is printed, not after: anything typed
    # between the two would otherwise appear on screen.
    if stty_saved=$(stty -g < /dev/tty 2>/dev/null); then
        # Restore the terminal even if the read is interrupted; leaving echo
        # off is a nasty thing to do to somebody's shell.
        trap 'stty "$stty_saved" < /dev/tty 2>/dev/null; exit 130' INT TERM HUP
        stty -echo < /dev/tty
    fi
    printf 'Keystore password: ' > /dev/tty
    IFS= read -r store_password < /dev/tty || store_password=
    if [ -n "${stty_saved:-}" ]; then
        stty "$stty_saved" < /dev/tty
        trap - INT TERM HUP
    fi
    printf '\n' > /dev/tty
    if [ -z "$store_password" ]; then
        echo "sign: no password given." >&2
        exit 1
    fi
fi

if [ $# -eq 0 ]; then
    set -- "$root"/app/build/outputs/apk/release/*-unsigned.apk
    [ -f "$1" ] || { echo "sign: no unsigned APKs; run ./gradlew assembleRelease first." >&2; exit 1; }
fi

for apk in "$@"; do
    signed=$(printf '%s' "$apk" | sed 's/-unsigned//')
    [ "$signed" != "$apk" ] || signed="${apk%.apk}-signed.apk"

    RESTICDROID_STORE_PASS="$store_password" \
    RESTICDROID_KEY_PASS="${key_password:-$store_password}" \
    "$apksigner" sign \
        --ks "$keystore" \
        --ks-key-alias "$alias" \
        ${store_password:+--ks-pass env:RESTICDROID_STORE_PASS} \
        ${store_password:+--key-pass env:RESTICDROID_KEY_PASS} \
        --out "$signed" \
        "$apk"

    echo "==> $signed"
    "$apksigner" verify --print-certs "$signed" | grep -i 'certificate DN\|SHA-256 digest'
done

echo
echo "Check that digest against the one your installed build was signed with."
echo "If it differs, Android will refuse the upgrade and you must uninstall first."
