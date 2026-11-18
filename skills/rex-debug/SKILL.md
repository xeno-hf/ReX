---
name: rex-debug
description: Use when a user's ReX (aRacer ECU phone app) misbehaves - it will not connect, data looks wrong, a recording is missing, or live tuning will not start or write. Collect facts, interpret them, and give fix steps.
---

# Debugging ReX

ReX is an unofficial Android/iOS app for aRacer RC X series ECUs: live dashboard, recording (`.loga`, XTune-compatible) and live tuning over the ECU's Wi-Fi.
It has no command line. Facts come from the user's screenshots, the app's Diagnostics page, recordings, and on Android optionally `adb`.
Recordings are analysed with `xbrex`, the command-line tool installed with the desktop app [XenoBrex](https://github.com/xeno-hf/XenoBrex).

## Rules first

1. **Never ask the user to write to the ECU without their explicit consent.** Dashboard controls (AT, TC, MAP), "Start tuning", table edits, function-page commands and Restore all write to the ECU. Reading, monitoring and recording do not.
2. **Do not tune or test while the bike is being ridden.** Tell the user to stop somewhere safe first.
3. **Recordings are the user's data.** A `.loga` holds the phone's GPS columns, i.e. their route. Read it to investigate; do not upload it anywhere, and remind the user before they post it publicly.
4. **Do not try to unlock what aRacer has not enabled.** A feature that is not enabled for an ECU model or licence (abort `0x06010004`) is not a bug. ReX and XenoBrex do not change passwords, flash firmware or touch activation.
5. Report results as they are: if nothing was found, say so; quote error text verbatim.

## Step 1: what the user has

Ask for:

- ReX version (More → Settings, under About) and whether it is Android or iOS
- ECU model (Mini X, Super X, Mini XX, Super XX) and how the phone joins the ECU (ECU Wi-Fi, or the ECU on the phone's hotspot)
- A **screenshot** (not a photo of the screen) of the screen with the problem
- For connection or data problems: a screenshot of More → Diagnostics while connected

What iOS can and cannot do matters for many questions:

| | Android | iOS |
|---|---|---|
| Dashboard, monitor, recording, dashboard controls | yes | yes |
| Pair / sync with XenoBrex | yes | not yet |
| Live tuning (needs a tune pack from XenoBrex) | yes | not yet |

## Step 2: by symptom

### Will not connect

The phone must be on the ECU's Wi-Fi (the same one XTune uses). Default address `192.168.88.88`; a blank password means the factory default `888888`.

| You see | Meaning | Fix |
|---|---|---|
| "Bike not found…" | The phone is not on the ECU's network, or the address is different | Join the ECU Wi-Fi; try "Find automatically" |
| "Not connected to WiFi" | Network is set to "WiFi only" and the phone has no Wi-Fi | Join the ECU Wi-Fi; if the ECU is on the phone's own hotspot, set Connection → Advanced settings → Network to "Any" |
| Connects, then XTune drops (or the other way round) | The ECU accepts one connection at a time | Close XTune while using ReX |
| Login fails | Wrong ECU password | Enter the password set in XTune |
| No reply right after key-on, or during idle stop | The ECU is busy or asleep | Turn the key off and on, wait a few seconds, retry |

### Connected, but values look wrong or slow

Read More → Diagnostics:

| Line | Look for |
|---|---|
| `ECU` | The model ReX detected. Wrong model = the heartbeat was missed; reconnect |
| `Rounds ok / partial … Hz` | Many partial rounds or a low rate = weak Wi-Fi or too many channels. Use a channel group with fewer channels |
| `Rearm / Reconnect / No ACK` | Rising numbers = the link keeps dropping |
| `Write gate refused` | Non-zero = ReX blocked a write it was not allowed to send (expected outside tuning; report it if it happens during tuning) |

A channel that stays at 0 or a fixed value may simply not be used on that ECU model or wiring. Compare with the same channel in XTune before calling it a ReX bug.
ReX ships common channels per model; to see every channel, import the user's own ControlFile XML in Settings.

### "No tune pack for this ECU…"

Live tuning needs a tune pack (the table definitions) from XenoBrex; ReX ships no definitions or keys. Android only.

1. Computer and phone on the same normal Wi-Fi (not the ECU's).
2. XenoBrex: Settings → Phone sync (ReX) → turn on "Sync server", press "Pair a phone…".
3. ReX: More → Sync to computer → enter the address and code shown → Pair → "Sync now". It should say "Tune pack updated."
4. The pack is for the ECU XenoBrex connected to last, so XenoBrex must have connected to this ECU at least once.
5. Join the ECU Wi-Fi again and open the Tune page.

After that the pack stays on the phone; the computer is needed again only when the ECU's version changes. If Windows asks about the firewall, allow XenoBrex on private networks.

Sync errors: "Wrong pairing code" (type it again, it expires), "The computer isn't pairing" (press Pair a phone first), "a different computer" or "doesn't know this phone any more" (Unpair, then pair again).

### Tuning will not start

| You see | Meaning |
|---|---|
| "ECU is … but the tune pack is V… C…" | The pack is for another ECU version. Connect XenoBrex to this ECU, then sync again |
| "The tune pack does not match its own template" | Damaged pack; sync again |
| "The backup of the ECU could not be saved" | Tuning refuses to start without a verified backup. Usually the full read-back was interrupted; reconnect and retry with a steady Wi-Fi |
| "The connection dropped, so tuning stopped" | Expected safety behaviour. Start tuning again; it reads the ECU again |
| "No tables can be edited on this ECU" | Nothing in the pack is writable for this model or licence |

### A table is refused while tuning

The ECU replied with an abort code:

| abort | Meaning |
|---|---|
| 0x06010004 | The feature is not enabled on this ECU (aRacer licensing). Not fixable in ReX |
| 0x06010003 | Login required; reconnect |
| 0x06010002 | Read-only |
| 0x06020000 | The table does not exist on this ECU version |
| 0x06090011 | Different number of rows on this ECU version |

Refused tables are not sent again in that session. Edits made live go to the ECU's RAM; the ECU usually keeps them at key-off, but not always. To be sure, save the whole calibration once with XenoBrex or the official app.

### Recording problems

- Records tab, each recording: "Share loga" (for XTune), "Pack .xbl" (ride + vehicle + every write ReX sent, for XenoBrex), "Share all".
- "Not closed properly, data kept": the recording was not stopped normally (the app was closed or killed); the data up to that point is kept.
- To replay a recording: disconnect, then Connect → Demo mode.

## Step 3: look inside a recording

Ask the user to share the `.xbl` (Records → Pack .xbl). With XenoBrex installed:

```
xbrex ride info <ride.xbl> [--lookup]        vehicle, times, writes (--lookup: vehicle name from the official list)
xbrex ride writes <ride.xbl>                 every write with the ECU's answer (ack / abort / no reply)
xbrex ride export <ride.xbl> --loga [out]    the loga, to open in XTune
```

An `.xbl` is a ZIP. A recording folder on the phone holds `meta.json` (format, start/stop time, frame and round counts, channel table), `events.jsonl` (connection events), `frames.rxraw` (raw frames) and `decoded.loga`.

### Android with adb (optional)

Only if the user agrees and has USB debugging on:

```
adb shell ls /sdcard/Android/data/io.github.xeno_hf.rex/files/sessions
adb pull /sdcard/Android/data/io.github.xeno_hf.rex/files/sessions/<recording> .
adb logcat -d | grep -i -E "AndroidRuntime|rex"
```

Tune packs and ECU backups are in the app's private storage and cannot be read over adb on a release build; use the app (Tune page → Restore) for those.
Do not use adb to tap through the app or change settings on the user's behalf unless they ask you to.

## Reporting to the developers

- Questions, ideas: [Discussions](https://github.com/xeno-hf/ReX/discussions)
- A confirmed bug: an [Issue](https://github.com/xeno-hf/ReX/issues) with the ReX version, phone and OS version, ECU model, what they did, what they expected, what they saw, a Diagnostics screenshot, and the `.xbl` or `.loga` if the user agrees to share it.
