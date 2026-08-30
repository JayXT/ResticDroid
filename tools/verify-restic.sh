#!/bin/sh
#
# Asserts the shape of the built binaries: position-independent, right machine,
# dynamically linked against bionic. The last is what makes DNS work at all.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JNI=${RESTIC_OUT:-$ROOT/restic-android/src/main/jniLibs}
status=0

if ! command -v readelf > /dev/null 2>&1; then
    echo "verify-restic: readelf not found; install binutils to run these checks" >&2
    exit 2
fi

check() {
    abi=$1; expect=$2
    file=$JNI/$abi/librestic.so

    if [ ! -f "$file" ]; then
        echo "MISSING  $abi/librestic.so"
        status=1
        return
    fi

    header=$(readelf -h "$file" 2>/dev/null || true)
    machine=$(printf '%s\n' "$header" | sed -n 's/^ *Machine: *//p')
    type=$(printf '%s\n' "$header" | sed -n 's/^ *Type: *//p')
    needed=$(readelf -d "$file" 2>/dev/null | grep -c 'NEEDED.*libc\.so' || true)
    size=$(wc -c < "$file")

    fail=""
    case "$machine" in
        *"$expect"*) ;;
        *) fail="$fail machine='$machine' expected '$expect';" ;;
    esac
    case "$type" in
        DYN*) ;;
        *) fail="$fail not a PIE (type=$type);" ;;
    esac
    [ "$needed" -ge 1 ] || fail="$fail not linked against bionic libc - DNS will not work;"
    [ "$size" -gt 5000000 ] || fail="$fail suspiciously small ($size bytes);"

    if [ -n "$fail" ]; then
        echo "BAD      $abi:$fail"
        status=1
    else
        echo "ok       $abi  $(printf '%s' "$machine")  $((size / 1048576)) MiB"
    fi
}

check arm64-v8a   AArch64
check armeabi-v7a ARM
check x86_64      X86-64

# The BSD-2 notice ships inside the APK, so the copy the app shows must still
# be the one the pinned restic carries. A tracked file cannot go missing at
# build time; it can go stale, and this is where that is caught.
LICENCE="$ROOT/restic-android/src/main/res/raw/restic_license.txt"
if ! cmp -s "$LICENCE" "$ROOT/third_party/restic/LICENSE"; then
    echo "BAD      restic_license.txt differs from third_party/restic/LICENSE"
    echo "         cp third_party/restic/LICENSE $LICENCE"
    status=1
else
    echo "ok       licence matches the pinned restic"
fi

if [ "$status" -ne 0 ]; then
    echo
    echo "verify-restic: the bundled restic binaries are not usable on Android." >&2
    exit 1
fi

echo "verify-restic: all good"
