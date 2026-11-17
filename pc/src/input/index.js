import { Win32InputDriver } from "./win32.js";

/**
 * Create the OS-specific mouse & keyboard input driver.
 * Structured behind an adapter boundary so macOS/Linux backends can be added cleanly later.
 * @param {{ speed?: number }} [options]
 */
export function createInputDriver(options = {}) {
  const speed = Number.isFinite(options.speed) ? Math.max(0.2, Math.min(5.0, options.speed)) : 1.0;
  const zoom = Number.isFinite(options.zoom) ? Math.max(1.3, Math.min(5.0, options.zoom)) : 2.2;

  if (process.platform === "win32") {
    return new Win32InputDriver(speed, zoom);
  }

  throw new Error(
    `Unsupported platform "${process.platform}". LeanBack currently ships a zero-config Windows driver; macOS/Linux adapters are planned.`
  );
}
