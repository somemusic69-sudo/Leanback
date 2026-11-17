export const DEFAULT_WS_PORT = 7890;
export const DISCOVERY_PORT = 7891;
export const DISCOVERY_MAGIC = "LEANBACK_DISCOVER_V1";
export const PROTOCOL_VERSION = 1;

const VALID_BUTTONS = new Set(["left", "right", "middle"]);

function clampNumber(value, min, max, fallback = 0) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

/**
 * Dispatch an authenticated client message to the OS input driver.
 * @param {object} msg
 * @param {object} driver
 * @param {(reply: object) => void} sendReply
 */
export function handleInputMessage(msg, driver, sendReply) {
  if (!msg || typeof msg !== "object") return;

  switch (msg.type) {
    case "move": {
      const dx = clampNumber(msg.dx, -2000, 2000, 0);
      const dy = clampNumber(msg.dy, -2000, 2000, 0);
      if (dx !== 0 || dy !== 0) {
        driver.moveMouse(dx, dy);
      }
      break;
    }

    case "click": {
      const button = VALID_BUTTONS.has(msg.button) ? msg.button : "left";
      driver.click(button);
      break;
    }

    case "mouseDown": {
      const button = VALID_BUTTONS.has(msg.button) ? msg.button : "left";
      driver.mouseDown(button);
      break;
    }

    case "mouseUp": {
      const button = VALID_BUTTONS.has(msg.button) ? msg.button : "left";
      driver.mouseUp(button);
      break;
    }

    case "scroll": {
      const dx = clampNumber(msg.dx, -500, 500, 0);
      const dy = clampNumber(msg.dy, -500, 500, 0);
      if (dx !== 0 || dy !== 0) {
        driver.scroll(dx, dy);
      }
      break;
    }

    case "text": {
      if (typeof msg.text === "string" && msg.text.length > 0) {
        // Guard against oversized single-frame pastes locking the input queue.
        const bounded = msg.text.slice(0, 4096);
        const modifiers = Array.isArray(msg.modifiers) ? msg.modifiers : [];
        driver.typeText(bounded, modifiers);
      }
      break;
    }

    case "key": {
      if (typeof msg.key === "string" && msg.key.length > 0) {
        const modifiers = Array.isArray(msg.modifiers) ? msg.modifiers : [];
        const repeat = clampNumber(msg.repeat, 1, 20, 1);
        driver.pressKey(msg.key, modifiers, repeat);
      }
      break;
    }

    case "ping": {
      sendReply({ type: "pong", ts: msg.ts ?? Date.now() });
      break;
    }

    default:
      break;
  }
}
