import dgram from "node:dgram";
import os from "node:os";
import crypto from "node:crypto";
import { DISCOVERY_MAGIC, DISCOVERY_PORT, PROTOCOL_VERSION } from "./protocol.js";

const VIRTUAL_IFACE_PATTERN = /vethernet|wsl|hyper-v|virtualbox|vmware|docker|tailscale|zerotier|loopback/i;
const PREFERRED_IFACE_PATTERN = /wi-?fi|wlan|ethernet|eth\d|en\d/i;

/**
 * List local non-internal IPv4 addresses sorted by physical LAN likelihood,
 * optionally prioritizing subnets matching a peer address.
 * @param {string} [peerIp]
 * @returns {string[]}
 */
export function getLocalIPv4Addresses(peerIp = "") {
  const interfaces = os.networkInterfaces();
  const candidates = [];

  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;
    for (const addr of addrs) {
      if (addr.family !== "IPv4" || addr.internal) continue;
      if (addr.address.startsWith("169.254.")) continue; // APIPA link-local

      let score = 0;
      if (VIRTUAL_IFACE_PATTERN.test(name)) score -= 50;
      if (PREFERRED_IFACE_PATTERN.test(name)) score += 30;
      if (addr.address.startsWith("192.168.")) score += 20;
      else if (addr.address.startsWith("10.")) score += 10;

      if (peerIp) {
        const peerParts = peerIp.split(".");
        const addrParts = addr.address.split(".");
        if (peerParts[0] === addrParts[0] && peerParts[1] === addrParts[1]) {
          score += 100;
          if (peerParts[2] === addrParts[2]) {
            score += 100;
          }
        }
      }

      candidates.push({ address: addr.address, score });
    }
  }

  candidates.sort((a, b) => b.score - a.score);
  return [...new Set(candidates.map((c) => c.address))];
}

/**
 * Start a UDP LAN discovery responder so the Android app finds this PC without manual IP entry.
 * @param {{ wsPort: number, serverName?: string, discoveryPort?: number }} options
 */
export function startDiscoveryResponder({
  wsPort,
  serverName = os.hostname(),
  discoveryPort = DISCOVERY_PORT,
}) {
  const serverId = crypto
    .createHash("sha1")
    .update(`${serverName}:${os.userInfo().username}`)
    .digest("hex")
    .slice(0, 12);

  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  let bound = false;
  const ready = new Promise((resolve) => {
    socket.once("error", () => {
      // Discovery is best-effort; manual IP connection remains available if port is busy.
      resolve(false);
    });

    socket.bind(discoveryPort, "0.0.0.0", () => {
      bound = true;
      try {
        socket.setBroadcast(true);
      } catch {
        // Ignore if OS restricts broadcast flag.
      }
      resolve(true);
    });
  });

  socket.on("message", (msg, rinfo) => {
    if (msg.toString("utf8").trim() !== DISCOVERY_MAGIC) return;

    const ips = getLocalIPv4Addresses(rinfo.address);
    const payload = JSON.stringify({
      service: "leanback",
      version: PROTOCOL_VERSION,
      id: serverId,
      name: serverName,
      port: wsPort,
      ip: ips[0] ?? null,
      ips,
    });

    socket.send(payload, rinfo.port, rinfo.address);
  });

  return {
    serverId,
    ready,
    isBound: () => bound,
    close: () =>
      new Promise((resolve) => {
        try {
          socket.close(() => resolve());
        } catch {
          resolve();
        }
      }),
  };
}
