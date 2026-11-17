import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";

/**
 * Hash a plaintext pairing token using SHA-256 so raw secrets are never stored on disk.
 * @param {string} token
 * @returns {string}
 */
export function hashToken(token) {
  return crypto.createHash("sha256").update(String(token), "utf8").digest("hex");
}

/**
 * Constant-time comparison of two hex strings.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
function safeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) {
    return false;
  }
  const bufA = Buffer.from(a, "hex");
  const bufB = Buffer.from(b, "hex");
  if (bufA.length === 0 || bufA.length !== bufB.length) {
    return false;
  }
  return crypto.timingSafeEqual(bufA, bufB);
}

export class TrustStore {
  /**
   * @param {string} [storePath]
   */
  constructor(storePath = path.join(os.homedir(), ".leanback", "devices.json")) {
    this.storePath = storePath;
  }

  /**
   * Load trusted device records from disk.
   * @returns {Array<{deviceId: string, deviceName: string, tokenHash: string, createdAt: string, lastSeen: string}>}
   */
  list() {
    try {
      if (!fs.existsSync(this.storePath)) {
        return [];
      }
      const raw = fs.readFileSync(this.storePath, "utf8");
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed?.devices) ? parsed.devices : [];
    } catch {
      return [];
    }
  }

  /**
   * Persist trusted device records to disk with restrictive permissions when supported.
   * @param {Array} devices
   */
  #save(devices) {
    const dir = path.dirname(this.storePath);
    fs.mkdirSync(dir, { recursive: true });
    const payload = JSON.stringify({ version: 1, devices }, null, 2);
    fs.writeFileSync(this.storePath, payload, { encoding: "utf8", mode: 0o600 });
  }

  /**
   * Issue and store a new trusted token for a device.
   * @param {string} deviceId
   * @param {string} deviceName
   * @returns {string} Plaintext token to send once to the paired phone.
   */
  trustDevice(deviceId, deviceName) {
    const token = crypto.randomBytes(32).toString("hex");
    const tokenHash = hashToken(token);
    const now = new Date().toISOString();
    const devices = this.list().filter((d) => d.deviceId !== deviceId);
    devices.push({
      deviceId,
      deviceName: deviceName || "Android Phone",
      tokenHash,
      createdAt: now,
      lastSeen: now,
    });
    this.#save(devices);
    return token;
  }

  /**
   * Verify a deviceId + plaintext token pair. Updates lastSeen if valid.
   * @param {string} deviceId
   * @param {string} token
   * @param {string} [deviceName]
   * @returns {boolean}
   */
  verify(deviceId, token, deviceName) {
    if (!deviceId || !token) return false;
    const devices = this.list();
    const entry = devices.find((d) => d.deviceId === deviceId);
    if (!entry) return false;
    const candidateHash = hashToken(token);
    if (!safeEqualHex(entry.tokenHash, candidateHash)) {
      return false;
    }
    entry.lastSeen = new Date().toISOString();
    if (deviceName) {
      entry.deviceName = deviceName;
    }
    this.#save(devices);
    return true;
  }

  /**
   * Revoke a single trusted device by ID or prefix.
   * @param {string} idOrPrefix
   * @returns {number} Number of removed devices.
   */
  revoke(idOrPrefix) {
    if (!idOrPrefix) return 0;
    const devices = this.list();
    const kept = devices.filter(
      (d) => d.deviceId !== idOrPrefix && !d.deviceId.startsWith(idOrPrefix)
    );
    const removed = devices.length - kept.length;
    if (removed > 0) {
      this.#save(kept);
    }
    return removed;
  }

  /**
   * Remove all trusted devices.
   * @returns {number} Number of cleared devices.
   */
  clear() {
    const count = this.list().length;
    this.#save([]);
    return count;
  }
}
