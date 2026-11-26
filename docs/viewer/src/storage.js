import { APP_CONFIG } from "./config.js";
import { generateIdentityKeyPair, importPrivateKeysFromPkcs8, randomUuid } from "./crypto.js";
import { sanitizeDeviceName } from "./ui.js";

export const MAX_PAIRED_CAMERAS = APP_CONFIG.limits.maxPairedCameras;
export const MAX_CONNECT_DURATION_SAMPLES = 3;
export const MIN_VALID_CONNECT_DURATION_MS = 100;
export const MAX_VALID_CONNECT_DURATION_MS = 30_000;

const DB_NAME = "cammonitor_web_db";
const DB_VERSION = 1;
const STORE_IDENTITY = "identity";
const STORE_CAMERAS = "paired_cameras";
const STORE_SETTINGS = "settings";
const IDENTITY_KEY = "self";

export function updateConnectDurationSamples(existing, newDurationMs) {
  const clamped = Math.max(
    MIN_VALID_CONNECT_DURATION_MS,
    Math.min(MAX_VALID_CONNECT_DURATION_MS, Math.round(Number(newDurationMs) || MIN_VALID_CONNECT_DURATION_MS))
  );
  const list = Array.isArray(existing) ? existing.slice() : [];
  list.push(clamped);
  return list.slice(-MAX_CONNECT_DURATION_SAMPLES);
}

export function computeTypicalConnectMs(samples) {
  if (!Array.isArray(samples) || samples.length === 0) return null;
  const sum = samples.reduce((acc, v) => acc + v, 0);
  return Math.round(sum / samples.length);
}

function openIdb() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("IndexedDB unavailable"));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_IDENTITY)) {
        db.createObjectStore(STORE_IDENTITY, { keyPath: "id" });
      }
      if (!db.objectStoreNames.contains(STORE_CAMERAS)) {
        db.createObjectStore(STORE_CAMERAS, { keyPath: "deviceId" });
      }
      if (!db.objectStoreNames.contains(STORE_SETTINGS)) {
        db.createObjectStore(STORE_SETTINGS, { keyPath: "key" });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("Failed to open IndexedDB"));
  });
}

function idbTx(db, storeName, mode, fn) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    let req;
    try {
      req = fn(store);
    } catch (err) {
      reject(err);
      return;
    }
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("IndexedDB operation failed"));
  });
}

async function resolveIdentityHandles(record) {
  if (record.ecdsaPrivateKey && record.ecdhPrivateKey) {
    return record;
  }
  if (record.privateKeyPkcs8) {
    const { ecdsaPrivateKey, ecdhPrivateKey } = await importPrivateKeysFromPkcs8(record.privateKeyPkcs8);
    return {
      ...record,
      ecdsaPrivateKey,
      ecdhPrivateKey
    };
  }
  return null;
}

