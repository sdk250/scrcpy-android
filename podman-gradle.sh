#!/bin/sh
set -e
cd "$(dirname "$0")"
exec podman run --rm -v "$PWD":/work:Z -v scrcpy-android-gradle:/gradle -w /work -e ANDROID_USER_HOME=/gradle/android-user-home \
  localhost/scrcpy-android-build:latest ./gradlew --no-daemon "$@"
