import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createEnvelope, deriveEcdhKey, deriveOtpKey, generateIdentityKeyPair } from "../src/crypto.js";
import {
  createEnvelopeProcessor,
  createSignalingClient,
  DEFAULT_BROKER_URL,
  isClientHealthy,
  resolveBrokerUrl,
  TIMESTAMP_TOLERANCE_MS
} from "../src/signaling.js";

test("resolveBrokerUrl defaults to hivemq and avoids unsolicited emqx failover", () => {
  assert.equal(resolveBrokerUrl(null), DEFAULT_BROKER_URL);
  assert.equal(resolveBrokerUrl(""), DEFAULT_BROKER_URL);
  assert.equal(resolveBrokerUrl("broker.hivemq.com"), "wss://broker.hivemq.com:8884/mqtt");
  assert.equal(resolveBrokerUrl("broker.emqx.io"), "wss://broker.emqx.io:8884/mqtt");
  assert.equal(resolveBrokerUrl("evil.example.com"), DEFAULT_BROKER_URL);
  assert.equal(resolveBrokerUrl("ws://broker.emqx.io:8083/mqtt"), DEFAULT_BROKER_URL);
});

async function setupPair(now = 1_764_000_000_000) {
  const pa = await generateIdentityKeyPair();
  const pb = await generateIdentityKeyPair();
  const paId = "pa-1";
  const pbId = "pb-1";
  const cameras = new Map();
  let currentTime = now;
  const processor = createEnvelopeProcessor({
    getIdentity: async () => ({
      deviceId: pbId,
      ecdsaPrivateKey: pb.ecdsaPrivateKey,
      ecdhPrivateKey: pb.ecdhPrivateKey
    }),
    getPairedCamera: async (id) => cameras.get(id) || null,
    nowFn: () => currentTime
  });
  return {
    pa,
    pb,
    paId,
    pbId,
    cameras,
    processor,
    setNow: (v) => {
      currentTime = v;
    },
    getNow: () => currentTime
  };
}

test("EnvelopeProcessor rejects malformed JSON, missing fields, and recipientId mismatch", async () => {
  const { pa, paId, processor, getNow } = await setupPair();
  assert.equal(await processor.processIncoming("{bad json"), null);
  assert.equal(await processor.processIncoming(JSON.stringify({ senderId: paId })), null);

  const env = await createEnvelope({
    senderId: paId,
    recipientId: "wrong-recipient",
    plaintextJson: JSON.stringify({ type: "OFFER", sdp: "x" }),
    keyBytes: new Uint8Array(32).fill(1),
    ecdsaPrivateKey: pa.ecdsaPrivateKey,
    timestamp: getNow()
  });
  assert.equal(await processor.processIncoming(env), null);
});

test("EnvelopeProcessor enforces +-120s timestamp window and nonce deduplication with TTL", async () => {
  const ctx = await setupPair();
  ctx.cameras.set(ctx.paId, { deviceId: ctx.paId, publicKeyBase64: ctx.pa.publicKeySpkiBase64 });
  const key = await deriveEcdhKey(ctx.pa.ecdhPrivateKey, ctx.pb.publicKeySpkiBase64);

  const expiredEnv = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({ type: "ANSWER", sdp: "old" }),
    keyBytes: key,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow() - TIMESTAMP_TOLERANCE_MS - 1
  });
  assert.equal(await ctx.processor.processIncoming(expiredEnv), null);

  const futureEnv = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({ type: "ANSWER", sdp: "future" }),
    keyBytes: key,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow() + TIMESTAMP_TOLERANCE_MS + 1
  });
  assert.equal(await ctx.processor.processIncoming(futureEnv), null);

  const validEnv = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({ type: "ANSWER", sdp: "ok" }),
    keyBytes: key,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow()
  });
  assert.ok(await ctx.processor.processIncoming(validEnv));
  // Duplicate nonce rejected
  assert.equal(await ctx.processor.processIncoming(validEnv), null);

  // After >120s nonce TTL, cleanOldNonces purges old nonce
  ctx.setNow(ctx.getNow() + TIMESTAMP_TOLERANCE_MS + 5000);
  const validEnv2 = { ...validEnv, timestamp: ctx.getNow() };
  // Signature changed because timestamp changed -> fails sig and rolls back nonce
  assert.equal(await ctx.processor.processIncoming(validEnv2), null);
});

