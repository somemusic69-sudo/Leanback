import koffi from "koffi";

const INPUT_MOUSE = 0;
const INPUT_KEYBOARD = 1;

const MOUSEEVENTF_MOVE = 0x0001;
const MOUSEEVENTF_LEFTDOWN = 0x0002;
const MOUSEEVENTF_LEFTUP = 0x0004;
const MOUSEEVENTF_RIGHTDOWN = 0x0008;
const MOUSEEVENTF_RIGHTUP = 0x0010;
const MOUSEEVENTF_MIDDLEDOWN = 0x0020;
const MOUSEEVENTF_MIDDLEUP = 0x0040;
const MOUSEEVENTF_WHEEL = 0x0800;
const MOUSEEVENTF_HWHEEL = 0x1000;

const KEYEVENTF_EXTENDEDKEY = 0x0001;
const KEYEVENTF_KEYUP = 0x0002;
const KEYEVENTF_UNICODE = 0x0004;

export const VK_MAP = Object.freeze({
  BACKSPACE: 0x08,
  TAB: 0x09,
  ENTER: 0x0d,
  RETURN: 0x0d,
  SHIFT: 0x10,
  CTRL: 0x11,
  CONTROL: 0x11,
  ALT: 0x12,
  ESC: 0x1b,
  ESCAPE: 0x1b,
  SPACE: 0x20,
  PGUP: 0x21,
  PAGE_UP: 0x21,
  PGDN: 0x22,
  PAGE_DOWN: 0x22,
  END: 0x23,
  HOME: 0x24,
  LEFT: 0x25,
  UP: 0x26,
  RIGHT: 0x27,
  DOWN: 0x28,
  INSERT: 0x2d,
  DELETE: 0x2e,
  DEL: 0x2e,
  WIN: 0x5b,
  META: 0x5b,
  SUPER: 0x5b,
  F1: 0x70,
  F2: 0x71,
  F3: 0x72,
  F4: 0x73,
  F5: 0x74,
  F6: 0x75,
  F7: 0x76,
  F8: 0x77,
  F9: 0x78,
  F10: 0x79,
  F11: 0x7a,
  F12: 0x7b,
  VOLUME_MUTE: 0xad,
  VOLUME_DOWN: 0xae,
  VOLUME_UP: 0xaf,
  MEDIA_NEXT: 0xb0,
  MEDIA_PREV: 0xb1,
  MEDIA_PLAY_PAUSE: 0xb3,
});

const EXTENDED_VK = new Set([
  0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2d, 0x2e, 0x5b, 0x5c,
]);

const OEM_VK_MAP = Object.freeze({
  " ": 0x20,
  "-": 0xbd,
  "=": 0xbb,
  "[": 0xdb,
  "]": 0xdd,
  "\\": 0xdc,
  ";": 0xba,
  "'": 0xde,
  ",": 0xbc,
  ".": 0xbe,
  "/": 0xbf,
  "`": 0xc0,
});

/**
 * Resolve a single character to a Windows virtual-key code (for modifier combos like Ctrl+C).
 * @param {string} ch
 * @returns {number | null}
 */
export function charToVk(ch) {
  if (typeof ch !== "string" || ch.length !== 1) return null;
  const upper = ch.toUpperCase();
  const code = upper.charCodeAt(0);
  if (code >= 0x41 && code <= 0x5a) return code; // A-Z
  if (code >= 0x30 && code <= 0x39) return code; // 0-9
  return OEM_VK_MAP[ch] ?? null;
}

/**
 * Smooth trackpad acceleration curve: precise at slow speeds, snappy on quick swipes.
 * @param {number} dx
 * @param {number} dy
 * @param {number} speedMultiplier
 * @returns {{ x: number, y: number }}
 */
export function applyPointerAcceleration(dx, dy, speedMultiplier = 1.0) {
  const magnitude = Math.hypot(dx, dy);
  if (magnitude === 0) return { x: 0, y: 0 };
  const accel = 1.0 + Math.min(magnitude * 0.045, 2.0);
  const scale = accel * speedMultiplier;
  return { x: dx * scale, y: dy * scale };
}

let win32Bindings = null;

