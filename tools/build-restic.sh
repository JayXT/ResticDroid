#!/bin/sh
#
# Cross-compiles restic into restic-android/src/main/jniLibs. Called by Gradle,
# by CI and by the F-Droid buildserver; runs standalone too:
#
#     ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018 tools/build-restic.sh
#
# CGO_ENABLED=1 is not optional. Android has no /etc/resolv.conf, so Go's
# pure-Go resolver falls back to 127.0.0.1:53 and every lookup fails. Linking
# bionic gets getaddrinfo(3), which routes through netd.
#
# The output is named lib*.so because jniLibs is the only place Android mounts
# executable. They are ELF executables; the name is packaging, not a claim.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC=${RESTIC_SRC:-$ROOT/third_party/restic}
OUT=${RESTIC_OUT:-$ROOT/restic-android/src/main/jniLibs}
API=${ANDROID_API:-26}

NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ]; then
    NDK=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] || { echo "build-restic: set ANDROID_NDK_HOME" >&2; exit 1; }

HOST=linux-x86_64
case "$(uname -s)" in Darwin) HOST=darwin-x86_64 ;; esac
BIN=$NDK/toolchains/llvm/prebuilt/$HOST/bin
[ -d "$BIN" ] || { echo "build-restic: no NDK toolchain at $BIN" >&2; exit 1; }

[ -f "$SRC/go.mod" ] || {
    echo "build-restic: restic sources missing at $SRC" >&2
    echo "  run: git submodule update --init --recursive" >&2
    exit 1
}

pinned() {
    sed -n 's/^resticdroid\.resticVersion=//p' "$ROOT/gradle.properties" 2>/dev/null | head -1
}
VERSION=${RESTIC_VERSION:-}
if [ -z "$VERSION" ]; then
    VERSION=$(cd "$SRC" && git describe --tags --exact-match 2>/dev/null | sed 's/^v//' || true)
fi
[ -n "$VERSION" ] || VERSION=$(pinned)
[ -n "$VERSION" ] || VERSION=unknown
# The toolchain is part of the output: two Go releases compiling the same
# source produce different bytes. .go-version is the pin, and GOTOOLCHAIN makes
# go fetch exactly that one rather than building with whatever is installed -
# which is the difference between a reproducible build and a coincidence.
GO_PIN=$(sed -n '1p' "$ROOT/.go-version" 2>/dev/null | tr -d '[:space:]')
[ -n "$GO_PIN" ] || { echo "build-restic: .go-version is missing" >&2; exit 1; }
export GOTOOLCHAIN="go$GO_PIN"

ABIS=${RESTIC_ABIS:-"arm64-v8a armeabi-v7a x86_64"}

# What the current output would have to have been built from: everything that
# changes the bytes.
stamp() {
    printf '%s %s %s %s %s\n' \
        "$(cd "$SRC" && git rev-parse HEAD 2>/dev/null || echo no-git)" \
        "$VERSION" "$GO_PIN" "$API" "$ABIS"
}
STAMP_FILE="$OUT/.build-stamp"

have_all() {
    for abi in $ABIS; do
        [ -f "$OUT/$abi/librestic.so" ] || return 1
    done
    return 0
}

# Record what we shipped, so the app can show it and CI can assert on it.
# Written on the skip path too: the binaries can arrive from a build cache
# without this file, and the app would then fail to compile.
record_version() {
    mkdir -p "$ROOT/restic-android/src/main/res/raw"
    printf '%s\n' "$VERSION" > "$ROOT/restic-android/src/main/res/raw/restic_version.txt"
}

if [ -z "${RESTIC_FORCE:-}" ] && have_all && [ "$(cat "$STAMP_FILE" 2>/dev/null)" = "$(stamp)" ]; then
    record_version
    echo "build-restic: restic $VERSION already built; RESTIC_FORCE=1 to rebuild"
    exit 0
fi

echo "build-restic: restic $VERSION -> $OUT (minSdk $API)"

# -trimpath and an empty -buildid keep the output byte-identical across
# machines, which is what F-Droid's reproducible-build check wants.
LDFLAGS="-s -w -buildid= -X main.version=$VERSION"

build() {
    abi=$1; goarch=$2; cc=$3
    echo "  $abi"
    mkdir -p "$OUT/$abi"
    ( cd "$SRC" && env \
        GOOS=android GOARCH="$goarch" CGO_ENABLED=1 GOARM=7 \
        CC="$BIN/$cc" \
        GOFLAGS=-mod=mod \
        go build -trimpath -buildvcs=false -ldflags "$LDFLAGS" \
            -o "$OUT/$abi/librestic.so" ./cmd/restic )
}

for abi in $ABIS; do
    case "$abi" in
        arm64-v8a)   build arm64-v8a   arm64 "aarch64-linux-android$API-clang" ;;
        armeabi-v7a) build armeabi-v7a arm   "armv7a-linux-androideabi$API-clang" ;;
        x86_64)      build x86_64      amd64 "x86_64-linux-android$API-clang" ;;
        x86)         build x86         386   "i686-linux-android$API-clang" ;;
        *) echo "build-restic: unknown ABI $abi" >&2; exit 1 ;;
    esac
done

record_version
stamp > "$STAMP_FILE"

echo "build-restic: done"
ls -l "$OUT"/*/librestic.so
