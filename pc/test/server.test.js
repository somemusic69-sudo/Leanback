import test from "node:test";
import assert from "node:assert/strict";
import dgram from "node:dgram";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { WebSocket } from "ws";
import { startServer } from "../src/server.js";
import { TrustStore } from "../src/trustStore.js";
import { DISCOVERY_MAGIC } from "../src/protocol.js";

function createQueue(ws) {
  const buffered = [];
  const waiters = [];

  ws.on("message", (data) => {
    const parsed = JSON.parse(data.toString("utf8"));
    if (waiters.length > 0) {
      waiters.shift()(parsed);
    } else {
      buffered.push(parsed);
    }
  });

  return () => {
    if (buffered.length > 0) {
      return Promise.resolve(buffered.shift());
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Timed out waiting for WS message")), 3000);
      waiters.push((msg) => {
        clearTimeout(timer);
        resolve(msg);
      });
    });
  };
}

function connectWs(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const nextMsg = createQueue(ws);
    ws.once("open", () => resolve({ ws, nextMsg }));
    ws.once("error", reject);
  });
}

test("Server pairing, token trust, rejection, and UDP discovery", async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "leanback-srv-"));
  const trustStore = new TrustStore(path.join(tmpDir, "devices.json"));
  const actions = [];
  const mockDriver = {
    moveMouse: (dx, dy) => actions.push(["move", dx, dy]),
    click: (btn) => actions.push(["click", btn]),
    mouseDown: () => {},
    mouseUp: () => {},
    scroll: () => {},
    typeText: (text) => actions.push(["text", text]),
    pressKey: (key) => actions.push(["key", key]),
  };

  let approverCalls = 0;
  let nextDecision = "trust";
  const discoveryPort = 47891;

  const srv = await startServer({
    port: 0,
    host: "127.0.0.1",
    discoveryPort,
    trustStore,
    driver: mockDriver,
    approver: async ({ code }) => {
      approverCalls++;
      assert.match(code, /^\d{4}$/);
      return nextDecision;
    },
  });

  try {
    // 1. First-time device must be approved and receives a token
    const c1 = await connectWs(`ws://127.0.0.1:${srv.port}`);
    c1.ws.send(JSON.stringify({ type: "hello", deviceId: "dev-a", deviceName: "Phone A" }));
    const pendingMsg = await c1.nextMsg();
    assert.equal(pendingMsg.type, "pending");

    const welcome1 = await c1.nextMsg();
    assert.equal(welcome1.type, "welcome");
    assert.equal(welcome1.trusted, true);
    assert.equal(typeof welcome1.token, "string");
    assert.equal(approverCalls, 1);

    c1.ws.send(JSON.stringify({ type: "move", dx: 5, dy: -3 }));
    c1.ws.send(JSON.stringify({ type: "text", text: "hi" }));
    await new Promise((r) => setTimeout(r, 30));
    assert.deepEqual(actions, [
      ["move", 5, -3],
      ["text", "hi"],
    ]);
    c1.ws.close();
    await new Promise((r) => setTimeout(r, 20));

    // 2. Reconnect with issued token skips approver
    const c2 = await connectWs(`ws://127.0.0.1:${srv.port}`);
    c2.ws.send(
      JSON.stringify({
        type: "hello",
        deviceId: "dev-a",
        deviceName: "Phone A",
        token: welcome1.token,
      })
    );
    const welcome2 = await c2.nextMsg();
    assert.equal(welcome2.type, "welcome");
    assert.equal(approverCalls, 1);
    c2.ws.close();

    // 3. Denied device receives rejected and cannot inject input
    nextDecision = "deny";
    const c3 = await connectWs(`ws://127.0.0.1:${srv.port}`);
    c3.ws.send(JSON.stringify({ type: "hello", deviceId: "dev-b", deviceName: "Phone B" }));
    const pending3 = await c3.nextMsg();
    assert.equal(pending3.type, "pending");
    const rejected3 = await c3.nextMsg();
    assert.equal(rejected3.type, "rejected");
    c3.ws.close();

    // 4. UDP discovery replies with server metadata
    const udpResp = await new Promise((resolve, reject) => {
      const sock = dgram.createSocket("udp4");
      const timer = setTimeout(() => {
        sock.close();
        reject(new Error("UDP discovery timed out"));
      }, 2000);
      sock.once("message", (buf) => {
        clearTimeout(timer);
        sock.close();
        resolve(JSON.parse(buf.toString("utf8")));
      });
      sock.send(DISCOVERY_MAGIC, discoveryPort, "127.0.0.1");
    });
    assert.equal(udpResp.service, "leanback");
    assert.equal(udpResp.port, srv.port);

    // 5. Session-only approval works without writing a permanent token
    nextDecision = "session";
    const c4 = await connectWs(`ws://127.0.0.1:${srv.port}`);
    c4.ws.send(JSON.stringify({ type: "hello", deviceId: "dev-c", deviceName: "Phone C" }));
    assert.equal((await c4.nextMsg()).type, "pending");
    const welcome4 = await c4.nextMsg();
    assert.equal(welcome4.type, "welcome");
    assert.equal(welcome4.trusted, false);
    assert.equal(welcome4.token, undefined);
    c4.ws.close();
  } finally {
    await srv.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test("Server alwaysAsk prompts even with valid saved token", async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "leanback-ask-"));
  const trustStore = new TrustStore(path.join(tmpDir, "devices.json"));
  const savedToken = trustStore.trustDevice("dev-x", "Phone X");
  let prompted = 0;

  const srv = await startServer({
    port: 0,
    host: "127.0.0.1",
    enableDiscovery: false,
    alwaysAsk: true,
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
    approver: async () => {
      prompted++;
      return "session";
    },
  });

  try {
    const c = await connectWs(`ws://127.0.0.1:${srv.port}`);
    c.ws.send(JSON.stringify({ type: "hello", deviceId: "dev-x", deviceName: "Phone X", token: savedToken }));
    assert.equal((await c.nextMsg()).type, "pending");
    const welcome = await c.nextMsg();
    assert.equal(welcome.type, "welcome");
    assert.equal(prompted, 1);
    c.ws.close();
  } finally {
    await srv.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