function getWin32Bindings() {
  if (win32Bindings) return win32Bindings;

  const MOUSEINPUT = koffi.struct("MOUSEINPUT", {
    dx: "int32",
    dy: "int32",
    mouseData: "uint32",
    dwFlags: "uint32",
    time: "uint32",
    dwExtraInfo: "uintptr_t",
  });

  const KEYBDINPUT = koffi.struct("KEYBDINPUT", {
    wVk: "uint16",
    wScan: "uint16",
    dwFlags: "uint32",
    time: "uint32",
    dwExtraInfo: "uintptr_t",
  });

  const HARDWAREINPUT = koffi.struct("HARDWAREINPUT", {
    uMsg: "uint32",
    wParamL: "uint16",
    wParamH: "uint16",
  });

  const INPUT_UNION = koffi.union("INPUT_UNION", {
    mi: MOUSEINPUT,
    ki: KEYBDINPUT,
    hi: HARDWAREINPUT,
  });

  const INPUT = koffi.struct("INPUT", {
    type: "uint32",
    u: INPUT_UNION,
  });

  const user32 = koffi.load("user32.dll");
  const SetProcessDPIAware = user32.func("bool __stdcall SetProcessDPIAware()");
  try {
    SetProcessDPIAware();
  } catch {
    // Non-fatal if DPI awareness was already configured by host console.
  }

  const SendInput = user32.func(
    "uint32 __stdcall SendInput(uint32 cInputs, INPUT *pInputs, int32 cbSize)"
  );

  win32Bindings = {
    SendInput,
    inputSize: koffi.sizeof(INPUT),
  };
  return win32Bindings;
}

function makeMouseInput(dwFlags, dx = 0, dy = 0, mouseData = 0) {
  return {
    type: INPUT_MOUSE,
    u: {
      mi: {
        dx: dx | 0,
        dy: dy | 0,
        mouseData: mouseData >>> 0,
        dwFlags: dwFlags >>> 0,
        time: 0,
        dwExtraInfo: 0,
      },
    },
  };
}

function makeKeyInput(wVk, wScan, dwFlags) {
  return {
    type: INPUT_KEYBOARD,
    u: {
      ki: {
        wVk: wVk & 0xffff,
        wScan: wScan & 0xffff,
        dwFlags: dwFlags >>> 0,
        time: 0,
        dwExtraInfo: 0,
      },
    },
  };
}

function makeVkEvents(vk, isKeyUp = false) {
  let flags = isKeyUp ? KEYEVENTF_KEYUP : 0;
  if (EXTENDED_VK.has(vk)) {
    flags |= KEYEVENTF_EXTENDEDKEY;
  }
  return makeKeyInput(vk, 0, flags);
}

export class Win32InputDriver {
  /**
   * @param {number} [speedMultiplier=1.0]
   */
  constructor(speedMultiplier = 1.0) {
    this.speedMultiplier = speedMultiplier;
    this.remX = 0;
    this.remY = 0;
    this.remScrollX = 0;
    this.remScrollY = 0;
    this.bindings = getWin32Bindings();
  }

