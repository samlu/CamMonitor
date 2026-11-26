import { APP_CONFIG } from "./config.js";
import {
  createEnvelope,
  decryptAesGcm,
  deriveEcdhKey,
  deriveOtpKey,
  randomHex,
  utf8Decode,
  verifyEnvelopeSignature
} from "./crypto.js";
import { logger } from "./logger.js";

const PRIMARY_BROKER =
  APP_CONFIG.signaling.brokers.find((b) => b.primary) || APP_CONFIG.signaling.brokers[0];
const BROKERS_BY_HOST = new Map(
  APP_CONFIG.signaling.brokers.map((b) => [b.host.toLowerCase(), b])
);

export const SIGNALING_TOPIC_PREFIX = APP_CONFIG.signaling.topicPrefix;
export const DEFAULT_BROKER_URL = `wss://${PRIMARY_BROKER.host}:${PRIMARY_BROKER.wssPort}${PRIMARY_BROKER.wssPath}`;
export const ALLOWED_BROKER_HOSTS = new Set(BROKERS_BY_HOST.keys());
export const TIMESTAMP_TOLERANCE_MS = 120_000;
export const NONCE_RETENTION_MS = 120_000;

export function resolveBrokerUrl(brokerHint) {
  if (!brokerHint || typeof brokerHint !== "string") {
    return DEFAULT_BROKER_URL;
  }
  const trimmed = brokerHint.trim();
  if (!trimmed) return DEFAULT_BROKER_URL;

  try {
    if (trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) {
      const parsed = new URL(trimmed);
      if (parsed.protocol === "wss:" && ALLOWED_BROKER_HOSTS.has(parsed.hostname.toLowerCase())) {
        return trimmed;
      }
      return DEFAULT_BROKER_URL;
    }
    const hostOnly = trimmed.split(":")[0].toLowerCase();
    const matched = BROKERS_BY_HOST.get(hostOnly);
    if (matched) {
      return `wss://${matched.host}:${matched.wssPort}${matched.wssPath}`;
    }
  } catch (_) {}
  return DEFAULT_BROKER_URL;
}

export function isValidEnvelopeShape(env) {
  if (!env || typeof env !== "object") return false;
  return (
    typeof env.senderId === "string" &&
    env.senderId.length > 0 &&
    typeof env.recipientId === "string" &&
    env.recipientId.length > 0 &&
    typeof env.timestamp === "number" &&
    Number.isFinite(env.timestamp) &&
    typeof env.nonce === "string" &&
    env.nonce.length > 0 &&
    typeof env.ciphertext === "string" &&
    env.ciphertext.length > 0 &&
    typeof env.iv === "string" &&
    env.iv.length > 0 &&
    typeof env.tag === "string" &&
    env.tag.length > 0 &&
    typeof env.signature === "string" &&
    env.signature.length > 0
  );
}

/**
 * Pure, testable signaling envelope processor mirroring Android SecurityRepository + MqttSignalingClient.
 */
