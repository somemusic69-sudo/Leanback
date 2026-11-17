#!/usr/bin/env node
import { createRequire } from "node:module";
import { DEFAULT_WS_PORT } from "../src/protocol.js";
import { TrustStore } from "../src/trustStore.js";
import { createInputDriver } from "../src/input/index.js";
import { createTerminalUI } from "../src/ui.js";
import { startServer } from "../src/server.js";
import { getLocalIPv4Addresses } from "../src/discovery.js";

const require = createRequire(import.meta.url);
const pkg = require("../package.json");

function printHelp() {
  console.log(`
  LeanBack v${pkg.version} — Control your PC mouse & keyboard from your phone

  Usage:
    leanback [options]
    leanback devices          List trusted phones
    leanback revoke <id>      Revoke trust for a phone (ID or prefix)
    leanback reset            Forget all trusted phones

  Options:
    -p, --port <port>         WebSocket port (default: ${DEFAULT_WS_PORT})
    -s, --speed <multiplier>  Cursor speed multiplier, 0.2 - 5.0 (default: 1.0)
        --always-ask          Always ask [Y/n] on PC before allowing control
        --no-discovery        Disable UDP LAN auto-discovery
        --no-clipboard        Save uploaded images without copying to clipboard
    -v, --version             Show version
    -h, --help                Show this help
`);
}

function parseArgs(argv) {
  const args = argv.slice(2);
  const opts = {
    command: "serve",
    target: null,
    port: DEFAULT_WS_PORT,
    speed: 1.0,
    alwaysAsk: false,
    discovery: true,
    clipboard: true,
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "-h" || arg === "--help") {
      opts.command = "help";
      return opts;
    }
    if (arg === "-v" || arg === "--version") {
      opts.command = "version";
      return opts;
    }
    if (arg === "--always-ask") {
      opts.alwaysAsk = true;
      continue;
    }
    if (arg === "--no-discovery") {
      opts.discovery = false;
      continue;
    }
    if (arg === "--no-clipboard") {
      opts.clipboard = false;
      continue;
    }
    if (arg === "-p" || arg === "--port") {
      const next = Number(args[++i]);
      if (!Number.isInteger(next) || next < 1 || next > 65535) {
        throw new Error(`Invalid port value: ${args[i] ?? ""}`);
      }
      opts.port = next;
      continue;
    }
    if (arg === "-s" || arg === "--speed") {
      const next = Number(args[++i]);
      if (!Number.isFinite(next) || next < 0.2 || next > 5.0) {
        throw new Error(`Invalid speed multiplier (expected 0.2 - 5.0): ${args[i] ?? ""}`);
      }
      opts.speed = next;
      continue;
    }
    if (!arg.startsWith("-") && opts.command === "serve") {
      if (arg === "devices" || arg === "reset" || arg === "revoke") {
        opts.command = arg;
        if (arg === "revoke") {
          opts.target = args[++i] ?? null;
        }
        continue;
      }
    }
    throw new Error(`Unknown argument: ${arg}`);
  }
  return opts;
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv);
  } catch (err) {
    console.error(`Error: ${err.message}`);
    printHelp();
    process.exitCode = 1;
    return;
  }

  if (opts.command === "help") {
    printHelp();
    return;
  }
  if (opts.command === "version") {
    console.log(pkg.version);
    return;
  }

  const trustStore = new TrustStore();

  if (opts.command === "devices") {
    const devices = trustStore.list();
    if (devices.length === 0) {
      console.log("No trusted devices saved.");
      return;
    }
    console.log("\nTrusted devices:");
    for (const d of devices) {
      console.log(`  - ${d.deviceName}  (id: ${d.deviceId.slice(0, 8)}..., last seen: ${d.lastSeen})`);
    }
    console.log("");
    return;
  }

  if (opts.command === "revoke") {
    if (!opts.target) {
      console.error("Usage: leanback revoke <deviceId-or-prefix>");
      process.exitCode = 1;
      return;
    }
    const removed = trustStore.revoke(opts.target);
    console.log(removed > 0 ? `Revoked ${removed} device(s).` : "No matching device found.");
    return;
  }

  if (opts.command === "reset") {
    const count = trustStore.clear();
    console.log(`Cleared ${count} trusted device(s).`);
    return;
  }

  const driver = createInputDriver({ speed: opts.speed });
  const ips = getLocalIPv4Addresses();
  const ui = createTerminalUI({
    port: opts.port,
    ips,
    alwaysAsk: opts.alwaysAsk,
    version: pkg.version,
  });

  const server = await startServer({
    port: opts.port,
    alwaysAsk: opts.alwaysAsk,
    enableDiscovery: opts.discovery,
    copyImagesToClipboard: opts.clipboard,
    driver,
    trustStore,
    approver: ui.requestApproval,
    onConnected: ui.connected,
    onDisconnected: ui.disconnected,
    onImageReceived: ui.imageReceived,
  });

  ui.printBanner();

  let shuttingDown = false;
  const shutdown = async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log("\n  Shutting down LeanBack...");
    ui.close();
    await server.close();
    process.exit(0);
  };

  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main().catch((err) => {
  console.error(`Fatal error: ${err?.message || err}`);
  process.exit(1);
});
