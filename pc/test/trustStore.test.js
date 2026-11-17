import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { TrustStore } from "../src/trustStore.js";

test("TrustStore stores hashed tokens and verifies/revokes cleanly", () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "leanback-trust-"));
  try {
    const storePath = path.join(tmpDir, "devices.json");
    const store = new TrustStore(storePath);

    assert.deepEqual(store.list(), []);

    const token = store.trustDevice("phone-1", "Pixel 8");
    assert.equal(typeof token, "string");
    assert.equal(token.length, 64);

    // Raw token must never appear in persisted JSON.
    const rawDisk = fs.readFileSync(storePath, "utf8");
    assert.equal(rawDisk.includes(token), false);

    assert.equal(store.verify("phone-1", token, "Pixel 8 Pro"), true);
    assert.equal(store.verify("phone-1", "wrong-token"), false);
    assert.equal(store.verify("unknown-phone", token), false);

    const list = store.list();
    assert.equal(list.length, 1);
    assert.equal(list[0].deviceName, "Pixel 8 Pro");

    assert.equal(store.revoke("phone-"), 1);
    assert.equal(store.verify("phone-1", token), false);

    store.trustDevice("phone-2", "Galaxy");
    store.trustDevice("phone-3", "OnePlus");
    assert.equal(store.clear(), 2);
    assert.deepEqual(store.list(), []);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
