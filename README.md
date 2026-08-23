# ScreenForge — 1080p60 Game Screen Recorder

[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-4285F4?logo=android&logoColor=white)](https://developer.android.com/studio/releases/platforms)
[![Latest Release](https://img.shields.io/github/v/release/michealwayne531-pixel/screen-forge-recorder-?display_name=tag&sort=semver)](https://github.com/michealwayne531-pixel/screen-forge-recorder-/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/michealwayne531-pixel/screen-forge-recorder-?style=social)](https://github.com/michealwayne531-pixel/screen-forge-recorder-/stargazers)

**ScreenForge** is a native Android game screen recorder built for sharp, reliable gameplay capture. It is tuned for high-resolution recordings, crisp UI text, stable foreground-service capture, and straightforward export to a phone’s media library.

> Capture gameplay clearly. Keep the moments that matter.

## Features

- **1080p60 High Quality Recording** with a High preset targeting 20 Mbps H.264 video bitrate.
- **Quality presets** for High 1080p60, Medium 1080p30, Low 720p30, plus an optional 1440p mode when the device display supports it.
- **Stable foreground-service capture** using Android MediaProjection so recording continues while the app is minimized.
- **Low compression and crisp text** for gameplay HUDs, menus, subtitles, and small UI details.
- **Proper video saving** with a private ScreenForge copy and a public `Movies/ScreenForge` MediaStore copy for Google Photos, Files, and other media apps.
- **Persistent recording notification** with Stop and pause/resume controls.
- **Optional microphone capture** with AAC audio configured for 48 kHz and 192 kbps when enabled.
- **In-app Gallery and player** with real recording discovery, playback, trimming, sharing, renaming, and deletion.
- **Permission-aware workflows** for MediaProjection, microphone, camera, notifications, and overlay controls.

## Download

Download the latest installable APK from the [Releases](https://github.com/michealwayne531-pixel/screen-forge-recorder-/releases) tab.

**[Download the latest ScreenForge APK](https://github.com/michealwayne531-pixel/screen-forge-recorder-/releases/latest)**

After downloading, install the APK on an Android device, open ScreenForge, grant the requested permissions, choose **High** quality in Settings, and tap **Start capture**. Approve Android’s **Start now** MediaProjection prompt. Stop the recording from the persistent notification or the floating controls.

## Build from Source

This repository contains the Expo JavaScript layer and the generated native Android Studio project. Open the `android/` directory in Android Studio, configure a local Android SDK, and run the release build:

```bash
cd android
./gradlew assembleRelease
```

The generated APK is written to:

```text
android/app/build/outputs/apk/release/app-release.apk
```

The project currently targets Android SDK 36, compiles with a minimum SDK of 24, and uses the native MediaProjection and MediaRecorder APIs for screen capture.

## Recording Workflow

1. Open ScreenForge and grant screen-capture and notification permissions.
2. Select a recording quality preset under **Settings → Video quality**.
3. Optionally enable microphone recording and grant microphone permission.
4. Tap **Start capture**, then approve Android’s system consent dialog.
5. Record gameplay while ScreenForge runs as a foreground service.
6. Stop from the notification or floating controls.
7. Find the result in the ScreenForge Gallery and in the public `Movies/ScreenForge` folder.

## Current Status

| Capability | Status |
|---|---|
| MediaProjection screen capture | Available in the native Android build |
| 1080p60 High preset | Available; device encoder/display limits may reduce output dimensions |
| H.264 surface encoding | Available; High Profile is attempted when supported |
| Microphone recording | Available when permission is granted |
| Public media-library copy | Available through MediaStore on Android 10+ |
| In-app playback, trim, share, rename, delete | Available in the native Android build |
| Internal/game audio capture | Not yet muxed into the MP4 pipeline |
| Camera facecam compositing | Not yet connected to the native encoder |
| Touch-indicator drawing | Intentionally hidden because cross-app injection is not supported by MediaProjection |

Android devices and game engines can apply their own capture and audio restrictions. The app reports unsupported capabilities instead of presenting them as active features.

## Project Structure

```text
android/                 Native Android Studio project
app/                     Expo Router screens
components/              Shared React Native UI components
lib/native-recorder.ts   Typed bridge to the native recorder module
android/app/src/main/    MediaProjection service and React Native package
assets/images/           ScreenForge branding assets
README.md                Project documentation
LICENSE                  MIT License
```

## License

ScreenForge is released under the [MIT License](LICENSE).
