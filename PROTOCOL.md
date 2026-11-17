# LeanBack Wire Protocol (v1)

LeanBack uses two lightweight local-network transports:

1. **UDP Broadcast Discovery (`7891/udp`)** — lets the Android app find PCs on the same Wi-Fi without typing an IP address.
2. **WebSocket Control Stream (`7890/tcp` by default)** — low-latency bidirectional JSON channel with `TCP_NODELAY` enabled.

---

## 1. LAN Auto-Discovery (`7891/udp`)

Phone broadcasts:

```text
LEANBACK_DISCOVER_V1
```

PC responds directly to the sender's address & port with JSON:

```json
{
  "service": "leanback",
  "version": 1,
  "id": "a1b2c3d4e5f6",
  "name": "DESKTOP-GAMING",
  "port": 7890,
  "ip": "192.168.1.42",
  "ips": ["192.168.1.42"]
}
```

---

## 2. WebSocket Handshake & Pairing

### Phone → PC (`hello`)

```json
{
  "type": "hello",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceName": "Pixel 8",
  "token": "optional-64-hex-token-if-previously-paired"
}
```

### PC → Phone (`pending` — when untrusted or `--always-ask`)

```json
{
  "type": "pending",
  "serverName": "DESKTOP-GAMING",
  "code": "4829"
}
```

The PC operator confirms `[Y]es & remember / [s]ession only / [n]o` in the terminal.

### PC → Phone (`welcome`)

```json
{
  "type": "welcome",
  "serverName": "DESKTOP-GAMING",
  "trusted": true,
  "uploadToken": "per-session-48-hex-upload-token",
  "token": "64-hex-token-omitted-if-already-trusted-or-session-only"
}
```

Only the SHA-256 hash of `token` is stored on the PC (`~/.leanback/devices.json`).

### PC → Phone (`rejected` / `replaced`)

- `{"type": "rejected", "reason": "Connection denied on PC"}` (closes with `4003`; phone stops auto-retrying)
- `{"type": "replaced", "reason": "Another device connected"}` (closes with `4000`)

---

## 3. Authenticated Image Upload (`POST /upload`)

Authenticated clients receive a per-session `uploadToken` in `welcome` and can stream images directly to `http://<pc-ip>:<port>/upload` with header `X-LeanBack-Token: <uploadToken>`.

- Maximum size: **20 MB**
- Validated by magic bytes (`png`, `jpg`, `gif`, `webp`, `bmp`)
- Saved safely to `~/Downloads/LeanBack/` (and copied to the Windows clipboard unless `--no-clipboard` is passed)

---

## 4. Authenticated Input Messages (Phone → PC)

| Type | Fields | Description |
| :--- | :--- | :--- |
| `move` | `dx: number`, `dy: number` | Relative cursor delta in dp (scaled & accelerated on PC) |
| `click` | `button: "left" \| "right" \| "middle"` | Single mouse click |
| `mouseDown` | `button: "left" \| "right" \| "middle"` | Press and hold mouse button (drag) |
| `mouseUp` | `button: "left" \| "right" \| "middle"` | Release mouse button |
| `scroll` | `dx: number`, `dy: number` | Horizontal / vertical wheel delta |
| `text` | `text: string`, `modifiers?: string[]` | Unicode text or modifier shortcut (`["CTRL"]` + `"c"`) |
| `key` | `key: string`, `modifiers?: string[]`, `repeat?: number` | Named key (`BACKSPACE`, `ENTER`, `TAB`, `ESC`, `LEFT`, `UP`, `DOWN`, `RIGHT`, `WIN`, etc.) |
| `ping` | `ts?: number` | Optional latency check (`pong` reply) |
