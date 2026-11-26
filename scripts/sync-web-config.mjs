#!/usr/bin/env node
/**
 * Synchronizes root `cammonitor.config.json` (plus optional gitignored `cammonitor.local.json`)
 * into the static Web Viewer's `docs/viewer/src/config.js` and `docs/viewer/index.html` CSP `connect-src`.
 *
 * Usage:
 *   node scripts/sync-web-config.mjs            # Write config.js and update index.html CSP
 *   node scripts/sync-web-config.mjs --check    # Verify files are in sync (CI / test check, no writes)
 *   node scripts/sync-web-config.mjs --no-local # Ignore cammonitor.local.json (for deterministic parity checks)
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(__dirname, "..");
export const BASE_CONFIG_PATH = path.join(REPO_ROOT, "cammonitor.config.json");
export const LOCAL_CONFIG_PATH = path.join(REPO_ROOT, "cammonitor.local.json");
export const WEB_CONFIG_JS_PATH = path.join(REPO_ROOT, "docs", "viewer", "src", "config.js");
export const WEB_INDEX_HTML_PATH = path.join(REPO_ROOT, "docs", "viewer", "index.html");

export function deepMerge(base, override) {
  if (!override || typeof override !== "object" || Array.isArray(override)) {
    return override === undefined ? base : override;
  }
  const result = { ...(base && typeof base === "object" && !Array.isArray(base) ? base : {}) };
  for (const [key, val] of Object.entries(override)) {
    if (val && typeof val === "object" && !Array.isArray(val) && result[key] && typeof result[key] === "object" && !Array.isArray(result[key])) {
      result[key] = deepMerge(result[key], val);
    } else {
      result[key] = val;
    }
  }
  return result;
}

function assertPositiveInt(val, label) {
  if (!Number.isInteger(val) || val <= 0) {
    throw new Error(`Invalid config: ${label} must be a positive integer (got ${JSON.stringify(val)})`);
  }
}

function assertNonEmptyString(val, label) {
  if (typeof val !== "string" || val.trim().length === 0) {
    throw new Error(`Invalid config: ${label} must be a non-empty string`);
  }
}

export function validateConfig(cfg) {
  if (!cfg || typeof cfg !== "object") throw new Error("Config root must be a JSON object");

  // project
  assertNonEmptyString(cfg.project?.githubOwner, "project.githubOwner");
  assertNonEmptyString(cfg.project?.githubRepo, "project.githubRepo");
  assertNonEmptyString(cfg.project?.webViewerBaseUrl, "project.webViewerBaseUrl");
  const webUrl = new URL(cfg.project.webViewerBaseUrl);
  if (webUrl.protocol !== "https:" && webUrl.hostname !== "localhost" && webUrl.hostname !== "127.0.0.1") {
    throw new Error(`Invalid config: project.webViewerBaseUrl must use https:// (got ${cfg.project.webViewerBaseUrl})`);
  }

  // signaling
  assertNonEmptyString(cfg.signaling?.topicPrefix, "signaling.topicPrefix");
  assertPositiveInt(cfg.signaling?.keepAliveSec, "signaling.keepAliveSec");
  assertPositiveInt(cfg.signaling?.maxReconnectBackoffMs, "signaling.maxReconnectBackoffMs");
  if (!Array.isArray(cfg.signaling?.brokers) || cfg.signaling.brokers.length === 0) {
    throw new Error("Invalid config: signaling.brokers must be a non-empty array");
  }
  const primaryBrokers = cfg.signaling.brokers.filter((b) => b.primary === true);
  if (primaryBrokers.length !== 1) {
    throw new Error(`Invalid config: signaling.brokers must have exactly one broker with primary: true (found ${primaryBrokers.length})`);
  }
  for (const [idx, b] of cfg.signaling.brokers.entries()) {
    assertNonEmptyString(b.host, `signaling.brokers[${idx}].host`);
    assertPositiveInt(b.tlsPort, `signaling.brokers[${idx}].tlsPort`);
    assertPositiveInt(b.wssPort, `signaling.brokers[${idx}].wssPort`);
    assertNonEmptyString(b.wssPath, `signaling.brokers[${idx}].wssPath`);
    if (!b.wssPath.startsWith("/")) {
      throw new Error(`Invalid config: signaling.brokers[${idx}].wssPath must start with '/'`);
    }
  }

  // webrtc
  if (!Array.isArray(cfg.webrtc?.iceServers) || cfg.webrtc.iceServers.length === 0) {
    throw new Error("Invalid config: webrtc.iceServers must be a non-empty array");
  }
  for (const [idx, s] of cfg.webrtc.iceServers.entries()) {
    assertNonEmptyString(s.urls, `webrtc.iceServers[${idx}].urls`);
    if (s.username !== undefined && typeof s.username !== "string") {
      throw new Error(`Invalid config: webrtc.iceServers[${idx}].username must be a string`);
    }
    if (s.credential !== undefined && typeof s.credential !== "string") {
      throw new Error(`Invalid config: webrtc.iceServers[${idx}].credential must be a string`);
    }
  }
  assertPositiveInt(cfg.webrtc?.video?.width, "webrtc.video.width");
  assertPositiveInt(cfg.webrtc?.video?.height, "webrtc.video.height");
  assertPositiveInt(cfg.webrtc?.video?.targetFps, "webrtc.video.targetFps");
  assertPositiveInt(cfg.webrtc?.video?.minBitrateBps, "webrtc.video.minBitrateBps");
  assertPositiveInt(cfg.webrtc?.video?.maxBitrateBps, "webrtc.video.maxBitrateBps");
  if (cfg.webrtc.video.minBitrateBps > cfg.webrtc.video.maxBitrateBps) {
    throw new Error("Invalid config: webrtc.video.minBitrateBps must be <= maxBitrateBps");
  }

  // limits
  assertPositiveInt(cfg.limits?.maxPairedCameras, "limits.maxPairedCameras");
  assertPositiveInt(cfg.limits?.maxPairedViewers, "limits.maxPairedViewers");
  assertPositiveInt(cfg.limits?.maxConcurrentViewers, "limits.maxConcurrentViewers");
  if (cfg.limits.maxConcurrentViewers > cfg.limits.maxPairedViewers) {
    throw new Error("Invalid config: limits.maxConcurrentViewers must be <= limits.maxPairedViewers");
  }

  // camera
  assertPositiveInt(cfg.camera?.noViewerPauseMs, "camera.noViewerPauseMs");
  assertPositiveInt(cfg.camera?.oledAutoLockSec, "camera.oledAutoLockSec");
  assertPositiveInt(cfg.camera?.maxRecoveryAttempts, "camera.maxRecoveryAttempts");
  if (typeof cfg.camera?.nightMode?.luxEnter !== "number" || typeof cfg.camera?.nightMode?.luxExit !== "number") {
    throw new Error("Invalid config: camera.nightMode.luxEnter and luxExit must be numbers");
  }
  if (cfg.camera.nightMode.luxExit <= cfg.camera.nightMode.luxEnter) {
    throw new Error("Invalid config: camera.nightMode.luxExit must be > luxEnter for hysteresis");
  }
  assertPositiveInt(cfg.camera?.nightMode?.debounceMs, "camera.nightMode.debounceMs");
  assertPositiveInt(cfg.camera?.battery?.lowPercent, "camera.battery.lowPercent");
  assertPositiveInt(cfg.camera?.battery?.broadcastWarnPercent, "camera.battery.broadcastWarnPercent");
  if (cfg.camera.battery.lowPercent > cfg.camera.battery.broadcastWarnPercent || cfg.camera.battery.broadcastWarnPercent >= 100) {
    throw new Error("Invalid config: battery thresholds must satisfy 0 < lowPercent <= broadcastWarnPercent < 100");
  }

  // updates
  assertPositiveInt(cfg.updates?.checkIntervalHours, "updates.checkIntervalHours");
  assertPositiveInt(cfg.updates?.flexIntervalHours, "updates.flexIntervalHours");
  assertPositiveInt(cfg.updates?.httpTimeoutMs, "updates.httpTimeoutMs");

  // logging
  assertPositiveInt(cfg.logging?.maxFileSizeMb, "logging.maxFileSizeMb");
  assertPositiveInt(cfg.logging?.maxBackupFiles, "logging.maxBackupFiles");
  assertPositiveInt(cfg.logging?.androidMemoryEntries, "logging.androidMemoryEntries");
  assertPositiveInt(cfg.logging?.webMemoryEntries, "logging.webMemoryEntries");

  return cfg;
}

export function loadMergedConfig({ includeLocal = true } = {}) {
  const base = JSON.parse(fs.readFileSync(BASE_CONFIG_PATH, "utf8"));
  if (includeLocal && fs.existsSync(LOCAL_CONFIG_PATH)) {
    const local = JSON.parse(fs.readFileSync(LOCAL_CONFIG_PATH, "utf8"));
    return validateConfig(deepMerge(base, local));
  }
  return validateConfig(base);
}

export function buildWebConfigSubset(cfg) {
  const brokers = cfg.signaling.brokers.map((b) => ({
    host: b.host.trim().toLowerCase(),
    wssPort: b.wssPort,
    wssPath: b.wssPath,
    primary: Boolean(b.primary)
  }));
  const iceServers = cfg.webrtc.iceServers.map((s) => {
    const entry = { urls: s.urls };
    if (s.username) entry.username = s.username;
    if (s.credential) entry.credential = s.credential;
    return entry;
  });
  return {
    project: {
      githubOwner: cfg.project.githubOwner,
      githubRepo: cfg.project.githubRepo,
      webViewerBaseUrl: cfg.project.webViewerBaseUrl
    },
    signaling: {
      topicPrefix: cfg.signaling.topicPrefix,
      keepAliveSec: cfg.signaling.keepAliveSec,
      maxReconnectBackoffMs: cfg.signaling.maxReconnectBackoffMs,
      brokers
    },
    webrtc: {
      iceServers,
      video: {
        width: cfg.webrtc.video.width,
        height: cfg.webrtc.video.height,
        targetFps: cfg.webrtc.video.targetFps
      }
    },
    limits: {
      maxPairedCameras: cfg.limits.maxPairedCameras,
      maxConcurrentViewers: cfg.limits.maxConcurrentViewers
    },
    logging: {
      webMemoryEntries: cfg.logging.webMemoryEntries
    }
  };
}

export function renderWebConfigJs(cfg) {
  const subset = buildWebConfigSubset(cfg);
  return (
    "// AUTO-GENERATED from cammonitor.config.json — DO NOT EDIT MANUALLY.\n" +
    "// Run `node scripts/sync-web-config.mjs` after editing cammonitor.config.json.\n" +
    `export const APP_CONFIG = Object.freeze(${JSON.stringify(subset, null, 2)});\n`
  );
}

export function buildExpectedConnectSrc(cfg) {
  const origins = cfg.signaling.brokers.map((b) => `wss://${b.host.trim().toLowerCase()}:${b.wssPort}`);
  return Array.from(new Set(origins)).join(" ");
}

export function updateIndexHtmlCsp(htmlContent, cfg) {
  const connectSrc = buildExpectedConnectSrc(cfg);
  const pattern = /(connect-src\s+)([^;"]+)/;
  if (!pattern.test(htmlContent)) {
    throw new Error("Could not find `connect-src` directive in docs/viewer/index.html CSP");
  }
  return htmlContent.replace(pattern, `$1${connectSrc}`);
}

function main() {
  const args = new Set(process.argv.slice(2));
  const checkOnly = args.has("--check");
  const includeLocal = !args.has("--no-local");

  const cfg = loadMergedConfig({ includeLocal });
  const expectedJs = renderWebConfigJs(cfg);
  const currentHtml = fs.readFileSync(WEB_INDEX_HTML_PATH, "utf8");
  const expectedHtml = updateIndexHtmlCsp(currentHtml, cfg);

  if (checkOnly) {
    const errors = [];
    const currentJs = fs.existsSync(WEB_CONFIG_JS_PATH) ? fs.readFileSync(WEB_CONFIG_JS_PATH, "utf8") : "";
    if (currentJs !== expectedJs) {
      errors.push("docs/viewer/src/config.js is out of sync with cammonitor.config.json");
    }
    if (currentHtml !== expectedHtml) {
      errors.push("docs/viewer/index.html CSP connect-src is out of sync with cammonitor.config.json");
    }
    if (errors.length > 0) {
      for (const e of errors) console.error(`[sync-web-config] ERROR: ${e}`);
      console.error("Run: node scripts/sync-web-config.mjs");
      process.exit(1);
    }
    console.log("[sync-web-config] OK: docs/viewer/src/config.js and index.html CSP match cammonitor.config.json");
    return;
  }

  fs.writeFileSync(WEB_CONFIG_JS_PATH, expectedJs, "utf8");
  if (currentHtml !== expectedHtml) {
    fs.writeFileSync(WEB_INDEX_HTML_PATH, expectedHtml, "utf8");
  }
  console.log("[sync-web-config] Updated docs/viewer/src/config.js and verified docs/viewer/index.html CSP");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
