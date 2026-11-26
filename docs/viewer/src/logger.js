import { APP_CONFIG } from "./config.js";

const MAX_ENTRIES = APP_CONFIG.logging.webMemoryEntries;
const startEpochMs = Date.now();
const entries = [];
const listeners = new Set();

function formatClock(tsMs) {
  const d = new Date(tsMs);
  const pad2 = (n) => String(n).padStart(2, "0");
  const pad3 = (n) => String(n).padStart(3, "0");
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}.${pad3(d.getMilliseconds())}`;
}

function add(level, tag, message) {
  const now = Date.now();
  const relSec = ((now - startEpochMs) / 1000).toFixed(1);
  const entry = {
    timestamp: now,
    clock: formatClock(now),
    rel: `+${relSec}s`,
    level,
    tag,
    message: String(message)
  };
  entries.push(entry);
  if (entries.length > MAX_ENTRIES) {
    entries.splice(0, entries.length - MAX_ENTRIES);
  }
  for (const cb of listeners) {
    try {
      cb(entry, entries);
    } catch (_) {
      // Ignore subscriber errors
    }
  }
  return entry;
}

export const logger = {
  d: (tag, msg) => add("DEBUG", tag, msg),
  i: (tag, msg) => add("INFO", tag, msg),
  w: (tag, msg) => add("WARN", tag, msg),
  e: (tag, msg) => add("ERROR", tag, msg),
  getEntries: () => entries.slice(),
  clear() {
    entries.length = 0;
    for (const cb of listeners) {
      try {
        cb(null, entries);
      } catch (_) {}
    }
  },
  subscribe(cb) {
    listeners.add(cb);
    return () => listeners.delete(cb);
  },
  exportText() {
    return entries.map((e) => `[${e.clock} ${e.rel}] [${e.level}] [${e.tag}] ${e.message}`).join("\n");
  }
};
