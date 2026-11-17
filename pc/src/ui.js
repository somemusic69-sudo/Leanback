import readline from "node:readline/promises";
import os from "node:os";

const useColor = process.stdout.isTTY && !process.env.NO_COLOR;
const c = {
  reset: useColor ? "\x1b[0m" : "",
  bold: useColor ? "\x1b[1m" : "",
  dim: useColor ? "\x1b[2m" : "",
  green: useColor ? "\x1b[32m" : "",
  yellow: useColor ? "\x1b[33m" : "",
  cyan: useColor ? "\x1b[36m" : "",
  red: useColor ? "\x1b[31m" : "",
};

/**
 * Interactive terminal UI for LeanBack PC server.
 */
export function createTerminalUI({ port, ips, alwaysAsk, version = "1.0.0" }) {
  let rl = null;
  let promptChain = Promise.resolve();

  function ensureRl() {
    if (!rl && process.stdin.isTTY) {
      rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout,
      });
    }
    return rl;
  }

  function printBanner() {
    const primaryIp = ips[0] ?? "127.0.0.1";
    const altIps = ips.slice(1);
    console.log("");
    console.log(`${c.bold}${c.cyan}  LeanBack v${version}${c.reset} ${c.dim}— phone touchpad & keyboard for your PC${c.reset}`);
    console.log(`${c.dim}  ──────────────────────────────────────────────────────${c.reset}`);
    console.log(`  PC Name    : ${c.bold}${os.hostname()}${c.reset}`);
    console.log(`  Address    : ${c.bold}${c.green}${primaryIp}:${port}${c.reset}`);
    if (altIps.length > 0) {
      console.log(`  Other IPs  : ${c.dim}${altIps.map((ip) => `${ip}:${port}`).join(", ")}${c.reset}`);
    }
    console.log(
      `  Trust Mode : ${alwaysAsk ? `${c.yellow}Always ask on connect (--always-ask)${c.reset}` : "Accept once, auto-reconnect later"}`
    );
    console.log(`${c.dim}  ──────────────────────────────────────────────────────${c.reset}`);
    console.log(`  Open ${c.bold}LeanBack${c.reset} on your phone -> Settings -> tap this PC.`);
    console.log(`  ${c.dim}Press Ctrl+C to stop.${c.reset}\n`);
  }

  function info(message) {
    console.log(`  ${c.dim}[${timeTag()}]${c.reset} ${message}`);
  }

  function connected(deviceName, remoteIp, trusted) {
    const badge = trusted ? `${c.green}trusted${c.reset}` : `${c.yellow}session only${c.reset}`;
    console.log(`  ${c.green}●${c.reset} Connected: ${c.bold}${deviceName}${c.reset} ${c.dim}(${remoteIp})${c.reset} [${badge}]`);
  }

  function disconnected(deviceName, reason) {
    const suffix = reason ? ` ${c.dim}(${reason})${c.reset}` : "";
    console.log(`  ${c.dim}○ Disconnected: ${deviceName}${suffix}${c.reset}`);
  }

  /**
   * Prompt the user on the PC terminal to accept/deny an incoming phone connection.
   * Serialized so multiple concurrent requests never overlap.
   * @param {{ deviceName: string, remoteIp: string, code: string, signal: AbortSignal }} req
   * @returns {Promise<"trust" | "session" | "deny">}
   */
  function requestApproval(req) {
    const task = promptChain.then(() => runPrompt(req));
    promptChain = task.catch(() => {});
    return task;
  }

  async function runPrompt({ deviceName, remoteIp, code, signal }) {
    if (signal.aborted) return "deny";

    const iface = ensureRl();
    if (!iface) {
      console.log(`  ${c.red}✖ Denied "${deviceName}" (${remoteIp}) — no interactive terminal.${c.reset}`);
      return "deny";
    }

    // Ring terminal bell gently to draw attention when phone requests access.
    process.stdout.write("\x07");
    console.log("");
    console.log(
      `  ${c.yellow}? Incoming connection:${c.reset} ${c.bold}${deviceName}${c.reset} ${c.dim}(${remoteIp})${c.reset}  Code: ${c.bold}${c.cyan}${code}${c.reset}`
    );

    try {
      const answer = await iface.question(
        `    Allow control? ${c.bold}[Y]${c.reset}es & remember / ${c.bold}[s]${c.reset}ession only / ${c.bold}[n]${c.reset}o: `,
        { signal }
      );
      const trimmed = answer.trim().toLowerCase();
      if (trimmed === "" || trimmed === "y" || trimmed === "yes") {
        return "trust";
      }
      if (trimmed === "s" || trimmed === "session") {
        return "session";
      }
      console.log(`  ${c.red}✖ Connection denied.${c.reset}`);
      return "deny";
    } catch (err) {
      if (err?.name === "AbortError") {
        console.log(`\n  ${c.dim}✖ Request cancelled (${deviceName} disconnected).${c.reset}`);
        return "deny";
      }
      return "deny";
    }
  }

  function close() {
    if (rl) {
      rl.close();
      rl = null;
    }
  }

  return {
    printBanner,
    info,
    connected,
    disconnected,
    requestApproval,
    close,
  };
}

function timeTag() {
  return new Date().toTimeString().slice(0, 8);
}
