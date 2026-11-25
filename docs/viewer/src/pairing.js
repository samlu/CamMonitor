import { base64UrlDecode, deriveOtpKey, utf8Decode } from "./crypto.js";
import { logger } from "./logger.js";
import { MAX_PAIRED_CAMERAS } from "./storage.js";
import { sanitizeDeviceName } from "./ui.js";

export const PAIR_ATTEMPT_INTERVAL_MS = 1200;
export const PAIR_MAX_ATTEMPTS = 15;
export const PAIR_TIMEOUT_MS = 20_000;

export function parsePairingInput(raw) {
  if (!raw || typeof raw !== "string") return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;

  let jsonStr = null;
  if (trimmed.startsWith("{")) {
    jsonStr = trimmed;
  } else {
    const hashIdx = trimmed.indexOf("#pair=");
    let tokenStart = null;
    if (hashIdx >= 0) {
      tokenStart = trimmed.slice(hashIdx + "#pair=".length);
    } else if (trimmed.startsWith("pair=")) {
      tokenStart = trimmed.slice("pair=".length);
    } else {
      return null;
    }
    const match = tokenStart.match(/^[^\s&#]+/);
    if (!match || !match[0]) return null;
    const token = match[0].replace(/%3D/gi, "=");
    try {
      jsonStr = utf8Decode(base64UrlDecode(token));
    } catch (_) {
      return null;
    }
  }

  try {
    const obj = JSON.parse(jsonStr);
    if (!obj || typeof obj !== "object") return null;
    const deviceId = typeof obj.deviceId === "string" ? obj.deviceId.trim() : "";
    const name = typeof obj.name === "string" ? obj.name.trim() : "";
    const secret = typeof obj.secret === "string" ? obj.secret.trim() : "";
    if (!deviceId || !name || !secret) return null;
    const ver = typeof obj.ver === "number" && Number.isFinite(obj.ver) ? obj.ver : 1;
    const broker =
      typeof obj.broker === "string" && obj.broker.trim().length > 0 ? obj.broker.trim() : null;
    return { ver, deviceId, name, secret, broker };
  } catch (_) {
    return null;
  }
}

export function scrubPairHashFromUrl(
  loc = globalThis.location,
  hist = globalThis.history
) {
  if (!loc || typeof loc.hash !== "string") return null;
  const hash = loc.hash;
  if (!hash.includes("pair=")) return null;
  try {
    if (hist && typeof hist.replaceState === "function") {
      hist.replaceState(null, "", `${loc.pathname || "/"}${loc.search || ""}`);
    }
  } catch (_) {}
  return hash;
}

export async function runPairingHandshake({
  payload,
  storage,
  signaling,
  onStatus = () => {},
  attemptIntervalMs = PAIR_ATTEMPT_INTERVAL_MS,
  maxAttempts = PAIR_MAX_ATTEMPTS,
  timeoutMs = PAIR_TIMEOUT_MS
}) {
  if (!payload) {
    return { ok: false, reason: "INVALID_PAYLOAD" };
  }

  const existing = await storage.getCamera(payload.deviceId);
  if (!existing) {
    const all = await storage.listCameras();
    if (all.length >= MAX_PAIRED_CAMERAS) {
      return { ok: false, reason: "MAX_CAMERAS_REACHED", maxCameras: MAX_PAIRED_CAMERAS };
    }
  }

  const identity = await storage.getOrCreateIdentity();
  const otpKey = await deriveOtpKey(payload.secret);

  signaling.setActivePairingOtp(payload.secret, payload.deviceId);
  onStatus("CONNECTING_BROKER", payload);

  const connected = await signaling.startListening(payload.broker || null);
  if (!connected) {
    onStatus("BROKER_RETRYING", payload);
  }

  onStatus("AUTHENTICATING", payload);

  const pairRequestMsg = {
    type: "PAIR_REQUEST",
    pbDeviceId: identity.deviceId,
    pbName: identity.deviceName,
    pbPublicKeyBase64: identity.publicKeySpkiBase64
  };

  let cancelled = false;
  let retryTimer = null;
  let timeoutTimer = null;
  let unsubMsg = null;

  const cleanup = () => {
    cancelled = true;
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (timeoutTimer) {
      clearTimeout(timeoutTimer);
      timeoutTimer = null;
    }
    if (unsubMsg) {
      unsubMsg();
      unsubMsg = null;
    }
    signaling.clearPairingOtp();
  };

  return await new Promise((resolve) => {
    const finish = (res) => {
      if (cancelled) return;
      cleanup();
      resolve(res);
    };

    unsubMsg = signaling.onMessage(async ({ senderId, message }) => {
      if (cancelled) return;
      if (message?.type !== "PAIR_CONFIRM" || senderId !== payload.deviceId) return;
      if (message.paDeviceId !== senderId || !message.paPublicKeyBase64) {
        logger.w("Pairing", `Dropped malformed PAIR_CONFIRM from ${senderId}`);
        return;
      }

      const currentExisting = await storage.getCamera(message.paDeviceId);
      if (currentExisting && currentExisting.publicKeyBase64 !== message.paPublicKeyBase64) {
        logger.w(
          "Pairing",
          `Security guard: ${message.paDeviceId} is already bound to a different public key`
        );
        finish({ ok: false, reason: "PUBLIC_KEY_MISMATCH", alreadyPairedCamera: currentExisting });
        return;
      }

      const camRecord = {
        deviceId: message.paDeviceId,
        name: sanitizeDeviceName(message.paName || payload.name, "Camera"),
        publicKeyBase64: message.paPublicKeyBase64,
        pairedTimestamp: currentExisting?.pairedTimestamp ?? Date.now(),
        lastConnectedAt: currentExisting?.lastConnectedAt ?? null,
        connectDurationsMs: currentExisting?.connectDurationsMs ?? [],
        audioEnabled: currentExisting?.audioEnabled ?? false,
        nightModeSetting: currentExisting?.nightModeSetting ?? "AUTO"
      };

      const saved = await storage.saveCamera(camRecord);
      if (!saved) {
        finish({ ok: false, reason: "MAX_CAMERAS_REACHED", maxCameras: MAX_PAIRED_CAMERAS });
        return;
      }
      await storage.requestPersistentStorage();
      logger.i("Pairing", `Paired with camera ${camRecord.name} (${camRecord.deviceId})`);
      finish({ ok: true, camera: camRecord });
    });

    timeoutTimer = setTimeout(async () => {
      const existingCam = await storage.getCamera(payload.deviceId);
      logger.w("Pairing", `Pairing timed out for ${payload.name} (${payload.deviceId})`);
      finish({
        ok: false,
        reason: "TIMEOUT",
        alreadyPairedCamera: existingCam || null
      });
    }, timeoutMs);

    let attempts = 0;
    const sendAttempt = async () => {
      if (cancelled || attempts >= maxAttempts) return;
      attempts += 1;
      logger.i("Pairing", `Sending PAIR_REQUEST to ${payload.deviceId} (attempt #${attempts})`);
      await signaling.sendSignalingMessage(payload.deviceId, pairRequestMsg, otpKey);
      if (!cancelled && attempts < maxAttempts) {
        retryTimer = setTimeout(sendAttempt, attemptIntervalMs);
      }
    };

    sendAttempt().catch((err) => {
      logger.w("Pairing", `PAIR_REQUEST attempt failed: ${err.message}`);
    });
  });
}

export function createQrWebcamScanner({ videoEl, onDetected, onError }) {
  let stream = null;
  let rafId = null;
  let stopped = false;
  const canvas = typeof document !== "undefined" ? document.createElement("canvas") : null;
  const ctx = canvas ? canvas.getContext("2d", { willReadFrequently: true }) : null;

  function stop() {
    stopped = true;
    if (rafId !== null && typeof cancelAnimationFrame === "function") {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    if (stream) {
      for (const t of stream.getTracks()) {
        try {
          t.stop();
        } catch (_) {}
      }
      stream = null;
    }
    if (videoEl) {
      try {
        videoEl.srcObject = null;
      } catch (_) {}
    }
  }

  async function start() {
    if (!globalThis.navigator?.mediaDevices?.getUserMedia) {
      onError(new Error("Camera API unavailable"));
      return;
    }
    if (typeof globalThis.jsQR !== "function") {
      onError(new Error("Vendored jsQR library not loaded"));
      return;
    }
    stopped = false;
    try {
      stream = await globalThis.navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false
      });
      if (stopped) {
        stop();
        return;
      }
      videoEl.srcObject = stream;
      videoEl.setAttribute("playsinline", "true");
      videoEl.muted = true;
      await videoEl.play();

      const tick = () => {
        if (stopped) return;
        if (videoEl.readyState >= 2 && videoEl.videoWidth > 0 && canvas && ctx) {
          canvas.width = videoEl.videoWidth;
          canvas.height = videoEl.videoHeight;
          ctx.drawImage(videoEl, 0, 0, canvas.width, canvas.height);
          const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
          const code = globalThis.jsQR(imgData.data, imgData.width, imgData.height, {
            inversionAttempts: "dontInvert"
          });
          if (code && code.data) {
            const parsed = parsePairingInput(code.data);
            if (parsed) {
              stop();
              onDetected(parsed, code.data);
              return;
            }
          }
        }
        rafId = requestAnimationFrame(tick);
      };
      rafId = requestAnimationFrame(tick);
    } catch (err) {
      stop();
      onError(err);
    }
  }

  return { start, stop };
}
