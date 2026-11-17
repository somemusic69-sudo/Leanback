import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { execFile } from "node:child_process";

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024; // 20 MB

/**
 * Inspect file header magic bytes so only genuine image formats are accepted.
 * @param {Buffer} buf
 * @returns {"png" | "jpg" | "gif" | "webp" | "bmp" | null}
 */
export function detectImageFormat(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 4) return null;

  // PNG: 89 50 4E 47 0D 0A 1A 0A
  if (
    buf.length >= 8 &&
    buf[0] === 0x89 &&
    buf[1] === 0x50 &&
    buf[2] === 0x4e &&
    buf[3] === 0x47 &&
    buf[4] === 0x0d &&
    buf[5] === 0x0a &&
    buf[6] === 0x1a &&
    buf[7] === 0x0a
  ) {
    return "png";
  }

  // JPEG: FF D8 FF
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    return "jpg";
  }

  // GIF: GIF87a / GIF89a
  if (buf.length >= 6 && buf.subarray(0, 4).toString("ascii") === "GIF8") {
    return "gif";
  }

  // WebP: RIFF....WEBP
  if (
    buf.length >= 12 &&
    buf.subarray(0, 4).toString("ascii") === "RIFF" &&
    buf.subarray(8, 12).toString("ascii") === "WEBP"
  ) {
    return "webp";
  }

  // BMP: BM
  if (buf[0] === 0x42 && buf[1] === 0x4d) {
    return "bmp";
  }

  return null;
}

function copyImageToWindowsClipboard(filePath) {
  return new Promise((resolve) => {
    if (process.platform !== "win32") {
      resolve(false);
      return;
    }

    const psScript = [
      "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;",
      "$src = [System.Drawing.Image]::FromFile($env:LEANBACK_IMG_PATH);",
      "try { $bmp = New-Object System.Drawing.Bitmap($src); try { [System.Windows.Forms.Clipboard]::SetImage($bmp) } finally { $bmp.Dispose() } } finally { $src.Dispose() }",
    ].join(" ");

    execFile(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-STA", "-Command", psScript],
      {
        env: { ...process.env, LEANBACK_IMG_PATH: filePath },
        timeout: 5000,
        windowsHide: true,
      },
      (err) => resolve(!err)
    );
  });
}

function buildSafeFilename(ext) {
  const now = new Date();
  const stamp = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0"),
    "-",
    String(now.getHours()).padStart(2, "0"),
    String(now.getMinutes()).padStart(2, "0"),
    String(now.getSeconds()).padStart(2, "0"),
  ].join("");
  const rand = crypto.randomBytes(2).toString("hex");
  return `leanback-${stamp}-${rand}.${ext}`;
}

/**
 * Validate and save an uploaded image into ~/Downloads/LeanBack, optionally copying to clipboard.
 * @param {Buffer} buffer
 * @param {{ saveDir?: string, copyToClipboard?: boolean }} [options]
 */
export async function saveUploadedImage(buffer, options = {}) {
  const ext = detectImageFormat(buffer);
  if (!ext) {
    const err = new Error("Unsupported or invalid image format");
    err.statusCode = 415;
    throw err;
  }

  const saveDir = options.saveDir ?? path.join(os.homedir(), "Downloads", "LeanBack");
  fs.mkdirSync(saveDir, { recursive: true });

  const filename = buildSafeFilename(ext);
  const filePath = path.join(saveDir, filename);
  fs.writeFileSync(filePath, buffer);

  let copied = false;
  if (options.copyToClipboard !== false && ext !== "webp") {
    copied = await copyImageToWindowsClipboard(filePath);
  }

  return {
    filename,
    filePath,
    bytes: buffer.length,
    ext,
    copied,
  };
}
