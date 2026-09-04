#!/bin/sh
# The release checklist, enforced.
#
# CI runs this on every push; the release workflow runs it with the tag, before
# the signing key is ever decrypted. It fails on the mistakes that are easy to
# make when you come back to this repository after eight months.
#
#
# Cutting a release
# -----------------
#
# 1. Bump both literals in app/build.gradle.kts, together:
#
#        versionCode = 100
#        versionName = "0.1.0"
#
#    versionCode is derived from versionName as major*10000 + minor*100 + patch,
#    so 0.1.0 is 100, 0.2.0 is 200, 1.0.0 is 10000, 1.2.3 is 10203. Nothing
#    else needs editing: :app:checkVersion asserts the two agree.
#
#    Those are not the numbers that reach a phone. One APK is published per
#    ABI, each carrying versionCode * 10 + an offset (armeabi-v7a 1,
#    arm64-v8a 2, x86_64 4), so 100 ships as 1001, 1002 and 1004. Android
#    needs them distinct; F-Droid reproduces the same arithmetic from
#    VercodeOperation in fdroid/*.yml.
#
# 2. Write the release note once, then copy it to one file per published code
#    in fastlane/metadata/android/en-US/changelogs/ - F-Droid names them after
#    the versionCode, so there is one per ABI with the same text in each. This
#    script prints the copy command. 500 bytes each is the limit. Write one when
#    there is something worth reading; the GitHub release notes are generated
#    from the commits either way.
#
# 3. Commit, tag, push:
#
#        git tag -a v0.1.0 -m "ResticDroid 0.1.0" && git push origin v0.1.0
#
#    A hyphen in the tag (v0.2.0-beta.1) publishes a GitHub prerelease and is
#    ignored by F-Droid, whose UpdateCheckMode matches bare versions only.
#
# 4. Nothing else. The release workflow builds, signs and publishes; Obtainium
#    picks the new assets up on its next poll; F-Droid's checkupdates bot opens
#    its own merge request against fdroiddata.
#
#
# What still needs a merge request
# --------------------------------
#
# Nothing, as long as this stays true: the F-Droid recipe reads .go-version
# and .ndk-version out of this repository at build time instead of repeating
# them, and builds Go from source at the tag it finds there. Bumping either is
# a commit here and no edit anywhere else. The recipe lives in fdroiddata once
# the app is included, and every change to it costs a merge request, so the
# checks below fail if a refactor ever cuts that wire.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CHANGELOGS=fastlane/metadata/android/en-US/changelogs
GRADLE=$ROOT/app/build.gradle.kts
FDROID=$(ls "$ROOT"/fdroid/*.yml | head -1)

status=0
fail() { echo "check-release: $*" >&2; status=1; }

field() { sed -n "$2" "$1" | head -1; }

VERSION_CODE=$(field "$GRADLE" 's/^ *versionCode *= *\([0-9][0-9]*\).*/\1/p')
VERSION_NAME=$(field "$GRADLE" 's/^ *versionName *= *"\([^"]*\)".*/\1/p')
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] ||
    { echo "check-release: cannot read versionCode/versionName from $GRADLE" >&2; exit 1; }