test("EnvelopeProcessor rolls back nonce on signature/decrypt failure so spoofed packets cannot poison nonces", async () => {
  const ctx = await setupPair();
  ctx.cameras.set(ctx.paId, { deviceId: ctx.paId, publicKeyBase64: ctx.pa.publicKeySpkiBase64 });
  const key = await deriveEcdhKey(ctx.pa.ecdhPrivateKey, ctx.pb.publicKeySpkiBase64);

  const validEnv = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({ type: "ANSWER", sdp: "ok" }),
    keyBytes: key,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow()
  });

  // Attacker sends tampered envelope reusing the same nonce
  const tampered = { ...validEnv, signature: "AAAA" };
  assert.equal(await ctx.processor.processIncoming(tampered), null);
  assert.equal(ctx.processor._seenNoncesSize(), 0);

  // Legitimate packet with that nonce still succeeds!
  const res = await ctx.processor.processIncoming(validEnv);
  assert.ok(res);
  assert.equal(res.message.sdp, "ok");
});

test("EnvelopeProcessor enforces OTP message-type gating and Proof-of-Possession on PAIR_CONFIRM", async () => {
  const ctx = await setupPair();
  const otp = "super-secret-otp";
  const otpKey = await deriveOtpKey(otp);
  ctx.processor.setActivePairingOtp(otp, ctx.paId);

  // Non-handshake message encrypted with OTP key must be dropped
  const nonHandshake = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({ type: "ANSWER", sdp: "sneaky" }),
    keyBytes: otpKey,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow()
  });
  assert.equal(await ctx.processor.processIncoming(nonHandshake), null);

  // PAIR_CONFIRM signed by attacker key claiming pa's public key fails PoP
  const attacker = await generateIdentityKeyPair();
  const spoofConfirm = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({
      type: "PAIR_CONFIRM",
      paDeviceId: ctx.paId,
      paName: "Fake Cam",
      paPublicKeyBase64: ctx.pa.publicKeySpkiBase64
    }),
    keyBytes: otpKey,
    ecdsaPrivateKey: attacker.ecdsaPrivateKey,
    timestamp: ctx.getNow()
  });
  assert.equal(await ctx.processor.processIncoming(spoofConfirm), null);

  // Genuine PAIR_CONFIRM signed by PA passes PoP
  const validConfirm = await createEnvelope({
    senderId: ctx.paId,
    recipientId: ctx.pbId,
    plaintextJson: JSON.stringify({
      type: "PAIR_CONFIRM",
      paDeviceId: ctx.paId,
      paName: "Real Cam",
      paPublicKeyBase64: ctx.pa.publicKeySpkiBase64
    }),
    keyBytes: otpKey,
    ecdsaPrivateKey: ctx.pa.ecdsaPrivateKey,
    timestamp: ctx.getNow()
  });
  const ok = await ctx.processor.processIncoming(validConfirm);
  assert.ok(ok);
  assert.equal(ok.usedOtpKey, true);
  assert.equal(ok.message.paName, "Real Cam");
});

test("nextOfferTimestamp is strictly monotonic even if clock does not advance or steps back", async () => {
  const ctx = await setupPair(5000);
  const t1 = ctx.processor.nextOfferTimestamp(ctx.paId);
  const t2 = ctx.processor.nextOfferTimestamp(ctx.paId);
  ctx.setNow(4000); // clock stepped backwards
  const t3 = ctx.processor.nextOfferTimestamp(ctx.paId);
  assert.equal(t1, 5000);
  assert.equal(t2, 5001);
  assert.equal(t3, 5002);
});

test("isClientHealthy detects disconnected, disconnecting, and closed WebSocket clients", () => {
  assert.equal(isClientHealthy(null), false);
  assert.equal(isClientHealthy({ connected: false }), false);
  assert.equal(isClientHealthy({ connected: true, disconnecting: true }), false);
  assert.equal(isClientHealthy({ connected: true, stream: { socket: { readyState: 3 } } }), false);
  assert.equal(isClientHealthy({ connected: true, stream: { socket: { readyState: 1 } } }), true);
  assert.equal(isClientHealthy({}), true);
});

