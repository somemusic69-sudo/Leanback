import test from "node:test";
import assert from "node:assert/strict";
import { handleInputMessage, sanitizeLabel } from "../src/protocol.js";
import {
  applyPointerAcceleration,
  charToVk,
  computeMagnifierOffset,
  VK_MAP,
} from "../src/input/win32.js";

test("handleInputMessage clamps deltas and dispatches driver calls", () => {
  const calls = [];
  const mockDriver = {
    moveMouse: (dx, dy) => calls.push(["move", dx, dy]),
    click: (btn) => calls.push(["click", btn]),
    mouseDown: (btn) => calls.push(["down", btn]),
    mouseUp: (btn) => calls.push(["up", btn]),
    scroll: (dx, dy) => calls.push(["scroll", dx, dy]),
    typeText: (text, mods) => calls.push(["text", text, mods]),
    pressKey: (key, mods, repeat) => calls.push(["key", key, mods, repeat]),
    setZoom: (level) => calls.push(["zoom", level]),
    zoomStart: () => calls.push(["zoomStart"]),
    zoomEnd: () => calls.push(["zoomEnd"]),
  };

  const replies = [];
  handleInputMessage({ type: "move", dx: 99999, dy: -99999 }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "click", button: "bogus" }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "click", button: "right" }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "text", text: "abc", modifiers: ["CTRL"] }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "key", key: "ESC", repeat: 99 }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "zoom", level: 9.9 }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "zoomStart" }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "zoomEnd" }, mockDriver, (r) => replies.push(r));
  handleInputMessage({ type: "ping", ts: 12345 }, mockDriver, (r) => replies.push(r));

  assert.deepEqual(calls, [
    ["move", 2000, -2000],
    ["click", "left"],
    ["click", "right"],
    ["text", "abc", ["CTRL"]],
    ["key", "ESC", [], 20],
    ["zoom", 5.0],
    ["zoomStart"],
    ["zoomEnd"],
  ]);
  assert.deepEqual(replies, [{ type: "pong", ts: 12345 }]);
});

test("Win32 helpers map virtual keys and apply non-linear pointer acceleration", () => {
  assert.equal(charToVk("c"), 0x43);
  assert.equal(charToVk("C"), 0x43);
  assert.equal(charToVk("9"), 0x39);
  assert.equal(VK_MAP.ENTER, 0x0d);
  assert.equal(VK_MAP.WIN, 0x5b);
  assert.equal(VK_MAP.OEM_PLUS, 0xbb);

  const slow = applyPointerAcceleration(1, 0, 1.0);
  const fast = applyPointerAcceleration(30, 0, 1.0);
  assert.ok(slow.x > 1 && slow.x < 1.2);
  assert.ok(fast.x / 30 > slow.x / 1);
});

test("sanitizeLabel strips terminal ANSI escape sequences and control chars", () => {
  assert.equal(sanitizeLabel("\x1b[31mEvil\x1b[0m\nPhone\x07"), "Evil Phone");
  assert.equal(sanitizeLabel("   \n\t  "), "Android Phone");
});

test("computeMagnifierOffset centers on cursor and clamps to monitor edges", () => {
  const bounds = { left: 0, top: 0, width: 1920, height: 1080 };
  assert.deepEqual(computeMagnifierOffset(960, 540, 2.0, bounds), { x: 480, y: 270 });
  assert.deepEqual(computeMagnifierOffset(10, 10, 2.0, bounds), { x: 0, y: 0 });
  assert.deepEqual(computeMagnifierOffset(1910, 1070, 2.0, bounds), { x: 960, y: 540 });
});
