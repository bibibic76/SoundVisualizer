# 🎧 SoundVisualizer

[한국어](README.md) | **English**

**An Android app that lets you see the sound playing on your phone.**

It draws where the sound on your phone is coming from, how loud it is and what kind of sound it is, live along the edges of your screen, whether it comes from a game, a video or music. The graphics float transparently over other apps and touches pass straight through, so you can keep playing or watching as usual while you see the sound.

---

## 🙋 Who it's for

- **Deaf and hard-of-hearing people**: notice sound effects, speech and danger sounds like gunshots in videos and games by sight.
- **Mobile gamers**: use it as an extra cue for which side footsteps or gunshots are coming from.
- **Anyone who can't turn the sound on**: follow what's happening on public transport, in a library or late at night with the sound off.

---

## 🔍 How it works

1. **It captures the sound playing inside your phone.** It doesn't listen to the room through the microphone. It receives the sound that apps and games play.
2. **It compares the loudness of the left and right channels** to work out direction and strength.
3. **AI decides what kind of sound it is every 0.25 seconds**: ambient, speech or danger.
4. **It draws the result along the edges of the screen.** The side the sound comes from reacts more strongly, and the color changes with the sound type.

---

## ✨ Features

### 🎨 Four visual modes

You can see the same sound in four shapes and switch between them any time in Settings.

| Mode | What it looks like | Good for |
|---|---|---|
| 🌊 **Wave** | The whole edge of the screen pushes inward with the loudness. The side the sound comes from pushes in deeper. | Seeing direction and loudness at a glance |
| 🎯 **Pad** | Bars light up at 8 spots around the edge of the screen. | Reading direction as clearly as possible |
| 🚨 **Outline** | Draws only a thin outline of the wave. | Covering as little of your game as possible |
| ⭕ **Circle** | A ring in the center of the screen swells toward the sound. | Content where you keep your eyes on the center |

### 🧠 Sound types (AI)

- Sorts what you hear into **ambient, speech and danger** sounds.
- Gunshots are double-checked by a dedicated model so they aren't missed.
- **Pick a color for each type.** Choose any color in the color picker, or tap a common color.
- **Show or hide each type.** For example, hide speech and show only danger sounds.
- When a danger sound is detected, the color changes instantly instead of fading, so it stands out right away.
- Even with only danger sounds shown, short danger sounds like gunshots that end before the AI decides are still drawn at their real size.

### 📳 Vibration alerts

Vibration lets you know a sound happened even when you're not looking at the screen.

- **Turn vibration on or off for each sound type.** At first only danger sounds vibrate.
- **Pick a strength (Light, Medium, Strong) and a pattern (Once, Twice, Long, Repeat) for each type.** Set them differently per type and you can tell sounds apart by touch alone.
- Tap an option in Settings to feel it right away.
- **Only types shown on screen vibrate.**
- So rapid gunfire doesn't buzz nonstop, the same type won't vibrate again within 2 seconds. "Repeat" vibrates every 1.5 seconds while the sound continues.

### ⚙️ Settings for each mode

All settings are **saved separately for each mode**, so you can make Wave mode big and Outline mode faint, for example.

| Setting | What it does |
|---|---|
| **Size** | How far the graphics reach. At 100 they get close to the center of the screen. |
| **Opacity** | Higher values look bolder; lower values look fainter and more transparent. |
| **Sensitivity** | How quickly the graphics react when loudness changes. |
| **Speed** | How fast the graphics move to a new position when the sound changes direction. |
| **Lock size** | Keeps the graphics at a fixed size and shows loudness only as opacity. Turn it on to use **Fixed size** and **Max opacity**. |
| **Radius** | (Circle mode only) The size of the ring in the center. |
| **Glow** | Adds a soft glow around the graphics. Turn it on to adjust **Glow strength**. |
| **Spatial ripple** | Adds a delay so sound seems to spread from front to back. |

Settings that don't apply to the current mode are dimmed so they don't get in the way.

### 🌐 Languages

The app follows your phone's language and is available in the languages below. On a phone set to any other language, it appears in English.

English · 한국어 · 日本語 · 简体中文 · 繁體中文 · Español · Português (Brasil) · Français · Deutsch · Русский · Italiano · Tiếng Việt · ไทย · Bahasa Indonesia · Türkçe · Polski · हिन्दी · العربية

To use a different language from your phone, tap **Language** at the top of the **Settings** tab. **System default** follows your phone's language again. On Android 13 and later, you can also change it under **App languages** in your phone's settings.