test("stopListening during in-flight MQTT connect cancels cleanly without deadlocking connectPromise", async () => {
  const pb = await generateIdentityKeyPair();
  const storage = {
    getOrCreateIdentity: async () => ({
      deviceId: "pb-deadlock",
      ecdsaPrivateKey: pb.ecdsaPrivateKey,
      ecdhPrivateKey: pb.ecdhPrivateKey
    }),
    getCamera: async () => null
  };

  let connectCalls = 0;
  const clients = [];
  const mqttConnectFn = () => {
    connectCalls += 1;
    const c = new EventEmitter();
    c.connected = false;
    c.ended = false;
    c.end = () => {
      c.ended = true;
      c.connected = false;
    };
    c.subscribe = (_t, _o, cb) => cb(null);
    c.publish = (_t, _p, _o, cb) => cb(null);
    clients.push(c);
    // Client #2 connects immediately; Client #1 stays hanging
    if (connectCalls === 2) {
      setTimeout(() => {
        c.connected = true;
        c.emit("connect");
      }, 10);
    }
    return c;
  };

  const sig = createSignalingClient({ storage, mqttConnectFn, connectTimeoutMs: 500 });
  const p1 = sig.startListening();
  await new Promise((r) => setTimeout(r, 15));
  await sig.stopListening();

  // First connect must settle to false cleanly and client #1 must be ended
  assert.equal(await p1, false);
  assert.equal(clients[0].ended, true);

  // Subsequent connect must open client #2 immediately (no wedged connectPromise)
  const ok2 = await sig.startListening();
  assert.equal(ok2, true);
  assert.equal(connectCalls, 2);
  assert.equal(sig.isConnected(), true);
  await sig.stopListening();
});

test("QoS-1 publish timeout tears down zombie socket, force-reconnects, and retries once", async () => {
  const pb = await generateIdentityKeyPair();
  const storage = {
    getOrCreateIdentity: async () => ({
      deviceId: "pb-pub",
      ecdsaPrivateKey: pb.ecdsaPrivateKey,
      ecdhPrivateKey: pb.ecdhPrivateKey
    }),
    getCamera: async () => null
  };

  let connectCalls = 0;
  const publishCalls = [];
  const mqttConnectFn = () => {
    connectCalls += 1;
    const gen = connectCalls;
    const c = new EventEmitter();
    c.connected = false;
    c.end = () => {
      c.connected = false;
    };
    c.subscribe = (_t, _o, cb) => cb(null);
    c.publish = (topic, _payload, _opts, cb) => {
      publishCalls.push({ gen, topic });
      if (gen === 1) {
        // Simulate zombie half-open socket: never invokes PUBACK callback
        return;
      }
      cb(null);
    };
    setTimeout(() => {
      c.connected = true;
      c.emit("connect");
    }, 5);
    return c;
  };

  const sig = createSignalingClient({
    storage,
    mqttConnectFn,
    publishTimeoutMs: 40,
    connectTimeoutMs: 500
  });
  assert.equal(await sig.startListening(), true);
  const sent = await sig.sendSignalingMessage(
    "pa-target",
    { type: "OFFER", sdp: "v=0" },
    new Uint8Array(32).fill(7),
    { isOffer: true }
  );
  assert.equal(sent, true);
  assert.equal(connectCalls, 2);
  assert.equal(publishCalls.length, 2);
  assert.equal(publishCalls[0].gen, 1);
  assert.equal(publishCalls[1].gen, 2);
  await sig.stopListening();
});

test("handlePageVisible refreshes stale socket after long hidden duration and resumes after pagehide", async () => {
  const pb = await generateIdentityKeyPair();
  let now = 1_000_000;
  const storage = {
    getOrCreateIdentity: async () => ({
      deviceId: "pb-vis",
      ecdsaPrivateKey: pb.ecdsaPrivateKey,
      ecdhPrivateKey: pb.ecdhPrivateKey
    }),
    getCamera: async () => null
  };

  let connectCalls = 0;
  const mqttConnectFn = () => {
    connectCalls += 1;
    const c = new EventEmitter();
    c.connected = false;
    c.end = () => {
      c.connected = false;
    };
    c.subscribe = (_t, _o, cb) => cb(null);
    setTimeout(() => {
      c.connected = true;
      c.emit("connect");
    }, 5);
    return c;
  };

  const sig = createSignalingClient({
    storage,
    mqttConnectFn,
    hiddenRefreshMs: 10_000,
    nowFn: () => now
  });
  assert.equal(await sig.startListening(), true);
  assert.equal(connectCalls, 1);

  // Hidden for 15s -> returning visible forces fresh socket even though client still thinks it's connected
  sig.handlePageHidden();
  now += 15_000;
  sig.handlePageVisible();
  await new Promise((r) => setTimeout(r, 25));
  assert.equal(connectCalls, 2);

  // pagehide calls stopListening(); returning with resumeIfWanted: true restores MQTT
  await sig.stopListening();
  assert.equal(sig.isConnected(), false);
  sig.handlePageVisible({ resumeIfWanted: true });
  await new Promise((r) => setTimeout(r, 25));
  assert.equal(connectCalls, 3);
  assert.equal(sig.isConnected(), true);
  await sig.stopListening();
});
