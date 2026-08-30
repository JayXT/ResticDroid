#!/bin/sh
#
#     tools/container-build.sh                    # check + assembleRelease
#     tools/container-build.sh ./gradlew test     # anything else
#
# Caches live in .container/ so they can be deleted with rm -rf. The image and
# the daemon's build cache cannot be scoped that way under Docker; ENGINE=podman
# takes --root and keeps those inside the project too.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CACHE=${RESTICDROID_CACHE:-$ROOT/.container}
IMAGE=${IMAGE:-resticdroid-build}

if [ -z "${ENGINE:-}" ]; then
    if command -v docker > /dev/null 2>&1; then
        ENGINE=docker
    elif command -v podman > /dev/null 2>&1; then
        ENGINE=podman
    else
        echo "container-build: neither docker nor podman is installed. On Debian 13:" >&2
        echo "    sudo apt install podman        # keeps everything in .container/" >&2
        echo "    sudo apt install docker.io     # image goes to /var/lib/docker" >&2
        exit 1
    fi
fi

mkdir -p "$CACHE/gradle" "$CACHE/go" "$CACHE/home"

USER_ARGS="--user $(id -u):$(id -g)"
STORE_ARGS=""

TTY_ARGS=""
[ -t 0 ] && TTY_ARGS="-it"

if [ "$ENGINE" = "podman" ]; then
    # podman keeps images wherever --root points, so the whole thing - image
    # included - stays inside the project.
    mkdir -p "$CACHE/storage"
    STORE_ARGS="--root $CACHE/storage"
    USER_ARGS=""
fi

command -v "$ENGINE" > /dev/null 2>&1 || {
    echo "container-build: $ENGINE is not installed." >&2
    exit 1
}

if ! "$ENGINE" $STORE_ARGS info > /dev/null 2>&1; then
    echo "container-build: cannot talk to $ENGINE." >&2
    if [ "$ENGINE" = "docker" ]; then
        echo "  Usually this means your user is not in the docker group:" >&2
        echo "    sudo usermod -aG docker \"$USER\" && newgrp docker" >&2
        echo "  Or the daemon is not running: sudo systemctl start docker" >&2
    fi
    exit 1
fi

if ! "$ENGINE" $STORE_ARGS image inspect "$IMAGE" > /dev/null 2>&1; then
    echo "container-build: building the $IMAGE image (once, a few minutes)"
    "$ENGINE" $STORE_ARGS build -f "$ROOT/tools/Dockerfile.debian13" -t "$IMAGE" "$ROOT/tools"
fi

echo "container-build: caches in $CACHE"

# shellcheck disable=SC2086
exec "$ENGINE" $STORE_ARGS run --rm \
    $TTY_ARGS \
    $USER_ARGS \
    -v "$ROOT:/src" \
    -v "$CACHE/gradle:/cache/gradle" \
    -v "$CACHE/go:/cache/go" \
    -v "$CACHE/home:/cache/home" \
    -w /src \
    "$IMAGE" \
    "$@"
