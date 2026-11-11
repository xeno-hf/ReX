# ReX

繁體中文 | [English](README.en.md)

aRacer RC X 系列 ECU 的 Android／iOS 即時儀表與資料記錄 App。

**非官方**第三方工具，與 aRacer 無關。提供監控、記錄與儀表控制，不改調校表、密碼或韌體。

<img src="docs/screenshots/landscape.webp" alt="橫向進階儀表" width="960">

| 進階 | 巡航 | 監控 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/advanced.webp" alt="進階" width="250"> | <img src="docs/screenshots/cruise.webp" alt="巡航" width="250"> | <img src="docs/screenshots/monitor.webp" alt="監控" width="250"> |

<details>
<summary>更多畫面</summary>

| 多通道示波 | 通道列表 |
| :---: | :---: |
| <img src="docs/screenshots/scope.webp" alt="多通道示波" width="300"> | <img src="docs/screenshots/channels.webp" alt="通道列表" width="300"> |

| 暗色主題 | 淺色主題 |
| :---: | :---: |
| <img src="docs/screenshots/red.webp" alt="暗色主題" width="300"> | <img src="docs/screenshots/light.webp" alt="淺色主題" width="300"> |

| 錄製紀錄 | 設定 |
| :---: | :---: |
| <img src="docs/screenshots/recordings.webp" alt="錄製紀錄" width="300"> | <img src="docs/screenshots/settings.webp" alt="設定" width="300"> |

</details>

## 功能

- 三種儀表版面、最多 6 通道示波、通道列表與故障碼
- 錄製 `.loga`（與 XTune 相容），可離線重播
- 儀表控制 MAP／AT／Pit／LC／TC／EB（依 ECU 型號與授權）
- 自動尋找 ECU、斷線重連、手機 GPS 車速
- 介面：English、繁體中文、简体中文、日本語

## 開始使用

1. 從 [Releases](https://github.com/xeno-hf/ReX/releases/latest) 下載 Android APK 或 iOS 未簽章 IPA。iOS 請先用 AltStore、Sideloadly 等工具，以自己的 Apple ID 簽署安裝；不需要自行編譯，IPA 不能直接點開安裝。
2. 連上 ECU 的 Wi-Fi，開啟 ReX 按連線。也可以匯入 `.loga` 離線重播。

ReX 內建各型號常用的監控通道，連線時會依 ECU 自動切換，不需要匯入任何檔案。想看完整通道的話，可以在設定裡另外匯入自己的 ControlFile XML。

需求：Android 10+／iOS 15+（iPhone／iPad）。目前只有 Android 版搭配 Mini X 實車驗證過；iOS 實車連線與其他型號尚未確認。

## 建置

需要 JDK 17+ 與 Android SDK（Platform 35）。

```sh
./gradlew :app:assembleRelease
```

未設定自己的簽章時，release 版會用 debug key 簽。

iOS 需要 macOS 與 Xcode，產出未簽章 IPA，請用自己的 Apple ID 簽章：

```sh
./rx-ios/app/build.sh device
```

## 安全

- 調校寫入、密碼變更、燒錄與韌體操作在送出前就會被擋下（`rx-session/src/commonMain/kotlin/rx/session/WriteGate.kt`）。
- 連線時會設定 ECU 的廣播通道；XTune 下次連線會自行寫回。
- 風險自負。騎車時請專心看路。

## 授權

GPL-3.0，見 [LICENSE](LICENSE)。字型 Oxanium 採 SIL Open Font License。商標聲明見 [NOTICE.md](NOTICE.md)。
