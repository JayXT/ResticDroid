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
# The bot only fills in the version and the tag; everything else in a build
# block is carried forward. So bumping .go-version or the NDK means editing
# fdroid/*.yml too - and after inclusion that file lives in fdroiddata, which
# takes a merge request of its own. This script fails when the pins drift, in
# the commit that moves them, rather than at F-Droid weeks later.
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

# Pins F-Droid cannot read from this repository, so they are written twice and
# have to be kept in step by hand.
one() { printf '%s\n' "$1" | sort -u | tr '\n' ' ' | sed 's/ $//'; }

GO_PINNED=$(tr -d '[:space:]' < "$ROOT/.go-version")
GO_FDROID=$(one "$(sed -n 's|.*/go\([0-9][0-9.]*\)\.linux-amd64\.tar\.gz.*|\1|p' "$FDROID")")
[ "$GO_FDROID" = "$GO_PINNED" ] ||
    fail "$(basename "$FDROID") installs Go '$GO_FDROID' but .go-version says '$GO_PINNED'"

NDK_FDROID=$(one "$(sed -n 's/^ *ndk: *\([0-9.]*\).*/\1/p' "$FDROID")")
NDK_ELSEWHERE=$(one "$(grep -rho 'ndk;[0-9.]*' "$ROOT/.github/workflows" | cut -d';' -f2)")
[ "$NDK_FDROID" = "$NDK_ELSEWHERE" ] ||
    fail "$(basename "$FDROID") uses NDK '$NDK_FDROID' but the workflows install '$NDK_ELSEWHERE'"

if [ "$status" -eq 0 ]; then
    echo "check-release: $VERSION_NAME / $VERSION_CODE, publishing as $(
        for o in $OFFSETS; do printf '%s ' $((VERSION_CODE * 10 + o)); done)"
    echo "check-release: Go $GO_PINNED, NDK $NDK_FDROID, consistent with F-Droid"
fi
exit "$status"
