# LeanBack

**Lean back in your gaming chair and control your PC cursor & keyboard straight from your phone over Wi-Fi.**

No unsigned `.exe` downloads. Run one command on your PC, open the pure-black app on your Android phone, and type or move the mouse without getting up.

```text
┌─────────────────────────────┐
│ ●                         ⚙ │  ← Pure AMOLED black touchpad
│                             │    (1-finger move, 2-finger scroll)
│                             │
├──────────────┬──────────────┤
│     Left     │    Right     │  ← Optional Click Buttons mode
├──┬───┬────┬──┴─┬───┬─┬─┬─┬──┤    (prevents accidental taps!)
│Es│Tab│Ctrl│Alt │Win│←│↓│↑│→│⌨│  ← Quick PC keys & keyboard toggle
├──┴───┴────┴────┴───┴─┴─┴─┴─┴┤
│   [ Q  W  E  R  T  Y ... ]  │  ← Your phone keyboard stays active
└─────────────────────────────┘
```

---

## Why LeanBack?

- **Zero `.exe` SmartScreen drama** — PC server runs straight from Command Prompt / PowerShell via Node.js (`npx leanback`).
- **Pure function, zero clutter** — No navbar or top bar. Just a black touchpad on top and your familiar phone keyboard (with swipe typing, autocorrect, and emojis) always ready at the bottom.
- **Two Click Modes** — Tap directly on the touchpad *(default)*, or switch to **Click buttons** in `⚙` Settings for `Left` / `Right` buttons (side-by-side or `1:1` square `Right` over `Left` on the bottom-left).
- **Send photos straight to PC** — Tap the subtle image icon in the top-right corner to pick a photo from your phone; it saves to `Downloads\LeanBack` and copies straight to your PC clipboard ready for `Ctrl+V`.
- **Safe LAN pairing** — Auto-discovers your PC over Wi-Fi, then asks `[Y]es & remember / [s]ession only / [n]o` on the PC terminal with a 4-digit verification code.

---

## Quick Start

### 1. Start the PC Server (Windows)

Requires **Node.js 18+**.

```cmd
npx leanback
```

*(Or from a local clone: `cd pc && npm install && npm start`)*

> If Windows Defender Firewall prompts on first launch, allow Node.js on **Private networks** so your phone can reach port `7890` (WebSocket) and `7891` (LAN discovery).

### 2. Install & Connect on Android

1. Download the APK from [Releases](https://github.com/somemusic69-sudo/LeanBack/releases) (or build from `android/`).
2. Open **LeanBack** on the same Wi-Fi and tap the **`⚙`** icon in the top-right corner.
3. Tap your PC under **Nearby PCs** (or enter `IP:Port` manually).
4. Press **`Y`** (or **`Enter`**) on your PC terminal to trust the phone.

After pairing once, LeanBack saves a hashed device token (`~/.leanback/devices.json`) and auto-reconnects silently next time.

---

## Touchpad & Click Modes

Switch between modes anytime in **`⚙` Settings → Click Mode**:

| Mode | Behavior |
| :--- | :--- |
| **Tap touchpad** *(default)* | 1-finger tap = Left click, double-tap & hold = Drag, 2-finger tap = Right click, 3-finger tap = Middle click |
| **Click buttons** | Touchpad only moves cursor & scrolls (no tap-clicks or accidental double-taps). Choose **Side-by-side** (`Left` \| `Right`) or **Left side** (`1:1` square `Right` over `Left` column on the bottom-left, keeping the right side open as touchpad). Hold `Left` + slide pad to drag. |

**Always active on both modes:** `1-finger slide` moves cursor, `2-finger slide` scrolls.

---

## PC CLI Commands

```text
leanback                    Start server on port 7890
leanback --port 8080        Use custom WebSocket port
leanback --speed 1.4        Cursor speed multiplier (0.2 - 5.0)
leanback --always-ask       Require [Y/n] PC approval on every connection
leanback --no-discovery     Disable UDP LAN auto-discovery (7891/udp)
leanback --no-clipboard     Save uploaded photos without copying to clipboard
leanback devices            List trusted phones
leanback revoke <id>        Revoke a trusted phone
leanback reset              Forget all trusted phones
```

---

## Build From Source

- **PC CLI tests**:
  ```cmd
  cd pc
  npm install
  npm test
  ```
- **Android APK**:
  ```cmd
  cd android
  ./gradlew assembleDebug
  ```
  Output: `android/app/build/outputs/apk/debug/app-debug.apk`. To sign release builds locally, add `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and run `./gradlew assembleRelease`.

---

## Project Structure

- [`pc/`](pc/) — `leanback` npm CLI (`ws` WebSocket server, `7891/udp` discovery, SHA-256 trust store, zero-compile Windows `SendInput` driver).
- [`android/`](android/) — Native Kotlin Android app (`TouchpadView`, `RemoteImeView`, `LanClient`, `LanDiscovery`, `SettingsDialog`).
- [`PROTOCOL.md`](PROTOCOL.md) — LAN discovery & WebSocket protocol specification.

## License

[MIT](LICENSE)
