import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {
  buildExpectedConnectSrc,
  deepMerge,
  loadMergedConfig,
  renderWebConfigJs,
  updateIndexHtmlCsp,
  validateConfig,
  WEB_CONFIG_JS_PATH,
  WEB_INDEX_HTML_PATH,
  REPO_ROOT
} from "../../../scripts/sync-web-config.mjs";
import { APP_CONFIG } from "../src/config.js";
import { ALLOWED_BROKER_HOSTS, DEFAULT_BROKER_URL, SIGNALING_TOPIC_PREFIX } from "../src/signaling.js";
import { MAX_PAIRED_CAMERAS } from "../src/storage.js";
import { ICE_SERVERS } from "../src/webrtc.js";

test("docs/viewer/src/config.js and index.html CSP match cammonitor.config.json", () => {
  const cfg = loadMergedConfig({ includeLocal: true });
  const actualJs = fs.readFileSync(WEB_CONFIG_JS_PATH, "utf8");
  const expectedJs = renderWebConfigJs(cfg);
  assert.equal(
    actualJs,
    expectedJs,
    "docs/viewer/src/config.js drifted from cammonitor.config.json — run `node scripts/sync-web-config.mjs`"
  );

  const actualHtml = fs.readFileSync(WEB_INDEX_HTML_PATH, "utf8");
  const expectedHtml = updateIndexHtmlCsp(actualHtml, cfg);
  assert.equal(
    actualHtml,
    expectedHtml,
    `docs/viewer/index.html CSP connect-src drifted (expected '${buildExpectedConnectSrc(cfg)}')`
  );

  const swJs = fs.readFileSync(path.join(REPO_ROOT, "docs", "viewer", "sw.js"), "utf8");
  assert.ok(swJs.includes('"./src/config.js"'), "docs/viewer/sw.js must precache ./src/config.js");
});

test("runtime Web Viewer modules consume APP_CONFIG values consistently", () => {
  const primary = APP_CONFIG.signaling.brokers.find((b) => b.primary);
  assert.ok(primary, "Primary broker must be defined");
  assert.equal(DEFAULT_BROKER_URL, `wss://${primary.host}:${primary.wssPort}${primary.wssPath}`);
  assert.equal(SIGNALING_TOPIC_PREFIX, APP_CONFIG.signaling.topicPrefix);
  for (const broker of APP_CONFIG.signaling.brokers) {
    assert.ok(ALLOWED_BROKER_HOSTS.has(broker.host));
  }
  assert.equal(MAX_PAIRED_CAMERAS, APP_CONFIG.limits.maxPairedCameras);
  assert.deepEqual(ICE_SERVERS, APP_CONFIG.webrtc.iceServers);
});

test("deepMerge merges nested objects and replaces arrays/primitives", () => {
  const merged = deepMerge(
    {
      project: { githubOwner: "samlu", githubRepo: "CamMonitor" },
      webrtc: { iceServers: [{ urls: "stun:a" }], video: { width: 1280, height: 720 } }
    },
    {
      project: { githubOwner: "custom" },
      webrtc: { iceServers: [{ urls: "turn:b", username: "u", credential: "p" }] }
    }
  );
  assert.equal(merged.project.githubOwner, "custom");
  assert.equal(merged.project.githubRepo, "CamMonitor");
  assert.deepEqual(merged.webrtc.iceServers, [{ urls: "turn:b", username: "u", credential: "p" }]);
  assert.equal(merged.webrtc.video.width, 1280);
});

test("validateConfig rejects invalid broker, bitrate, lux, capacity, and URL settings", () => {
  const valid = loadMergedConfig({ includeLocal: false });
  const clone = () => structuredClone(valid);

  // Zero primary brokers
  const noPrimary = clone();
  noPrimary.signaling.brokers.forEach((b) => { b.primary = false; });
  assert.throws(() => validateConfig(noPrimary), /primary: true/);

  // Two primary brokers
  const twoPrimary = clone();
  twoPrimary.signaling.brokers.forEach((b) => { b.primary = true; });
  assert.throws(() => validateConfig(twoPrimary), /primary: true/);

  // Non-HTTPS webViewerBaseUrl
  const httpUrl = clone();
  httpUrl.project.webViewerBaseUrl = "http://example.com/viewer/";
  assert.throws(() => validateConfig(httpUrl), /https:\/\//);

  // minBitrate > maxBitrate
  const badBitrate = clone();
  badBitrate.webrtc.video.minBitrateBps = 5_000_000;
  badBitrate.webrtc.video.maxBitrateBps = 1_000_000;
  assert.throws(() => validateConfig(badBitrate), /minBitrateBps/);

  // maxConcurrentViewers > maxPairedViewers
  const badCap = clone();
  badCap.limits.maxConcurrentViewers = 9;
  assert.throws(() => validateConfig(badCap), /maxConcurrentViewers/);

  // luxExit <= luxEnter
  const badLux = clone();
  badLux.camera.nightMode.luxExit = badLux.camera.nightMode.luxEnter;
  assert.throws(() => validateConfig(badLux), /luxExit/);
});
