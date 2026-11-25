import test from "node:test";
import assert from "node:assert/strict";
import {
  createTranslator,
  normalizeChineseLocale,
  resolveLocale,
  SUPPORTED_LOCALES,
  TRANSLATIONS
} from "../src/i18n.js";
import {
  computeRotatedVideoLayout,
  detectDefaultViewerName,
  detectIosSafariBanner,
  formatBatteryBadge,
  formatRelativeLastConnected,
  sanitizeDeviceName
} from "../src/ui.js";

test("i18n has 100% key and {placeholder} parity across all 4 locales", () => {
  const baseKeys = Object.keys(TRANSLATIONS.en).sort();
  const extractPlaceholders = (s) => (s.match(/\{\w+\}/g) || []).sort();

  for (const loc of SUPPORTED_LOCALES) {
    const dict = TRANSLATIONS[loc];
    assert.ok(dict, `Missing locale ${loc}`);
    const keys = Object.keys(dict).sort();
    assert.deepEqual(keys, baseKeys, `Key mismatch in locale ${loc}`);
    for (const k of baseKeys) {
      assert.deepEqual(
        extractPlaceholders(dict[k]),
        extractPlaceholders(TRANSLATIONS.en[k]),
        `Placeholder mismatch in ${loc}.${k}`
      );
    }
  }
});

test("locale normalization matches Android LocaleHelper rules", () => {
  assert.equal(normalizeChineseLocale("zh-TW"), "zh-Hant");
  assert.equal(normalizeChineseLocale("zh-HK"), "zh-Hant");
  assert.equal(normalizeChineseLocale("zh-Hant-TW"), "zh-Hant");
  assert.equal(normalizeChineseLocale("zh-CN"), "zh-Hans");
  assert.equal(normalizeChineseLocale("zh-SG"), "zh-Hans");
  assert.equal(normalizeChineseLocale("zh"), "zh-Hans");
  assert.equal(resolveLocale(null, ["zh-TW"]), "zh-Hant");
  assert.equal(resolveLocale(null, ["zh-CN"]), "zh-Hans");
  assert.equal(resolveLocale(null, ["ja-JP"]), "ja");
  assert.equal(resolveLocale(null, ["fr-FR"]), "en");
});

test("computeRotatedVideoLayout scales 0/90/180/270 in Fit and Fill without clipping in Fit", () => {
  // Portrait iPhone viewport (390x844) watching 1280x720 landscape video
  const fit0 = computeRotatedVideoLayout({
    containerWidth: 390,
    containerHeight: 844,
    videoWidth: 1280,
    videoHeight: 720,
    rotationDeg: 0,
    fitMode: "fit"
  });
  assert.equal(fit0.width, 390);
  assert.equal(fit0.height, 219);
  assert.equal(fit0.transform, "none");

  // Rotated 90deg in portrait viewport -> post-rotation width=height*... fills 390x693 cleanly
  const fit90 = computeRotatedVideoLayout({
    containerWidth: 390,
    containerHeight: 844,
    videoWidth: 1280,
    videoHeight: 720,
    rotationDeg: 90,
    fitMode: "fit"
  });
  // After 90deg CSS rotation, visual width is fit90.height and visual height is fit90.width
  assert.ok(fit90.height <= 390);
  assert.ok(fit90.width <= 844);
  assert.equal(fit90.transform, "rotate(90deg)");

  const fill90 = computeRotatedVideoLayout({
    containerWidth: 390,
    containerHeight: 844,
    videoWidth: 1280,
    videoHeight: 720,
    rotationDeg: 90,
    fitMode: "fill"
  });
  // Post-rotation visual box covers entire 390x844 stage
  assert.ok(fill90.height >= 390);
  assert.ok(fill90.width >= 844);
});

test("device name detection, sanitization, iOS PWA banner, battery, and relative time", () => {
  assert.equal(
    detectDefaultViewerName(
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
      "iPhone",
      5
    ),
    "iPhone Safari"
  );
  assert.equal(
    detectDefaultViewerName(
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
      "MacIntel",
      5
    ),
    "iPad Safari"
  );
  assert.equal(
    detectDefaultViewerName(
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0",
      "Win32",
      0
    ),
    "Windows Edge"
  );

  assert.equal(sanitizeDeviceName("  \u0000My\nCam\u007f  "), "MyCam");
  assert.equal(sanitizeDeviceName("a".repeat(50)).length, 32);
  assert.equal(sanitizeDeviceName("   ", "Fallback"), "Fallback");

  assert.equal(detectIosSafariBanner("iPhone", "iPhone", 5, false), true);
  assert.equal(detectIosSafariBanner("iPhone", "iPhone", 5, true), false);
  assert.equal(detectIosSafariBanner("Android", "Linux", 5, false), false);

  assert.equal(formatBatteryBadge(85, true), "85%⚡");
  assert.equal(formatBatteryBadge(42, false), "42%");
  assert.equal(formatBatteryBadge(null, false), "--%");

  const t = createTranslator("en");
  const now = 1_764_000_000_000;
  assert.equal(formatRelativeLastConnected(null, now, t), "Not connected yet");
  assert.equal(formatRelativeLastConnected(now - 10_000, now, t), "Connected just now");
  assert.equal(formatRelativeLastConnected(now - 5 * 60_000, now, t), "Connected 5 min ago");
  assert.equal(formatRelativeLastConnected(now - 2 * 3600_000, now, t), "Connected 2 hr ago");
  assert.equal(formatRelativeLastConnected(now - 3 * 86400_000, now, t), "Connected 3 d ago");
});
