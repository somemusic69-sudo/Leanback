import http from "node:http";
import os from "node:os";
import crypto from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";
import { DEFAULT_WS_PORT, handleInputMessage, sanitizeLabel } from "./protocol.js";
import { startDiscoveryResponder, getLocalIPv4Addresses } from "./discovery.js";
import { MAX_UPLOAD_BYTES, saveUploadedImage } from "./upload.js";

function normalizeIp(raw) {
  if (!raw) return "unknown";
  return raw.startsWith("::ffff:") ? raw.slice(7) : raw;
}

function sendJson(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function safeTokenEquals(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || !a || a.length !== b.length) {
    return false;
  }
  return crypto.timingSafeEqual(Buffer.from(a, "utf8"), Buffer.from(b, "utf8"));
}

function readLimitedBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let settled = false;

    req.on("data", (chunk) => {
      if (settled) return;
      total += chunk.length;
      if (total > maxBytes) {
        settled = true;
        const err = new Error("Image exceeds maximum allowed size");
        err.statusCode = 413;
        req.pause();
        reject(err);
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      if (!settled) {
        settled = true;
        resolve(Buffer.concat(chunks, total));
      }
    });

    req.on("error", (err) => {
      if (!settled) {
        settled = true;
        reject(err);
      }
    });
  });
}

/**
 * Start the LeanBack WebSocket + HTTP Upload + LAN Discovery server.
 */
export async function startServer({
  port = DEFAULT_WS_PORT,
  host = "0.0.0.0",
  alwaysAsk = false,
  enableDiscovery = true,
  discoveryPort,
  saveDir,
  copyImagesToClipboard = true,
  driver,
  trustStore,
  approver,
  onConnected,
  onDisconnected,
  onImageReceived,
}) {
  const serverName = os.hostname();
  let activeSocket = null;
  let activeUploadToken = null;

  const httpServer = http.createServer(async (req, res) => {
    const jsonRes = (status, body, closeAfter = false) => {
      const headers = { "Content-Type": "application/json" };
      if (closeAfter) headers.Connection = "close";
      res.writeHead(status, headers);
      res.end(JSON.stringify(body), () => {
        if (closeAfter) req.destroy();
      });
    };

    if (req.method !== "POST" || req.url?.split("?")[0] !== "/upload") {
      jsonRes(404, { error: "Not found" });
      return;
    }

    const headerToken = req.headers["x-leanback-token"];
    if (!activeUploadToken || !safeTokenEquals(String(headerToken || ""), activeUploadToken)) {
      jsonRes(401, { error: "Unauthorized" }, true);
      return;
    }

    const declaredLength = Number(req.headers["content-length"]);
    if (Number.isFinite(declaredLength) && declaredLength > MAX_UPLOAD_BYTES) {
      jsonRes(413, { error: "Image exceeds 20 MB limit" }, true);
      return;
    }

    try {
      const body = await readLimitedBody(req, MAX_UPLOAD_BYTES);
      const saved = await saveUploadedImage(body, {
        saveDir,
        copyToClipboard: copyImagesToClipboard,
      });
      onImageReceived?.(saved);
      jsonRes(200, {
        ok: true,
        filename: saved.filename,
        path: saved.filePath,
        copied: saved.copied,
      });
    } catch (err) {
      const status = err?.statusCode || 400;
      jsonRes(status, { error: err?.message || "Upload failed" }, status === 413);
    }
  });

  const wss = new WebSocketServer({ server: httpServer, maxPayload: 64 * 1024 });

  await new Promise((resolve, reject) => {
    httpServer.once("listening", resolve);
    httpServer.once("error", reject);
    httpServer.listen(port, host);
  });

  const actualPort = httpServer.address().port;
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

  let pendingCount = 0;
  const MAX_PENDING_PROMPTS = 4;
  const APPROVAL_TIMEOUT_MS = 60_000;
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
        driver.releaseAll?.();
        sendJson(activeSocket, { type: "replaced", reason: "Another device connected" });
        activeSocket.close(4000, "Replaced by another device");
      }
      activeSocket = ws;
      activeUploadToken = crypto.randomBytes(24).toString("hex");
      state = "authenticated";

      sendJson(ws, {
        type: "welcome",
        serverName,
        trusted,
        uploadToken: activeUploadToken,
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
        deviceId = sanitizeLabel(msg.deviceId, 128, "");
        if (deviceId.length < 4) {
          ws.close(4002, "Invalid deviceId");
          return;
        }
        deviceName = sanitizeLabel(msg.deviceName, 64, "Android Phone");
        const presentedToken = typeof msg.token === "string" ? msg.token : "";

        if (!alwaysAsk && trustStore.verify(deviceId, presentedToken, deviceName)) {
          activateClient(null, true);
          return;
        }

        if (pendingCount >= MAX_PENDING_PROMPTS) {
          state = "closed";
          sendJson(ws, { type: "rejected", reason: "Too many pending pairing requests" });
          ws.close(4004, "Busy");
          return;
        }

        state = "pending";
        pendingCount++;
        const code = String(crypto.randomInt(1000, 10000));
        sendJson(ws, {
          type: "pending",
          serverName,
          code,
        });

        const approvalTimer = setTimeout(() => {
          pendingAbort.abort();
        }, APPROVAL_TIMEOUT_MS);
        approvalTimer.unref?.();

        let decision = "deny";
        try {
          decision = await approver({
            deviceId,
            deviceName,
            remoteIp,
            code,
            signal: pendingAbort.signal,
          });
        } finally {
          clearTimeout(approvalTimer);
          pendingCount = Math.max(0, pendingCount - 1);
        }

        if (ws.readyState !== WebSocket.OPEN || pendingAbort.signal.aborted) {
          if (ws.readyState === WebSocket.OPEN) {
            state = "closed";
            sendJson(ws, { type: "rejected", reason: "Approval timed out" });
            ws.close(4003, "Approval timeout");
          }
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
        activeUploadToken = null;
        driver.releaseAll?.();
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
      activeUploadToken = null;
      driver.releaseAll?.();
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
        new Promise((resolve) => httpServer.close(() => resolve())),
      ]);
    },
  };
}