export function createMemoryStore() {
  let identity = null;
  const cameras = new Map();
  const settings = new Map();

  return {
    async getOrCreateIdentity(defaultName = "Web Viewer") {
      if (identity) return identity;
      const kp = await generateIdentityKeyPair({ keepPkcs8: false });
      identity = {
        id: IDENTITY_KEY,
        deviceId: randomUuid(),
        deviceName: sanitizeDeviceName(defaultName, "Web Viewer"),
        ecdsaPrivateKey: kp.ecdsaPrivateKey,
        ecdhPrivateKey: kp.ecdhPrivateKey,
        publicKeySpkiBase64: kp.publicKeySpkiBase64,
        createdAt: Date.now()
      };
      return identity;
    },
    async setDeviceName(name) {
      const id = await this.getOrCreateIdentity();
      identity = { ...id, deviceName: sanitizeDeviceName(name, id.deviceName) };
      return identity;
    },
    async listCameras() {
      return Array.from(cameras.values()).sort((a, b) => (b.lastConnectedAt || 0) - (a.lastConnectedAt || 0) || (b.pairedTimestamp || 0) - (a.pairedTimestamp || 0));
    },
    async getCamera(deviceId) {
      return cameras.get(deviceId) || null;
    },
    async saveCamera(cam) {
      const exists = cameras.has(cam.deviceId);
      if (!exists && cameras.size >= MAX_PAIRED_CAMERAS) {
        return false;
      }
      const prev = cameras.get(cam.deviceId);
      cameras.set(cam.deviceId, {
        deviceId: cam.deviceId,
        name: sanitizeDeviceName(cam.name, "Camera"),
        publicKeyBase64: cam.publicKeyBase64,
        pairedTimestamp: cam.pairedTimestamp ?? prev?.pairedTimestamp ?? Date.now(),
        lastConnectedAt: cam.lastConnectedAt ?? prev?.lastConnectedAt ?? null,
        connectDurationsMs: cam.connectDurationsMs ?? prev?.connectDurationsMs ?? [],
        audioEnabled: cam.audioEnabled ?? prev?.audioEnabled ?? false,
        nightModeSetting: cam.nightModeSetting ?? prev?.nightModeSetting ?? "AUTO"
      });
      return true;
    },
    async updateCamera(deviceId, patch) {
      const prev = cameras.get(deviceId);
      if (!prev) return null;
      const next = { ...prev, ...patch, deviceId };
      cameras.set(deviceId, next);
      return next;
    },
    async deleteCamera(deviceId) {
      cameras.delete(deviceId);
    },
    async recordConnectDuration(deviceId, durationMs, connectedAt = Date.now()) {
      const prev = cameras.get(deviceId);
      if (!prev) return null;
      const next = {
        ...prev,
        lastConnectedAt: connectedAt,
        connectDurationsMs: updateConnectDurationSamples(prev.connectDurationsMs, durationMs)
      };
      cameras.set(deviceId, next);
      return next;
    },
    async getSetting(key, fallback = null) {
      return settings.has(key) ? settings.get(key) : fallback;
    },
    async setSetting(key, value) {
      settings.set(key, value);
    },
    async requestPersistentStorage() {
      return false;
    }
  };
}

