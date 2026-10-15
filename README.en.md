<div align="center">

[한국어](README.md) | **English**

<br/>

# SoundVisualizer

<img src="app/src/main/res/mipmap-xxxhdpi/adaptive_icon_foreground.png" width="160" alt="SoundVisualizer icon" />

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android_10+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![ONNX](https://img.shields.io/badge/ONNX-005CED?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai/)

**Real-Time Sound Visualization & AI Sound Classification Overlay for Android**

</div>

<br/>

> **SoundVisualizer** is an Android app that captures the sound playing on your phone in real time and turns its direction, intensity, and type into graphics along the edges of the screen and into vibration.
Going beyond simple waveform rendering, it uses an AI sound classifier powered by **YAMNet** and **ONNX Runtime** to recognize whether the sound playing is ambient, speech, or danger, and tells you with color and vibration.

---

## 📖 Table of Contents
- [Background & Objectives](#-background--objectives)
- [Key Features](#-key-features)
- [System & AI Architecture](#-system--ai-architecture)
- [Installation & Quick Start](#-installation--quick-start)
- [Permissions & Privacy](#-permissions--privacy)
- [Good to Know](#-good-to-know)
- [Build Guide (For Developers)](#-build-guide-for-developers)
- [License](#-license)

---

## 🎯 Background & Objectives

### Enhancing Mobile Media Accessibility for the Deaf and Hard of Hearing
Games, videos, and music on phones rely on spatial sound to draw you in, but for deaf and hard-of-hearing people that same sound becomes a barrier. Subtitles only carry dialogue and leave out **where a sound comes from, footsteps, and urgent sound effects such as gunshots.**
SoundVisualizer was created to **deliver the direction, intensity, and type of invisible sounds through the screen and vibration**, closing that information gap so that everyone can enjoy the same experience.

### Expanding Utility for All Users
For mobile gamers it works as an extra cue for where footsteps and gunshots come from, and on public transport, in a library, or late at night, it lets anyone follow along with the sound turned off.

---

## ✨ Key Features

### 1. Four Visual Modes
The same sound can be shown in four shapes. The graphics float transparently over other apps, and touches pass straight through.
- **Wave**: The whole edge of the screen pushes inward with the loudness, deeper on the side the sound comes from.
- **Pad**: Bars light up at 8 spots around the edge of the screen, showing direction most clearly.
- **Outline**: Draws only a thin outline of the wave, covering as little of the screen as possible.
- **Circle**: A ring in the center of the screen swells toward the sound.
- Settings such as size, opacity, sensitivity, speed, size lock, and glow are **saved separately for each mode**.

### 2. Real-Time AI Sound Classification (ONNX & YAMNet)
- **Three types**: Every sound is classified as `Ambient`, `Speech`, or `Danger`, and each type has its own color and on/off switch.
- **Warning cues**: Sirens, alarms, explosions, fireworks, horns, and breaking glass count as `Danger` whenever they are among the AI's top five candidates at 5% or more, even if they are not its first choice. A few cases prone to false alarms, such as when speech is louder, are excluded.
- **Danger shows at once**: The danger color appears instantly instead of fading in, and even short danger sounds that end before the AI decides are drawn at their real size.
- If the AI model can't be loaded, Home and Settings say so and every sound is shown in the ambient color.

### 3. Choose How Each Sound Counts (Sounds Tab)
- The sounds the AI knows are grouped by similarity, and you choose whether each group counts as `Ambient`, `Speech`, or `Danger`. **Advanced mode** lets you change sounds one by one.
- Search by name, filter by type or by the sounds you changed, and reset everything in one step. The AI follows your changes immediately.

### 4. Vibration Alerts
- For each sound type, choose a vibration pattern (**Off, Slow, Medium, Fast, Continuous**) and a strength from **10 to 100%**. At first only danger sounds vibrate, at Medium.
- It vibrates while that type of sound continues and stops when the sound stops. Only types shown on screen vibrate.
- **Danger alerts on the lock screen**: When a danger sound is heard while the phone is locked, an alert appears on the lock screen. While the screen is off, it briefly turns the screen on, if the app is set to keep listening with the screen off.

### 5. External Sound Mode
- Turn it on in Home to listen to **the sounds around you through the microphone** instead of the sound playing on the phone, with the same graphics and vibration. You can notice sounds outside the phone, such as fire alarms, sirens, or someone calling you.
- What it hears is used only to measure loudness and recognize the sound type. **The audio itself is never saved to a file or sent anywhere.**
- Microphone sensitivity has 16 steps, and sound picked up while the phone vibrates is handled separately so the phone doesn't mistake its own vibration for sound.

### 6. Easy to Control
- **Notification**: Stop the visualizer or change the visual mode right from the running notification.
- **Quick Settings tile**: Turn it on and off from the notification shade without opening the app.
- **Tutorial**: The first time you open the app, it shows what the app does and how to turn it on and off, with real graphics and vibration.
- **Help and reports**: Read how to use it and the FAQ, send a report as a GitHub issue or an email, and see the open source licenses, all inside the app.

### 7. 18 Languages
- Korean, English, Japanese, Chinese (Simplified and Traditional), Vietnamese, Thai, Indonesian, Spanish, Portuguese (Brazil), French, Italian, German, Russian, Polish, Turkish, Hindi, and Arabic.
- By default it follows the phone's language. You can change it with the globe button at the bottom right of the Home tab.

### 8. Light on Resources
- It is capped at 60 frames per second even on 120 Hz screens, and **Draw the graphic less often** lowers it to 30.
- When there is no sound it stops drawing and goes idle, and by default it **pauses while the screen is off**.

### 9. Developer Mode
- Shows what the AI heard (its top five sounds, how sure it was, and how long it took) at the top left of the overlay, and can record every result to a CSV file.

---

## 🧩 System & AI Architecture

* **Audio capture**: A foreground service receives the sound playing on the phone through `MediaProjection` and `AudioPlaybackCapture`, or the microphone through `AudioRecord` in External sound mode.
* **Per-channel levels**: The peak level of the left and right channels is measured in C++ (NDK) and handed to the renderer.
* **ONNX inference pipeline**: Every 250 ms, the last 0.975 seconds of audio is downmixed to mono, resampled to 16 kHz through an anti-aliasing filter, turned into the log-mel spectrogram the model was exported with, and classified by YAMNet via ONNX Runtime. The model files are checked against a pinned size and SHA-256 at load, and each step is tested against reference outputs.
* **Overlay rendering**: Graphics are drawn with a Jetpack Compose canvas in an overlay window that lets touches through. Smoothing is time-normalized to 60 FPS so it feels the same at any refresh rate, and no memory is allocated per frame.
* **Vibration**: Whether to vibrate is decided from the sound type and your settings, and the vibration follows a fixed rhythm.

The data flow, threads, and each stage are described in the [architecture document](docs/ARCHITECTURE.md) (Korean).

---

## 🚀 Installation & Quick Start

**Requirements**: Android 10 or later

1. Download the latest `SoundVisualizer-vX.Y.Z.apk` from **[GitHub Releases](https://github.com/bibibic76/SoundVisualizer/releases)** to your phone.
2. Open the APK. If you are asked to allow installing unknown apps, allow it, then install.
3. When you open the app, the **tutorial** appears first. If you skip it, you can open it again with **View tutorial** on the Home tab.
4. Tap **Start** on Home and allow the permissions. Play a game or a video, and the sound is drawn along the edges of the screen.
5. To turn it off, tap **Stop** in the app or in the notification.

The **Help** tab in the app has more on how to use it and the FAQ.

---

## 🔐 Permissions & Privacy

| Permission | What it is used for |
|---|---|
| **Display over other apps** | Showing the graphics on top of games and videos. |
| **Record audio (microphone)** | Receiving the sound playing on the phone. Unless External sound mode is on, **it does not listen to the sounds around you.** |
| **Screen recording / casting** | Android only lets apps receive the phone's own sound through this permission. **The screen itself is never captured.** External sound mode doesn't ask for it. |
| **Notifications** (Android 13 and later) | The running notification with its Stop button, and the notice when it turns off without you stopping it. The app works without it. |

Sound is processed only on the device and is never saved to a file or sent anywhere.

---

## 📌 Good to Know

- **It tells left from right, but not front from back.** Phone sound has two channels, so graphics at the top and bottom of the screen show sound heard equally on both sides and a sense of space.
- **Only media and game sound is captured.** Calls, notification sounds, and alarms are not, and apps that block audio capture (such as protected video) are not drawn. In that case Home and the running notification tell you that no sound is reaching the visualizer.
- **The phone's own sound follows the media volume.** At a very low volume the graphics and vibration barely react.
- **Starting screen recording, sharing, or casting turns it off.** Android lets only one app use that feature at a time. If it turns off without you stopping it, you are told with vibration and a notification.
- **AI classification can be wrong.** Don't rely on this app as your only safeguard when safety is at stake.

---

## 🛠 Build Guide (For Developers)

1. **Prerequisites**: The latest Android Studio, JDK 17, and the Android SDK (compileSdk 37). The matching NDK and CMake are downloaded during the build.
2. **Clone the repository**:
   ```bash
   git clone https://github.com/bibibic76/SoundVisualizer.git
   ```
3. **Build and test**:
   ```bash
   ./gradlew assembleDebug        # APKs for phones (arm64-v8a) and emulators (x86_64) in app/build/outputs/apk/debug/
   ./gradlew testDebugUnitTest    # Unit tests (no device needed)
   ./gradlew lintDebug            # Lint
   ```
   Instrumented tests (`connectedDebugAndroidTest`) uninstall the app when they finish and erase its settings, so run them on an emulator, not on a personal phone.

**CI**: On every push, GitHub Actions builds, runs the unit tests and Lint, and uploads a phone APK under **Artifacts**. Pull requests to `develop` and `main` also run instrumented tests on an emulator. Pushing a version tag such as `v1.0.0` on `main` builds a release APK and attaches it to Releases.

| Location | Role |
|---|---|
| `AudioCaptureService` | Foreground service that receives sound (the phone's own sound, or the microphone in External sound mode) |
| `AudioEngine`, `cpp/native-lib.cpp` | Left and right channel levels |
| `ai/` | Preprocessing → YAMNet → warning cues and post-processing |
| `OverlayService`, `VisualizerEngine` | The overlay window and the four visual modes |
| `feedback/` | Vibration alerts |
| `ClassifyTab`, `SoundCatalog`, `SoundGroups` | The Sounds tab |
| `HomeTab`, `SettingsTab`, `help/`, `tutorial/`, `language/`, `tile/` | App screens, help, tutorial, languages, and the Quick Settings tile |

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to contribute and the [architecture document](docs/ARCHITECTURE.md) (Korean) for the full structure.

---

## 📄 License

SoundVisualizer is distributed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for more information.

The sound groups (`app/src/main/assets/sound_groups.tsv`) and sound names (`app/src/main/res/values*/sound_names.xml`) used in the Sounds tab are adaptations of the AudioSet ontology (CC BY-SA 4.0) and are distributed under **CC BY-SA 4.0**. Copyright and license notices for third-party components (ONNX Runtime, the YAMNet model, AndroidX, and others) are in the [NOTICE](NOTICE) file.

<br/>
<div align="center">
  <b>Built for Accessibility.</b>
</div>
