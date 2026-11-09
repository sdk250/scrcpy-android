# scrcpy-android

View and control one Android phone from another, like [scrcpy](https://github.com/Genymobile/scrcpy) but without a computer. The app has a built-in ADB client and runs the official scrcpy-server (v5.0.1) on the target device.

## Features

- Device list like `adb devices`: USB (OTG) and `adb connect` (TCP)
- Fullscreen mirroring with hardware decoding (H.264 / H.265), aspect ratio kept (no stretching), follows the target's rotation
- Multi-touch, key bar (back / home / recents / volume / power), text input
- Your own ADB key: import (e.g. `~/.android/adbkey`), export, regenerate
- English / 中文 UI

## Requirements

- Controller: Android 10+
- Target: USB debugging enabled. For network use, run `adb tcpip 5555` once. The Android 11+ wireless debugging port (TLS / pairing) is not supported.

## Build

Uses podman, so no local JDK or Android SDK is needed:

```sh
podman build -t scrcpy-android-build container/
./podman-gradle.sh :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./podman-gradle.sh :app:testDebugUnitTest
```

## License

The bundled scrcpy-server is licensed under Apache 2.0 by Genymobile (see `app/src/main/assets/scrcpy-NOTICE.txt`).