export function createIndexedDbStore() {
  let dbPromise = null;
  let cachedIdentity = null;

  async function db() {
    if (!dbPromise) {
      dbPromise = openIdb();
    }
    return dbPromise;
  }

  return {
    async getOrCreateIdentity(defaultName = "Web Viewer") {
      if (cachedIdentity) return cachedIdentity;
      const database = await db();
      const existing = await idbTx(database, STORE_IDENTITY, "readonly", (s) => s.get(IDENTITY_KEY));
      if (existing) {
        const resolved = await resolveIdentityHandles(existing);
        if (resolved) {
          cachedIdentity = resolved;
          return resolved;
        }
      }

      const kp = await generateIdentityKeyPair({ keepPkcs8: false });
      const record = {
        id: IDENTITY_KEY,
        deviceId: randomUuid(),
        deviceName: sanitizeDeviceName(defaultName, "Web Viewer"),
        ecdsaPrivateKey: kp.ecdsaPrivateKey,
        ecdhPrivateKey: kp.ecdhPrivateKey,
        publicKeySpkiBase64: kp.publicKeySpkiBase64,
        createdAt: Date.now()
      };

      try {
        await idbTx(database, STORE_IDENTITY, "readwrite", (s) => s.put(record));
        cachedIdentity = record;
        return record;
      } catch (err) {
        if (err && err.name === "DataCloneError") {
          const fallbackKp = await generateIdentityKeyPair({ keepPkcs8: true });
          const fallbackRecord = {
            id: IDENTITY_KEY,
            deviceId: record.deviceId,
            deviceName: record.deviceName,
            publicKeySpkiBase64: fallbackKp.publicKeySpkiBase64,
            privateKeyPkcs8: fallbackKp.privateKeyPkcs8,
            createdAt: record.createdAt
          };
          await idbTx(database, STORE_IDENTITY, "readwrite", (s) => s.put(fallbackRecord));
          cachedIdentity = {
            ...fallbackRecord,
            ecdsaPrivateKey: fallbackKp.ecdsaPrivateKey,
            ecdhPrivateKey: fallbackKp.ecdhPrivateKey
          };
          return cachedIdentity;
        }
        throw err;
      }
    },

    async setDeviceName(name) {
      const current = await this.getOrCreateIdentity();
      const updated = { ...current, deviceName: sanitizeDeviceName(name, current.deviceName) };
      const database = await db();
      const toStore = { ...updated };
      if (toStore.privateKeyPkcs8) {
        delete toStore.ecdsaPrivateKey;
        delete toStore.ecdhPrivateKey;
      }
      await idbTx(database, STORE_IDENTITY, "readwrite", (s) => s.put(toStore));
      cachedIdentity = updated;
      return updated;
    },

    async listCameras() {
      const database = await db();
      const all = await idbTx(database, STORE_CAMERAS, "readonly", (s) => s.getAll());
      return (all || []).sort(
        (a, b) => (b.lastConnectedAt || 0) - (a.lastConnectedAt || 0) || (b.pairedTimestamp || 0) - (a.pairedTimestamp || 0)
      );
    },

    async getCamera(deviceId) {
      const database = await db();
      return (await idbTx(database, STORE_CAMERAS, "readonly", (s) => s.get(deviceId))) || null;
    },

    async saveCamera(cam) {
      const database = await db();
      const all = await idbTx(database, STORE_CAMERAS, "readonly", (s) => s.getAll());
      const prev = (all || []).find((c) => c.deviceId === cam.deviceId) || null;
      if (!prev && (all || []).length >= MAX_PAIRED_CAMERAS) {
        return false;
      }
      const next = {
        deviceId: cam.deviceId,
        name: sanitizeDeviceName(cam.name, "Camera"),
        publicKeyBase64: cam.publicKeyBase64,
        pairedTimestamp: cam.pairedTimestamp ?? prev?.pairedTimestamp ?? Date.now(),
        lastConnectedAt: cam.lastConnectedAt ?? prev?.lastConnectedAt ?? null,
        connectDurationsMs: cam.connectDurationsMs ?? prev?.connectDurationsMs ?? [],
        audioEnabled: cam.audioEnabled ?? prev?.audioEnabled ?? false,
        nightModeSetting: cam.nightModeSetting ?? prev?.nightModeSetting ?? "AUTO"
      };
      await idbTx(database, STORE_CAMERAS, "readwrite", (s) => s.put(next));
      return true;
    },

    async updateCamera(deviceId, patch) {
      const prev = await this.getCamera(deviceId);
      if (!prev) return null;
      const next = { ...prev, ...patch, deviceId };
      const database = await db();
      await idbTx(database, STORE_CAMERAS, "readwrite", (s) => s.put(next));
      return next;
    },

    async deleteCamera(deviceId) {
      const database = await db();
      await idbTx(database, STORE_CAMERAS, "readwrite", (s) => s.delete(deviceId));
    },

    async recordConnectDuration(deviceId, durationMs, connectedAt = Date.now()) {
      const prev = await this.getCamera(deviceId);
      if (!prev) return null;
      const next = {
        ...prev,
        lastConnectedAt: connectedAt,
        connectDurationsMs: updateConnectDurationSamples(prev.connectDurationsMs, durationMs)
      };
      const database = await db();
      await idbTx(database, STORE_CAMERAS, "readwrite", (s) => s.put(next));
      return next;
    },

    async getSetting(key, fallback = null) {
      const database = await db();
      const row = await idbTx(database, STORE_SETTINGS, "readonly", (s) => s.get(key));
      return row ? row.value : fallback;
    },

    async setSetting(key, value) {
      const database = await db();
      await idbTx(database, STORE_SETTINGS, "readwrite", (s) => s.put({ key, value }));
    },

    async requestPersistentStorage() {
      try {
        if (globalThis.navigator?.storage?.persist) {
          return await globalThis.navigator.storage.persist();
        }
      } catch (_) {}
      return false;
    }
  };
}