# The tag is the only thing a person types, so it is the thing to check.
if [ $# -gt 0 ] && [ -n "$1" ]; then
    [ "$1" = "v$VERSION_NAME" ] ||
        fail "tag '$1' does not match versionName '$VERSION_NAME' in app/build.gradle.kts"
fi

# One published APK per ABI, and F-Droid must agree about how many.
OFFSETS=$(awk '
    /^VercodeOperation:/ { in_op = 1; next }
    /^[^ -]/             { in_op = 0 }
    in_op && /%c/        { if (match($0, /\+ *[0-9]+/)) print substr($0, RSTART + 1) + 0 }
' "$FDROID")
[ -n "$OFFSETS" ] || fail "no VercodeOperation entries in $(basename "$FDROID")"

ABIS=$(sed -n 's/.*include(\(.*\)).*/\1/p' "$GRADLE" | head -1 | tr -cd , | wc -c)
ABIS=$((ABIS + 1))
COUNT=$(printf '%s\n' "$OFFSETS" | wc -l)
[ "$COUNT" -eq "$ABIS" ] ||
    fail "$COUNT VercodeOperation entries but $ABIS ABIs in the splits block"

CODES=
for offset in $OFFSETS; do
    grep -q "to $offset" "$GRADLE" ||
        fail "VercodeOperation offset $offset is not in abiVersionOffsets"
    CODES="$CODES $((VERSION_CODE * 10 + offset))"
done

# One file per published versionCode, because that is how F-Droid names them.
# The text does not differ by ABI, so it is written once and copied.
missing=
for code in $CODES; do
    file=$CHANGELOGS/$code.txt
    if [ ! -s "$ROOT/$file" ]; then
        missing="$missing $code"
    elif [ "$(wc -c < "$ROOT/$file")" -gt 500 ]; then
        fail "$file is over F-Droid's 500-byte limit"
    fi
done

if [ -n "$missing" ]; then
    first=${CODES# }; first=${first%% *}
    fail "no changelog for versionCode(s)$missing"
    {
        echo "  versionCode $VERSION_CODE publishes as$CODES, and F-Droid names"
        echo "  a changelog after each. Write it once and copy it:"
        echo
        echo "    cd $CHANGELOGS"
        printf '    $EDITOR %s.txt' "$first"
        for code in $CODES; do
            [ "$code" = "$first" ] || printf ' && cp %s.txt %s.txt' "$first" "$code"
        done
        echo
    } >&2
fi

# The toolchain is an input to the output bytes, so there is exactly one place
# to state each half of it and everything else reads that place. These checks
# guard the reading, not the value: a recipe that goes back to naming its own
# Go or NDK builds something this repository never asked for, and says so only
# by failing F-Droid's binary comparison weeks later.
GO_PINNED=$(tr -d '[:space:]' < "$ROOT/.go-version")
NDK_PINNED=$(tr -d '[:space:]' < "$ROOT/.ndk-version" 2>/dev/null || true)
[ -n "$NDK_PINNED" ] || fail ".ndk-version is missing or empty"

FDROID_NAME=$(basename "$FDROID")
grep -q '\.go-version' "$FDROID" ||
    fail "$FDROID_NAME no longer reads .go-version"
grep -q '\.ndk-version' "$FDROID" ||
    fail "$FDROID_NAME no longer reads .ndk-version"
! grep -q '^ *ndk:' "$FDROID" ||
    fail "$FDROID_NAME pins ndk: again; it should read .ndk-version"
! grep -qE 'go[0-9][0-9.]*\.linux-amd64|golang-[0-9.]*-go=' "$FDROID" ||
    fail "$FDROID_NAME installs a fixed Go; it should read .go-version"
! grep -rq 'ndk;[0-9]' "$ROOT/.github/workflows" ||
    fail "a workflow hardcodes an NDK version; it should read .ndk-version"

DOCKER=$ROOT/tools/Dockerfile.debian13
if [ -f "$DOCKER" ]; then
    grep -q "NDK_VERSION=$NDK_PINNED" "$DOCKER" ||
        fail "$(basename "$DOCKER") does not build with NDK $NDK_PINNED"
    grep -q "GO_VERSION=$GO_PINNED" "$DOCKER" ||
        fail "$(basename "$DOCKER") does not build with Go $GO_PINNED"
fi

# Reproducible builds: F-Droid downloads the published APK named by each
# binary: and refuses to publish unless it matches what it just built. That
# makes the release workflow's asset names part of a contract held in
# fdroiddata, where changing them costs a merge request.
RELEASE=$ROOT/.github/workflows/release.yml
MISPAIRED=$(awk '
    /^ *output: / { sub(/.*\/app-/, ""); sub(/-release-unsigned\.apk.*/, ""); abi = $0 }
    /^ *https:\/\/.*\.apk$/ || /^ *binary: *https/ {
        url = $0; sub(/.*\//, "", url); sub(/\.apk.*/, "", url)
        sub(/^ResticDroid-v%v-/, "", url)
        if (abi != "" && url != abi)
            print "binary: names " url " but the block builds " abi
        abi = ""
    }
' "$FDROID")
[ -z "$MISPAIRED" ] || fail "$MISPAIRED"

for url in $(sed -n 's|^ *\(binary: *\)\?\(https://[^ ]*\.apk\) *$|\2|p' "$FDROID"); do
    name=${url##*/}
    expected=$(printf '%s' "$name" | sed 's/-v%v-/-${TAG}-/; s/-[a-z0-9_]*-\?[a-z0-9_]*\.apk$/-${abi}.apk/')
    grep -qF "$expected" "$RELEASE" ||
        fail "binary: expects '$name' but release.yml does not build that name"
done

if [ "$status" -eq 0 ]; then
    echo "check-release: $VERSION_NAME / $VERSION_CODE, publishing as $(
        for o in $OFFSETS; do printf '%s ' $((VERSION_CODE * 10 + o)); done)"
    echo "check-release: Go $GO_PINNED, NDK $NDK_PINNED, read from here by F-Droid"
fi
exit "$status"
