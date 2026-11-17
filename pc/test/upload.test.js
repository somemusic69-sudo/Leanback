import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { WebSocket } from "ws";
import { detectImageFormat, saveUploadedImage } from "../src/upload.js";
import { startServer } from "../src/server.js";
import { TrustStore } from "../src/trustStore.js";

const SAMPLE_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

test("detectImageFormat identifies image magic bytes and rejects non-images", () => {
  assert.equal(detectImageFormat(SAMPLE_PNG), "png");
  assert.equal(detectImageFormat(Buffer.from([0xff, 0xd8, 0xff, 0xe0])), "jpg");
  assert.equal(detectImageFormat(Buffer.from("GIF89a...")), "gif");
  assert.equal(detectImageFormat(Buffer.from("RIFF\x00\x00\x00\x00WEBP")), "webp");
  assert.equal(detectImageFormat(Buffer.from("BM\x00\x00")), "bmp");
  assert.equal(detectImageFormat(Buffer.from("#!/bin/sh\nrm -rf")), null);
});

test("POST /upload enforces uploadToken and magic-byte validation", async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "leanback-up-"));
  const saveDir = path.join(tmpDir, "images");
  const trustStore = new TrustStore(path.join(tmpDir, "devices.json"));
  const token = trustStore.trustDevice("phone-up", "Pixel");
  const received = [];

  const srv = await startServer({
    port: 0,
    host: "127.0.0.1",
    enableDiscovery: false,
    saveDir,
    copyImagesToClipboard: false,
    trustStore,
    driver: {
      moveMouse: () => {},
      click: () => {},
      mouseDown: () => {},
      mouseUp: () => {},
      scroll: () => {},
      typeText: () => {},
      pressKey: () => {},
    },
    approver: async () => "deny",
    onImageReceived: (info) => received.push(info),
  });

  try {
    // 1. Unauthenticated upload rejected
    const resUnauth = await fetch(`http://127.0.0.1:${srv.port}/upload`, {
      method: "POST",
      body: SAMPLE_PNG,
    });
    assert.equal(resUnauth.status, 401);

    // 2. Connect WS to obtain session uploadToken
    const ws = new WebSocket(`ws://127.0.0.1:${srv.port}`);
    const welcome = await new Promise((resolve, reject) => {
      ws.once("open", () => {
        ws.send(JSON.stringify({ type: "hello", deviceId: "phone-up", deviceName: "Pixel", token }));
      });
      ws.once("message", (buf) => resolve(JSON.parse(buf.toString("utf8"))));
      ws.once("error", reject);
    });
    assert.equal(welcome.type, "welcome");
    assert.equal(typeof welcome.uploadToken, "string");

    // 3. Invalid non-image payload rejected with 415
    const resBad = await fetch(`http://127.0.0.1:${srv.port}/upload`, {
      method: "POST",
      headers: { "x-leanback-token": welcome.uploadToken },
      body: Buffer.from("MZ-not-an-image"),
    });
    assert.equal(resBad.status, 415);

    // 4. Valid PNG saved cleanly to saveDir
    const resOk = await fetch(`http://127.0.0.1:${srv.port}/upload`, {
      method: "POST",
      headers: { "x-leanback-token": welcome.uploadToken },
      body: SAMPLE_PNG,
    });
    assert.equal(resOk.status, 200);
    const jsonOk = await resOk.json();
    assert.equal(jsonOk.ok, true);
    assert.ok(jsonOk.filename.endsWith(".png"));
    assert.equal(received.length, 1);
    assert.equal(fs.existsSync(jsonOk.path), true);

    ws.close();
  } finally {
    await srv.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("saveUploadedImage rejects non-image buffers directly", async () => {
  await assert.rejects(
    () => saveUploadedImage(Buffer.from("plain text"), { copyToClipboard: false }),
    { statusCode: 415 }
  );
});
