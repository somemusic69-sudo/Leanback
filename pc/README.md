# leanback

Control your PC mouse and keyboard from your Android phone over Wi-Fi — no `.exe` download required.

## Quick Start

Run directly with `npx`:

```bash
npx leanback
```

Or install globally:

```bash
npm install -g leanback
leanback
```

1. Start `leanback` on your PC (runs on port `7890` by default).
2. Open the **LeanBack** Android app on your phone (on the same Wi-Fi).
3. Tap the small gear icon (`⚙`) -> select your PC from **Nearby PCs** (or enter its IP).
4. Press **`Y`** (or **`Enter`**) on your PC terminal to accept the phone.

After the first approval, your phone receives a device token (stored hashed as SHA-256 in `~/.leanback/devices.json`) so future opens reconnect automatically.

## CLI Options & Commands

```text
leanback [options]
leanback devices          List trusted phones
leanback revoke <id>      Revoke trust for a phone (ID or prefix)
leanback reset            Forget all trusted phones

Options:
  -p, --port <port>         WebSocket port (default: 7890)
  -s, --speed <multiplier>  Cursor speed multiplier, 0.2 - 5.0 (default: 1.0)
      --always-ask          Always prompt [Y/n] on PC for every connection
      --no-discovery        Disable UDP LAN auto-discovery (port 7891)
```

## Requirements

- **Node.js** `>= 18`
- **Windows** (uses native `user32.dll` `SendInput` via `koffi` — zero C++ build tools needed)
