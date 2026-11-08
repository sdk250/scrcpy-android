#!/bin/sh
set -e
cd "$(dirname "$0")"
exec podman run --rm -v "$PWD":/work:Z -v scrcpy-android-gradle:/gradle -w /work \
  localhost/scrcpy-android-build:latest ./gradlew --no-daemon "$@"