export function createEnvelopeProcessor({
  getIdentity,
  getPairedCamera,
  nowFn = () => Date.now(),
  allowPairRequest = false
}) {
  const seenNonces = new Map();
  const lastOfferTimestamps = new Map();
  let activeOtp = null;
  let activeOtpExpiresAt = 0;
  let expectedOtpPeerId = null;

  function cleanExpiredNonces(now) {
    for (const [nonce, ts] of seenNonces.entries()) {
      if (now - ts > NONCE_RETENTION_MS) {
        seenNonces.delete(nonce);
      }
    }
  }

  function setActivePairingOtp(otp, expectedPeerId = null, ttlMs = 5 * 60_000) {
    activeOtp = otp;
    activeOtpExpiresAt = nowFn() + ttlMs;
    expectedOtpPeerId = expectedPeerId;
  }

  function getActivePairingOtp() {
    if (activeOtp && nowFn() > activeOtpExpiresAt) {
      activeOtp = null;
      expectedOtpPeerId = null;
    }
    return activeOtp;
  }

  function clearPairingOtp() {
    activeOtp = null;
    activeOtpExpiresAt = 0;
    expectedOtpPeerId = null;
  }

  function nextOfferTimestamp(cameraDeviceId) {
    const now = nowFn();
    const prev = lastOfferTimestamps.get(cameraDeviceId) || 0;
    const next = now > prev ? now : prev + 1;
    lastOfferTimestamps.set(cameraDeviceId, next);
    return next;
  }

  async function processIncoming(rawJson) {
    let envelope;
    try {
      envelope = typeof rawJson === "string" ? JSON.parse(rawJson) : rawJson;
    } catch (_) {
      logger.w("Signaling", "Dropped malformed JSON envelope");
      return null;
    }
    if (!isValidEnvelopeShape(envelope)) {
      logger.w("Signaling", "Dropped incomplete signaling envelope");
      return null;
    }

    const identity = await getIdentity();
    if (!identity || envelope.recipientId !== identity.deviceId) {
      logger.w("Signaling", `Dropped envelope: recipientId mismatch (${envelope.recipientId})`);
      return null;
    }

    const now = nowFn();
    if (
      envelope.timestamp <= 0 ||
      envelope.timestamp < now - TIMESTAMP_TOLERANCE_MS ||
      envelope.timestamp > now + TIMESTAMP_TOLERANCE_MS
    ) {
      logger.w("Signaling", `Dropped envelope: timestamp out of 120s window (${envelope.timestamp})`);
      return null;
    }

    cleanExpiredNonces(now);
    if (seenNonces.has(envelope.nonce)) {
      logger.d("Signaling", `Dropped duplicate nonce ${envelope.nonce}`);
      return null;
    }
    seenNonces.set(envelope.nonce, envelope.timestamp);

    const pairedCam = await getPairedCamera(envelope.senderId);
    const otp = getActivePairingOtp();

    if (!pairedCam && !otp) {
      seenNonces.delete(envelope.nonce);
      logger.w("Signaling", `Dropped envelope from unauthorized sender: ${envelope.senderId}`);
      return null;
    }

    let decryptedJson = null;
    let usedOtpKey = false;

    if (pairedCam) {
      const sigOk = await verifyEnvelopeSignature(envelope, pairedCam.publicKeyBase64);
      if (sigOk) {
        try {
          const ecdhKey = await deriveEcdhKey(identity.ecdhPrivateKey, pairedCam.publicKeyBase64);
          const plainBytes = await decryptAesGcm(
            envelope.ciphertext,
            envelope.iv,
            envelope.tag,
            ecdhKey
          );
          decryptedJson = utf8Decode(plainBytes);
        } catch (err) {
          logger.w("Signaling", `ECDH decryption failed for ${envelope.senderId}: ${err.message}`);
        }
      } else {
        logger.w("Signaling", `ECDSA signature invalid for paired sender ${envelope.senderId}`);
      }
    }

    if (decryptedJson === null && otp) {
      try {
        const otpKey = await deriveOtpKey(otp);
        const plainBytes = await decryptAesGcm(
          envelope.ciphertext,
          envelope.iv,
          envelope.tag,
          otpKey
        );
        decryptedJson = utf8Decode(plainBytes);
        usedOtpKey = true;
      } catch (_) {
        decryptedJson = null;
      }
    }

    if (decryptedJson === null) {
      seenNonces.delete(envelope.nonce);
      logger.w("Signaling", `Verification/decryption failed for ${envelope.senderId}`);
      return null;
    }

    let message;
    try {
      message = JSON.parse(decryptedJson);
    } catch (_) {
      seenNonces.delete(envelope.nonce);
      return null;
    }
    if (!message || typeof message.type !== "string") {
      seenNonces.delete(envelope.nonce);
      return null;
    }

    if (usedOtpKey) {
      const isConfirm = message.type === "PAIR_CONFIRM";
      const isRequest = allowPairRequest && message.type === "PAIR_REQUEST";
      if (!isConfirm && !isRequest) {
        seenNonces.delete(envelope.nonce);
        logger.w("Signaling", `Dropped non-handshake OTP message [${message.type}]`);
        return null;
      }
      if (expectedOtpPeerId && envelope.senderId !== expectedOtpPeerId) {
        seenNonces.delete(envelope.nonce);
        logger.w("Signaling", `Dropped OTP handshake from unexpected peer ${envelope.senderId}`);
        return null;
      }
      const claimedId = isConfirm ? message.paDeviceId : message.pbDeviceId;
      const claimedPub = isConfirm ? message.paPublicKeyBase64 : message.pbPublicKeyBase64;
      if (claimedId !== envelope.senderId) {
        seenNonces.delete(envelope.nonce);
        logger.w("Signaling", `Handshake dropped: deviceId mismatch (${envelope.senderId} != ${claimedId})`);
        return null;
      }
      const popValid = claimedPub ? await verifyEnvelopeSignature(envelope, claimedPub) : false;
      if (!popValid) {
        seenNonces.delete(envelope.nonce);
        logger.w("Signaling", `Handshake dropped: Proof-of-Possession failed for ${envelope.senderId}`);
        return null;
      }
    }

    return { senderId: envelope.senderId, message, usedOtpKey };
  }

  return {
    setActivePairingOtp,
    getActivePairingOtp,
    clearPairingOtp,
    nextOfferTimestamp,
    processIncoming,
    _seenNoncesSize: () => seenNonces.size
  };
}

