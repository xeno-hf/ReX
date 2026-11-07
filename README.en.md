# ReX

[繁體中文](README.md) | English

An Android/iOS real-time dashboard and data recorder for aRacer RC X series ECUs.

**Unofficial** third-party tool, not affiliated with aRacer. It only monitors and records — no tuning-map, password, or firmware changes.

<img src="docs/screenshots/landscape.webp" alt="Advanced landscape dashboard" width="960">

| Advanced | Cruise | Monitor |
| :---: | :---: | :---: |
| <img src="docs/screenshots/advanced.webp" alt="Advanced" width="250"> | <img src="docs/screenshots/cruise.webp" alt="Cruise" width="250"> | <img src="docs/screenshots/monitor.webp" alt="Monitor" width="250"> |

<details>
<summary>More screenshots</summary>

| Multi-channel scope | Channel list |
| :---: | :---: |
| <img src="docs/screenshots/scope.webp" alt="Multi-channel scope" width="300"> | <img src="docs/screenshots/channels.webp" alt="Channel list" width="300"> |

| Dark theme | Light theme |
| :---: | :---: |
| <img src="docs/screenshots/red.webp" alt="Dark theme" width="300"> | <img src="docs/screenshots/light.webp" alt="Light theme" width="300"> |

| Recordings | Settings |
| :---: | :---: |
| <img src="docs/screenshots/recordings.webp" alt="Recordings" width="300"> | <img src="docs/screenshots/settings.webp" alt="Settings" width="300"> |

</details>

## Features

- Three dashboard layouts, six-channel scope, channel list and trouble codes
- `.loga` recording (XTune-compatible) with offline playback
- Dashboard controls MAP / AT / Pit / LC / TC / EB (depending on ECU model and licensing)
- Automatic ECU discovery, reconnection, phone GPS speed
- English, Traditional Chinese, Simplified Chinese, Japanese

## Get started

1. Download the APK from [Releases](https://github.com/xeno-hf/ReX/releases) (build iOS yourself).
2. Import the **ControlFile XML** for your ECU. ReX ships no aRacer files and does not pull them from XTune.
3. Join the ECU Wi-Fi and open ReX, or import a `.loga` for offline playback.

Requires Android 10+ / iOS 15+. Only Mini X has been tested on a real vehicle so far.

## Build

Requires JDK 17+ and the Android SDK (Platform 35).

```sh
./gradlew :app:assembleRelease
```

The default release build uses a debug key unless you configure your own signing.

iOS requires macOS and Xcode and produces an unsigned IPA; sign it with your own Apple ID:

```sh
./rx-ios/app/build.sh device
```

## Safety

- Calibration writes, password changes, flashing and firmware operations are blocked before sending (`rx-session/src/commonMain/kotlin/rx/session/WriteGate.kt`).
- Connecting configures the ECU's broadcast channels; XTune restores its own on next connect.
- Use at your own risk. Keep your eyes on the road while riding.

## License

GPL-3.0; see [LICENSE](LICENSE). The Oxanium font is under the SIL Open Font License. See [NOTICE.md](NOTICE.md) for trademark notices.
