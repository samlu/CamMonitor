import test from "node:test";
import assert from "node:assert/strict";
import { base64UrlEncode } from "../src/crypto.js";
import { parsePairingInput, runPairingHandshake, scrubPairHashFromUrl } from "../src/pairing.js";
import { createMemoryStore, MAX_PAIRED_CAMERAS } from "../src/storage.js";

test("parsePairingInput handles #pair= URLs, CJK, padded/percent-encoded tokens, bare tokens, and legacy JSON", () => {
  const payload = {
    ver: 1,
    deviceId: "cam-001",
    name: "客廳鏡頭 #1",
    secret: "abc123def456",
    broker: "broker.hivemq.com"
  };
  const b64Std = Buffer.from(JSON.stringify(payload), "utf8").toString("base64");
  const b64uPadded = b64Std.replace(/\+/g, "-").replace(/\//g, "_");
  const b64u = b64uPadded.replace(/=+$/, "");
  const url = `https://samlu.github.io/CamMonitor/viewer/#pair=${b64u}`;

  assert.deepEqual(parsePairingInput(url), payload);
  assert.deepEqual(parsePairingInput(`  ${url}\n`), payload);
  assert.deepEqual(parsePairingInput(`#pair=${b64u}`), payload);
  assert.deepEqual(parsePairingInput(`pair=${b64uPadded.replace(/=/g, "%3D")}`), payload);
  assert.deepEqual(parsePairingInput(JSON.stringify(payload)), payload);

  assert.equal(parsePairingInput(""), null);
  assert.equal(parsePairingInput("https://samlu.github.io/CamMonitor/viewer/"), null);
  assert.equal(parsePairingInput("#pair="), null);
  assert.equal(parsePairingInput("#pair=!!!"), null);
  assert.equal(parsePairingInput('{"deviceId":"x","name":"","secret":"s"}'), null);
});

test("scrubPairHashFromUrl immediately removes #pair= fragment via history.replaceState", () => {
  let replacedUrl = null;
  const fakeLoc = { pathname: "/CamMonitor/viewer/", search: "", hash: "#pair=abc123" };
  const fakeHist = {
    replaceState: (_state, _title, url) => {
      replacedUrl = url;
    }
  };
  const raw = scrubPairHashFromUrl(fakeLoc, fakeHist);
  assert.equal(raw, "#pair=abc123");
  assert.equal(replacedUrl, "/CamMonitor/viewer/");
});

function createFakeSignaling() {
  const listeners = new Set();
  const sent = [];
  let activeOtp = null;
  return {
    sent,
    setActivePairingOtp(otp) {
      activeOtp = otp;
    },
    clearPairingOtp() {
      activeOtp = null;
    },
    getOtp: () => activeOtp,
    onMessage(cb) {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    async startListening() {
      return true;
    },
    async sendSignalingMessage(recipientId, msg) {
      sent.push({ recipientId, msg });
      return true;
    },
    emit(senderId, message) {
      for (const cb of listeners) cb({ senderId, message });
    }
  };
}

test("runPairingHandshake pre-checks MAX_PAIRED_CAMERAS=8 before sending PAIR_REQUEST", async () => {
  const storage = createMemoryStore();
  for (let i = 0; i < MAX_PAIRED_CAMERAS; i++) {
    await storage.saveCamera({
      deviceId: `cam-${i}`,
      name: `Cam ${i}`,
      publicKeyBase64: `pub-${i}`
    });
  }
  const signaling = createFakeSignaling();
  const res = await runPairingHandshake({
    payload: { ver: 1, deviceId: "cam-9th", name: "9th", secret: "otp", broker: null },
    storage,
    signaling
  });
  assert.equal(res.ok, false);
  assert.equal(res.reason, "MAX_CAMERAS_REACHED");
  assert.equal(signaling.sent.length, 0);
});

test("runPairingHandshake enforces public key immutability guard for already-paired camera", async () => {
  const storage = createMemoryStore();
  await storage.saveCamera({
    deviceId: "cam-1",
    name: "Living Room",
    publicKeyBase64: "ORIGINAL_PUBKEY"
  });
  const baseSig = createFakeSignaling();
  const signaling = {
    ...baseSig,
    async sendSignalingMessage(recipientId, msg) {
      const ok = await baseSig.sendSignalingMessage(recipientId, msg);
      setTimeout(() => {
        baseSig.emit("cam-1", {
          type: "PAIR_CONFIRM",
          paDeviceId: "cam-1",
          paName: "Hijacked",
          paPublicKeyBase64: "DIFFERENT_PUBKEY"
        });
      }, 5);
      return ok;
    }
  };
  const res = await runPairingHandshake({
    payload: { ver: 1, deviceId: "cam-1", name: "Living Room", secret: "otp", broker: null },
    storage,
    signaling,
    attemptIntervalMs: 50,
    timeoutMs: 1000
  });
  assert.equal(res.ok, false);
  assert.equal(res.reason, "PUBLIC_KEY_MISMATCH");
  assert.equal((await storage.getCamera("cam-1")).publicKeyBase64, "ORIGINAL_PUBKEY");
});

test("runPairingHandshake succeeds on valid PAIR_CONFIRM and returns alreadyPairedCamera on timeout", async () => {
  const storage = createMemoryStore();
  const baseSig = createFakeSignaling();
  const signaling = {
    ...baseSig,
    async sendSignalingMessage(recipientId, msg) {
      const ok = await baseSig.sendSignalingMessage(recipientId, msg);
      if (recipientId === "cam-ok") {
        setTimeout(() => {
          baseSig.emit("cam-ok", {
            type: "PAIR_CONFIRM",
            paDeviceId: "cam-ok",
            paName: "Baby Cam",
            paPublicKeyBase64: "PUB_OK"
          });
        }, 5);
      }
      return ok;
    }
  };

  const okRes = await runPairingHandshake({
    payload: { ver: 1, deviceId: "cam-ok", name: "Nursery", secret: "otp-1", broker: null },
    storage,
    signaling,
    attemptIntervalMs: 30,
    timeoutMs: 1000
  });
  assert.equal(okRes.ok, true);
  assert.equal(okRes.camera.name, "Baby Cam");
  assert.equal(baseSig.getOtp(), null);

  // Re-click expired link for cam-ok -> timeout surfaces alreadyPairedCamera fallback
  const timeoutRes = await runPairingHandshake({
    payload: { ver: 1, deviceId: "cam-ok", name: "Nursery", secret: "expired", broker: null },
    storage,
    signaling: baseSig,
    attemptIntervalMs: 20,
    maxAttempts: 2,
    timeoutMs: 50
  });
  assert.equal(timeoutRes.ok, false);
  assert.equal(timeoutRes.reason, "TIMEOUT");
  assert.equal(timeoutRes.alreadyPairedCamera.deviceId, "cam-ok");
});