export function createSignalingClient({ storage, mqttConnectFn = null }) {
  const listeners = new Set();
  const connectionListeners = new Set();

  const processor = createEnvelopeProcessor({
    getIdentity: () => storage.getOrCreateIdentity(),
    getPairedCamera: (id) => storage.getCamera(id)
  });

  let activeClient = null;
  let clientGeneration = 0;
  let isListeningWanted = false;
  let isConnected = false;
  let currentBrokerUrl = DEFAULT_BROKER_URL;
  let reconnectTimer = null;
  let backoffMs = 2000;
  let connectPromise = null;

  function setConnected(val) {
    if (isConnected === val) return;
    isConnected = val;
    for (const cb of connectionListeners) {
      try {
        cb(val);
      } catch (_) {}
    }
  }

  function clearReconnectTimer() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  function scheduleReconnect() {
    if (!isListeningWanted || isConnected || reconnectTimer) return;
    const wait = backoffMs;
    backoffMs = Math.min(APP_CONFIG.signaling.maxReconnectBackoffMs, backoffMs * 2);
    logger.i("MQTT", `Scheduling reconnect in ${wait}ms...`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (isListeningWanted && !isConnected) {
        connectInternal().catch(() => {});
      }
    }, wait);
  }

  async function connectInternal() {
    if (!isListeningWanted) return false;
    if (isConnected && activeClient) return true;
    if (connectPromise) return connectPromise;

    const connectImpl = mqttConnectFn || globalThis.mqtt?.connect;
    if (typeof connectImpl !== "function") {
      throw new Error("Vendored mqtt.min.js client is not loaded");
    }

    connectPromise = (async () => {
      const identity = await storage.getOrCreateIdentity();
      const topic = `${SIGNALING_TOPIC_PREFIX}/${identity.deviceId}/rx`;
      const gen = ++clientGeneration;

      if (activeClient) {
        try {
          activeClient.end(true);
        } catch (_) {}
        activeClient = null;
      }

      const clientId = `cammonitor-web-${randomHex(4)}`;
      logger.i("MQTT", `Connecting to ${currentBrokerUrl} (gen=${gen}, topic=${topic})`);

      return await new Promise((resolve) => {
        let settled = false;
        let hasEverConnected = false;
        const finish = (ok) => {
          if (!settled) {
            settled = true;
            clearTimeout(timeoutId);
            resolve(ok);
          }
        };

        const client = connectImpl(currentBrokerUrl, {
          clientId,
          protocolVersion: 4,
          clean: true,
          keepalive: APP_CONFIG.signaling.keepAliveSec,
          reconnectPeriod: 0,
          connectTimeout: 8000
        });

        const timeoutId = setTimeout(() => {
          if (gen === clientGeneration && !settled) {
            logger.w("MQTT", `Connection timeout (gen=${gen})`);
            try {
              client.end(true);
            } catch (_) {}
            setConnected(false);
            finish(false);
          }
        }, 8500);

        client.on("connect", () => {
          if (gen !== clientGeneration) {
            try {
              client.end(true);
            } catch (_) {}
            return;
          }
          hasEverConnected = true;
          backoffMs = 2000;
          client.subscribe(topic, { qos: 1 }, (err) => {
            if (gen !== clientGeneration) return;
            if (err) {
              logger.e("MQTT", `Subscribe failed on ${topic}: ${err.message}`);
              try {
                client.end(true);
              } catch (_) {}
              setConnected(false);
              finish(false);
              return;
            }
            activeClient = client;
            setConnected(true);
            logger.i("MQTT", `Subscribed to ${topic} (gen=${gen})`);
            finish(true);
          });
        });

        client.on("message", (_t, payload) => {
          if (gen !== clientGeneration) return;
          const str = typeof payload === "string" ? payload : utf8Decode(new Uint8Array(payload));
          processor
            .processIncoming(str)
            .then((res) => {
              if (!res) return;
              for (const cb of listeners) {
                try {
                  cb(res);
                } catch (_) {}
              }
            })
            .catch((err) => {
              logger.e("MQTT", `Message handler error: ${err.message}`);
            });
        });

        const handleDrop = (reason) => {
          if (gen !== clientGeneration) return;
          if (activeClient === client) activeClient = null;
          setConnected(false);
          if (!settled) {
            logger.w("MQTT", `Connect failed (${reason}, gen=${gen})`);
            finish(false);
          } else if (hasEverConnected) {
            logger.w("MQTT", `Disconnected (${reason}, gen=${gen})`);
          }
          if (isListeningWanted) {
            scheduleReconnect();
          }
        };

        client.on("error", (err) => handleDrop(err?.message || "error"));
        client.on("close", () => handleDrop("close"));
        client.on("offline", () => handleDrop("offline"));
      });
    })();

    try {
      return await connectPromise;
    } finally {
      connectPromise = null;
    }
  }

  return {
    setActivePairingOtp: processor.setActivePairingOtp,
    clearPairingOtp: processor.clearPairingOtp,
    onMessage(cb) {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    onConnectionChange(cb) {
      connectionListeners.add(cb);
      return () => connectionListeners.delete(cb);
    },
    isConnected: () => isConnected,
    async startListening(brokerHint = null) {
      const nextUrl = resolveBrokerUrl(brokerHint);
      if (nextUrl !== currentBrokerUrl && activeClient) {
        await this.stopListening();
      }
      currentBrokerUrl = nextUrl;
      isListeningWanted = true;
      clearReconnectTimer();
      const ok = await connectInternal();
      if (!ok && isListeningWanted) {
        scheduleReconnect();
      }
      return ok;
    },
    async stopListening() {
      isListeningWanted = false;
      clearReconnectTimer();
      clientGeneration += 1;
      const client = activeClient;
      activeClient = null;
      setConnected(false);
      if (client) {
        try {
          client.end(true);
        } catch (_) {}
      }
      logger.i("MQTT", "Stopped MQTT signaling");
    },
    async ensureConnected(timeoutMs = 7000) {
      if (isConnected && activeClient) return true;
      isListeningWanted = true;
      const started = await Promise.race([
        this.startListening(currentBrokerUrl),
        new Promise((r) => setTimeout(() => r(false), timeoutMs))
      ]);
      return Boolean(started || (isConnected && activeClient));
    },
    async sendSignalingMessage(recipientId, messageObj, keyBytes, { isOffer = false } = {}) {
      const connected = (await this.ensureConnected(7000)) || (await this.ensureConnected(5000));
      if (!connected || !activeClient) {
        logger.w("MQTT", `Cannot send ${messageObj.type} to ${recipientId}: not connected`);
        return false;
      }
      const identity = await storage.getOrCreateIdentity();
      const timestamp = isOffer ? processor.nextOfferTimestamp(recipientId) : Date.now();
      const envelope = await createEnvelope({
        senderId: identity.deviceId,
        recipientId,
        plaintextJson: JSON.stringify(messageObj),
        keyBytes,
        ecdsaPrivateKey: identity.ecdsaPrivateKey,
        timestamp
      });
      const targetTopic = `${SIGNALING_TOPIC_PREFIX}/${recipientId}/rx`;
      const payloadStr = JSON.stringify(envelope);
      return await new Promise((resolve) => {
        try {
          activeClient.publish(targetTopic, payloadStr, { qos: 1 }, (err) => {
            if (err) {
              logger.w("MQTT", `Publish failed to ${targetTopic}: ${err.message}`);
              resolve(false);
            } else {
              resolve(true);
            }
          });
        } catch (err) {
          logger.w("MQTT", `Publish threw for ${targetTopic}: ${err.message}`);
          resolve(false);
        }
      });
    },
    handleNetworkOnline() {
      if (isListeningWanted && !isConnected) {
        backoffMs = 2000;
        clearReconnectTimer();
        connectInternal().catch(() => {});
      }
    },
    handlePageVisible() {
      if (isListeningWanted && (!isConnected || !activeClient)) {
        backoffMs = 2000;
        clearReconnectTimer();
        connectInternal().catch(() => {});
      }
    }
  };
}