### 🔋 Lightweight

- Built to run smoothly over games. Even on 120Hz screens it's capped at 60 frames per second to save battery and reduce heat.
- When there's no sound, it stops drawing and goes idle.
- A notification is shown while it's running, and its **Stop** button turns it off right away.

---

## 📲 Install

**Requirements**: Android 10 or later

1. Download `SoundVisualizer-<version>.apk` for the version you want from [Releases](https://github.com/bibibic76/SoundVisualizer/releases) onto your phone. If someone sent you the APK file directly, use that file.
2. Open the APK file on your phone.
3. If you're asked to allow installing unknown apps, allow it.
4. Tap Install.

---

## ▶️ How to use

You can also find how to use it, why permissions are needed and the FAQ in the app's **Help** tab.

1. Open the app and tap **Start**.
2. Allow the permissions below. Some are asked only once, and some are asked every time you turn it on because of Android's rules.

   | Permission | Why it's needed |
   |---|---|
   | **Display over other apps** | To show graphics on top of games and videos. |
   | **Microphone (audio recording)** | To capture sound playing on your phone. The permission is called microphone, but **sounds around you are not recorded.** |
   | **Screen recording / casting** | Android only lets apps capture your phone's sound through this permission. **Your screen itself is not captured.** |
   | **Notifications** (Android 13+) | To show the running notification and its Stop button. |

   The app explains why it needs the microphone before asking. If you've denied it several times and Android stops asking, tap **Open settings** when the app prompts you, and allow the microphone under Permissions in the app info.

3. Go to your home screen and open a game or video. Sounds are drawn along the edges of the screen.
4. To turn it off, tap **Stop** in the app or **Stop** in the notification.

### Turn it on and off from the notification shade

You can turn it on and off from a Quick Settings button in the notification shade without switching apps.

1. Tap **Add to Quick Settings** on the app's Home tab and allow it. (On Android 12 and earlier, pull down the notification shade, tap Edit (pencil) and drag **Sound visualizer** in.)
2. While playing a game or watching a video, pull down the notification shade and tap **Sound visualizer** to turn it on. Android shows the screen recording consent every time you turn it on.
3. Tap it again to turn it off. Long-press it to open the app's settings.

The first time you turn it on, the microphone and notification permissions are asked right there. If **Display over other apps** isn't allowed, the app opens to guide you.

---

## ⚠️ Good to know

- **It can tell left from right, but not front from back.** Phone audio has two channels, left and right, and direction comes from the difference between them. Graphics at the top and bottom of the screen show sounds heard equally on both sides and a sense of space. They don't pick out sounds actually coming from behind you.
- **Sounds that are identical on the left and right (mono) have no direction.** They're shown only toward the front.
- **Not every sound is captured.** Only media and game sounds are captured. Phone calls, notification sounds and alarms are not. Some apps (such as copy-protected video apps) block audio capture and can't be visualized.
- **AI classification is for reference only.** It can get the sound type wrong. Don't rely on this app as your only safeguard when safety is at stake.

---

## ❓ FAQ

**I don't see any graphics.**
Check whether the app you're playing blocks audio capture. Also make sure **Opacity** isn't too low in Settings, and that showing the current sound type isn't turned off.

**It doesn't vibrate.**
In Settings, under Sound types, make sure both **Show** and **Vibrate** are on for that sound type. If vibration or touch vibration is turned off in your phone's settings, it may not vibrate.

**A new version won't install.**
An APK built on a different computer has a different signature and can't be installed over the existing app. Uninstall the existing app first, then install. This resets your settings.

**Does it use a lot of battery?**
It only draws while there's sound and goes idle when it's quiet. Because it keeps capturing sound, it does use some battery while running. We recommend turning it off when you're not using it.

**How do I change the app language?**
Tap **Language** at the top of the **Settings** tab and pick one. **System default** follows your phone's language. On Android 13 and later, you can also change it under **App languages** in your phone's settings.

---

## 🛠️ For developers

Build, test, CI and code structure docs are in Korean: see the "개발자용" (For developers) section of [README.md](README.md) and the [architecture doc](docs/ARCHITECTURE.md). How to add strings and translations is in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## 📄 License

This project is distributed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.

Copyright and license notices for bundled third-party components (ONNX Runtime, the YAMNet model, AndroidX and more) are in the [NOTICE](NOTICE) file.
