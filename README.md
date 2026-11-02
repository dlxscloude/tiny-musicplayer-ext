# Tiny Music Player Extended (`tiny-musicplayer-ext`)

An ultra-lightweight, completely headless, UI-less music player for Android (Target API 31 / Min API 26).
Everything is controlled exclusively via the system notification shade and Android Media3 session controls.

## Architecture

- **Completely Headless**: Zero user-facing activities, screens, fragments, or heavy UI frameworks (no Jetpack Compose or layout inflation overhead).
- **Intent Launcher (`MediaLauncherActivity`)**: Zero-second translucent activity that intercepts `ACTION_VIEW` audio intents from external file managers, starts `TinyMediaService` as a foreground service, and immediately finishes.
- **Media Engine (`TinyMediaService`)**: Powered by Jetpack Media3 (ExoPlayer). Automatically scans the parent directory of the opened file using `DocumentFile` and MediaStore, sorts all supported audio files (`.mp3`, `.flac`, `.m4a`, `.ogg`, `.opus`) alphabetically, builds a dynamic playlist queue, and begins playback at the selected track index.
- **Smart Notification Actions**:
  - **Loop Toggle**: Cycles through `REPEAT_MODE_OFF`, `REPEAT_MODE_ALL`, and `REPEAT_MODE_ONE`.
  - **Shuffle Toggle**: Dynamically toggles ExoPlayer's `shuffleModeEnabled` state.
  - **Close App**: Stops playback, clears the queue, dismisses the notification, and stops the foreground service.
- **Binary Footprint Optimization**: R8 full minification (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) producing a tiny APK binary (~1.7 MB).

## Supported Formats

- `.mp3`
- `.flac`
- `.m4a`
- `.ogg`
- `.opus`

## Build & Install

```bash
# Build Release APK
./gradlew assembleRelease

# Install via ADB
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Icon Attribution

<a href="https://www.flaticon.com/free-icons/treble-clef" title="treble clef icons">Treble clef icons created by Ricardo Ruiz - Flaticon</a>
