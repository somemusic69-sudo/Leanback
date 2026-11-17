import os from "node:os";
import crypto from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";
import { DEFAULT_WS_PORT, handleInputMessage } from "./protocol.js";
import { startDiscoveryResponder, getLocalIPv4Addresses } from "./discovery.js";

function normalizeIp(raw) {
  if (!raw) return "unknown";
  return raw.startsWith("::ffff:") ? raw.slice(7) : raw;
}

function sendJson(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

/**
 * Start the LeanBack WebSocket + LAN Discovery server.
 */
export async function startServer({
  port = DEFAULT_WS_PORT,
  host = "0.0.0.0",
  alwaysAsk = false,
  enableDiscovery = true,
  discoveryPort,
  driver,
  trustStore,
  approver,
  onConnected,
  onDisconnected,
}) {
  const serverName = os.hostname();
  const wss = new WebSocketServer({ port, host });

  await new Promise((resolve, reject) => {
    wss.once("listening", resolve);
    wss.once("error", reject);
  });

  const actualPort = wss.address().port;
  const discovery = enableDiscovery
    ? startDiscoveryResponder({
        wsPort: actualPort,
        serverName,
        discoveryPort,
      })
    : null;
  if (discovery) {
    await discovery.ready;
  }

  let activeSocket = null;
  const heartbeatMs = 5000;
  const heartbeatTimer = setInterval(() => {
    for (const client of wss.clients) {
      if (client.isAlive === false) {
        client.terminate();
        continue;
      }
      client.isAlive = false;
      try {
        client.ping();
      } catch {
        client.terminate();
      }
    }
  }, heartbeatMs);
  heartbeatTimer.unref?.();

  wss.on("connection", (ws, req) => {
    // Disable Nagle's algorithm so mouse motion packets flush immediately with minimal latency.
    req.socket?.setNoDelay?.(true);

    const remoteIp = normalizeIp(req.socket?.remoteAddress);
    ws.isAlive = true;
    ws.on("pong", () => {
      ws.isAlive = true;
    });

    let state = "awaiting_hello"; // awaiting_hello -> pending -> authenticated -> closed
    let deviceId = null;
    let deviceName = "Android Phone";
    const pendingAbort = new AbortController();

    const helloTimeout = setTimeout(() => {
      if (state === "awaiting_hello") {
        ws.close(4001, "Handshake timeout");
      }
    }, 8000);
    helloTimeout.unref?.();

    const activateClient = (issuedToken, trusted) => {
      if (ws.readyState !== WebSocket.OPEN) return;

      if (activeSocket && activeSocket !== ws && activeSocket.readyState === WebSocket.OPEN) {
        sendJson(activeSocket, { type: "replaced", reason: "Another device connected" });
        activeSocket.close(4000, "Replaced by another device");
      }
      activeSocket = ws;
      state = "authenticated";

      sendJson(ws, {
        type: "welcome",
        serverName,
        trusted,
        ...(issuedToken ? { token: issuedToken } : {}),
      });
      onConnected?.(deviceName, remoteIp, trusted);
    };

    ws.on("message", async (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString("utf8"));
      } catch {
        return;
      }

      if (state === "awaiting_hello") {
        if (msg?.type !== "hello" || typeof msg.deviceId !== "string" || !msg.deviceId.trim()) {
          ws.close(4002, "Expected hello");
          return;
        }
        clearTimeout(helloTimeout);
        deviceId = msg.deviceId.trim().slice(0, 128);
        deviceName =
          typeof msg.deviceName === "string" && msg.deviceName.trim()
            ? msg.deviceName.trim().slice(0, 64)
            : "Android Phone";
        const presentedToken = typeof msg.token === "string" ? msg.token : "";

        if (!alwaysAsk && trustStore.verify(deviceId, presentedToken, deviceName)) {
          activateClient(null, true);
          return;
        }

        state = "pending";
        const code = String(crypto.randomInt(1000, 10000));
        sendJson(ws, {
          type: "pending",
          serverName,
          code,
        });

        const decision = await approver({
          deviceId,
          deviceName,
          remoteIp,
          code,
          signal: pendingAbort.signal,
        });

        if (ws.readyState !== WebSocket.OPEN || pendingAbort.signal.aborted) {
          return;
        }

        if (decision === "trust") {
          const newToken = trustStore.trustDevice(deviceId, deviceName);
          activateClient(newToken, true);
        } else if (decision === "session") {
          activateClient(null, false);
        } else {
          state = "closed";
          sendJson(ws, { type: "rejected", reason: "Connection denied on PC" });
          ws.close(4003, "Rejected");
        }
        return;
      }

      if (state === "authenticated") {
        if (activeSocket !== ws) return;
        handleInputMessage(msg, driver, (reply) => sendJson(ws, reply));
      }
    });

    ws.on("close", (_code, reasonBuf) => {
      clearTimeout(helloTimeout);
      pendingAbort.abort();
      const wasAuthenticated = state === "authenticated";
      state = "closed";
      if (activeSocket === ws) {
        activeSocket = null;
      }
      if (wasAuthenticated) {
        const reason = reasonBuf?.toString?.("utf8") || "";
        onDisconnected?.(deviceName, reason);
      }
    });

    ws.on("error", () => {
      pendingAbort.abort();
    });
  });

  return {
    port: actualPort,
    ips: getLocalIPv4Addresses(),
    serverName,
    close: async () => {
      clearInterval(heartbeatTimer);
      for (const client of wss.clients) {
        try {
          client.close(1001, "Server shutting down");
          setTimeout(() => {
            if (client.readyState !== WebSocket.CLOSED) {
              client.terminate();
            }
          }, 100).unref?.();
        } catch {
          client.terminate();
        }
      }
      await Promise.all([
        discovery ? discovery.close() : Promise.resolve(),
        new Promise((resolve) => wss.close(() => resolve())),
      ]);
    },
  };
}