  /**
   * @param {Array} inputs
   */
  #send(inputs) {
    if (!inputs.length) return;
    // Send in reasonable chunks so long pasted text doesn't hit buffer limits.
    const CHUNK = 128;
    for (let i = 0; i < inputs.length; i += CHUNK) {
      const slice = inputs.slice(i, i + CHUNK);
      this.bindings.SendInput(slice.length, slice, this.bindings.inputSize);
    }
  }

  moveMouse(dx, dy) {
    const scaled = applyPointerAcceleration(dx, dy, this.speedMultiplier);
    this.remX += scaled.x;
    this.remY += scaled.y;

    const ix = Math.trunc(this.remX);
    const iy = Math.trunc(this.remY);
    this.remX -= ix;
    this.remY -= iy;

    if (ix === 0 && iy === 0) return;
    this.#send([makeMouseInput(MOUSEEVENTF_MOVE, ix, iy, 0)]);
  }

  mouseDown(button = "left") {
    const flag =
      button === "right"
        ? MOUSEEVENTF_RIGHTDOWN
        : button === "middle"
          ? MOUSEEVENTF_MIDDLEDOWN
          : MOUSEEVENTF_LEFTDOWN;
    this.#send([makeMouseInput(flag)]);
  }

  mouseUp(button = "left") {
    const flag =
      button === "right"
        ? MOUSEEVENTF_RIGHTUP
        : button === "middle"
          ? MOUSEEVENTF_MIDDLEUP
          : MOUSEEVENTF_LEFTUP;
    this.#send([makeMouseInput(flag)]);
  }

  click(button = "left") {
    if (button === "right") {
      this.#send([
        makeMouseInput(MOUSEEVENTF_RIGHTDOWN),
        makeMouseInput(MOUSEEVENTF_RIGHTUP),
      ]);
    } else if (button === "middle") {
      this.#send([
        makeMouseInput(MOUSEEVENTF_MIDDLEDOWN),
        makeMouseInput(MOUSEEVENTF_MIDDLEUP),
      ]);
    } else {
      this.#send([
        makeMouseInput(MOUSEEVENTF_LEFTDOWN),
        makeMouseInput(MOUSEEVENTF_LEFTUP),
      ]);
    }
  }

  scroll(dx, dy) {
    // Scale dp deltas to smooth Windows high-resolution wheel deltas.
    this.remScrollY += dy * 4.5;
    this.remScrollX += dx * 4.5;

    const iy = Math.trunc(this.remScrollY);
    const ix = Math.trunc(this.remScrollX);
    this.remScrollY -= iy;
    this.remScrollX -= ix;

    const events = [];
    if (iy !== 0) {
      events.push(makeMouseInput(MOUSEEVENTF_WHEEL, 0, 0, iy));
    }
    if (ix !== 0) {
      events.push(makeMouseInput(MOUSEEVENTF_HWHEEL, 0, 0, ix));
    }
    this.#send(events);
  }

  /**
   * Type arbitrary Unicode text, optionally holding modifier keys (e.g. Ctrl+C).
   * @param {string} text
   * @param {string[]} [modifiers]
   */
  typeText(text, modifiers = []) {
    if (!text) return;

    const modVks = (Array.isArray(modifiers) ? modifiers : [])
      .map((m) => VK_MAP[String(m).toUpperCase()])
      .filter((vk) => vk != null);

    // When Ctrl, Alt, or Win is held, Windows ignores KEYEVENTF_UNICODE, so dispatch VK combos instead.
    const hasShortcutMod = modVks.some(
      (vk) => vk === VK_MAP.CTRL || vk === VK_MAP.ALT || vk === VK_MAP.WIN
    );

    if (hasShortcutMod) {
      for (const ch of text) {
        const vk = charToVk(ch);
        if (vk != null) {
          this.#sendCombo(modVks, vk);
        }
      }
      return;
    }

    const events = [];
    const normalized = text.replace(/\r\n/g, "\n");
    for (const ch of normalized) {
      if (ch === "\n" || ch === "\r") {
        events.push(makeVkEvents(VK_MAP.ENTER, false), makeVkEvents(VK_MAP.ENTER, true));
      } else if (ch === "\t") {
        events.push(makeVkEvents(VK_MAP.TAB, false), makeVkEvents(VK_MAP.TAB, true));
      } else if (ch === "\b") {
        events.push(makeVkEvents(VK_MAP.BACKSPACE, false), makeVkEvents(VK_MAP.BACKSPACE, true));
      } else {
        // Handle BMP and surrogate pairs cleanly using UTF-16 code units.
        for (let i = 0; i < ch.length; i++) {
          const codeUnit = ch.charCodeAt(i);
          events.push(makeKeyInput(0, codeUnit, KEYEVENTF_UNICODE));
        }
        for (let i = ch.length - 1; i >= 0; i--) {
          const codeUnit = ch.charCodeAt(i);
          events.push(makeKeyInput(0, codeUnit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
        }
      }
    }
    this.#send(events);
  }

  /**
   * Press a named virtual key (e.g., BACKSPACE, ENTER, ESC, TAB, LEFT) with optional modifiers.
   * @param {string} keyName
   * @param {string[]} [modifiers]
   * @param {number} [repeat=1]
   */
  pressKey(keyName, modifiers = [], repeat = 1) {
    const upper = String(keyName || "").toUpperCase();
    const vk = VK_MAP[upper] ?? (keyName?.length === 1 ? charToVk(keyName) : null);
    if (vk == null) return;

    const modVks = (Array.isArray(modifiers) ? modifiers : [])
      .map((m) => VK_MAP[String(m).toUpperCase()])
      .filter((mVk) => mVk != null && mVk !== vk);

    const count = Math.max(1, Math.min(20, Math.trunc(repeat) || 1));
    for (let i = 0; i < count; i++) {
      this.#sendCombo(modVks, vk);
    }
  }

  #sendCombo(modVks, vk) {
    const events = [];
    for (const mVk of modVks) {
      events.push(makeVkEvents(mVk, false));
    }
    events.push(makeVkEvents(vk, false));
    events.push(makeVkEvents(vk, true));
    for (let i = modVks.length - 1; i >= 0; i--) {
      events.push(makeVkEvents(modVks[i], true));
    }
    this.#send(events);
  }
}
