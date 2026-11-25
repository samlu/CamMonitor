export function sanitizeDeviceName(raw, fallback = "Web Viewer") {
  if (typeof raw !== "string") return fallback;
  let cleaned = "";
  for (const ch of raw.trim()) {
    const code = ch.codePointAt(0);
    // Strip ISO control characters (U+0000..U+001F, U+007F..U+009F)
    if ((code >= 0x00 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f)) continue;
    cleaned += ch;
    if (cleaned.length >= 32) break;
  }
  return cleaned.length > 0 ? cleaned : fallback;
}

export function detectDefaultViewerName(
  userAgent = globalThis.navigator?.userAgent || "",
  platform = globalThis.navigator?.platform || "",
  maxTouchPoints = globalThis.navigator?.maxTouchPoints || 0
) {
  const ua = String(userAgent);
  const plat = String(platform);
  const isIPad = /iPad/i.test(ua) || (/Macintosh/i.test(ua) && Number(maxTouchPoints) > 1) || (/MacIntel/i.test(plat) && Number(maxTouchPoints) > 1);
  const isIPhone = /iPhone|iPod/i.test(ua);
  const isAndroid = /Android/i.test(ua);
  const isMac = !isIPad && /Macintosh|Mac OS X/i.test(ua);
  const isWin = /Windows/i.test(ua);
  const isLinux = !isAndroid && /Linux/i.test(ua);

  let os = "Web";
  if (isIPhone) os = "iPhone";
  else if (isIPad) os = "iPad";
  else if (isAndroid) os = "Android";
  else if (isMac) os = "Mac";
  else if (isWin) os = "Windows";
  else if (isLinux) os = "Linux";

  let browser = "Browser";
  if (/Edg\//i.test(ua) || /EdgiOS/i.test(ua)) browser = "Edge";
  else if (/CriOS/i.test(ua) || (/Chrome\//i.test(ua) && !/Edg\//i.test(ua))) browser = "Chrome";
  else if (/FxiOS/i.test(ua) || /Firefox\//i.test(ua)) browser = "Firefox";
  else if (/Safari\//i.test(ua)) browser = "Safari";

  return sanitizeDeviceName(`${os} ${browser}`, "Web Viewer");
}

export function detectIosSafariBanner(
  userAgent = globalThis.navigator?.userAgent || "",
  platform = globalThis.navigator?.platform || "",
  maxTouchPoints = globalThis.navigator?.maxTouchPoints || 0,
  isStandalone = Boolean(
    globalThis.navigator?.standalone ||
      globalThis.matchMedia?.("(display-mode: standalone)")?.matches
  )
) {
  const ua = String(userAgent);
  const plat = String(platform);
  const isIos =
    /iPhone|iPod|iPad/i.test(ua) ||
    (/Macintosh|MacIntel/i.test(`${ua} ${plat}`) && Number(maxTouchPoints) > 1);
  return isIos && !isStandalone;
}

/**
 * Computes exact CSS box dimensions + transform for 0/90/180/270 rotation in Fit or Fill mode
 * without clipping in Fit mode or leaving unwanted gaps in Fill mode.
 */
export function computeRotatedVideoLayout({
  containerWidth,
  containerHeight,
  videoWidth,
  videoHeight,
  rotationDeg = 0,
  fitMode = "fit"
}) {
  const cw = Math.max(1, Number(containerWidth) || 1);
  const ch = Math.max(1, Number(containerHeight) || 1);
  const vw = Math.max(1, Number(videoWidth) || 1280);
  const vh = Math.max(1, Number(videoHeight) || 720);
  const rot = ((Math.round(Number(rotationDeg) || 0) % 360) + 360) % 360;
  const swapped = rot === 90 || rot === 270;

  const effW = swapped ? vh : vw;
  const effH = swapped ? vw : vh;
  const scale =
    fitMode === "fill"
      ? Math.max(cw / effW, ch / effH)
      : Math.min(cw / effW, ch / effH);

  // Pre-rotation CSS width/height of the <video> element so after rotate(rot) it matches effW*scale x effH*scale
  const width = Math.round(vw * scale);
  const height = Math.round(vh * scale);
  return {
    width,
    height,
    rotationDeg: rot,
    transform: rot === 0 ? "none" : `rotate(${rot}deg)`
  };
}

export function formatRelativeLastConnected(lastConnectedAt, now = Date.now(), t) {
  if (!lastConnectedAt || lastConnectedAt <= 0) {
    return t("home_never_connected");
  }
  const diffMs = Math.max(0, now - lastConnectedAt);
  const diffMin = Math.floor(diffMs / 60_000);
  if (diffMin < 1) return t("home_connected_just_now");
  if (diffMin < 60) return t("home_connected_minutes", { n: diffMin });
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return t("home_connected_hours", { n: diffHr });
  const diffDays = Math.floor(diffHr / 24);
  if (diffDays <= 7) return t("home_connected_days", { n: diffDays });
  return t("home_connected_date", { date: new Date(lastConnectedAt).toLocaleDateString() });
}

export function formatBatteryBadge(pct, charging) {
  if (typeof pct !== "number" || !Number.isFinite(pct) || pct < 0) {
    return "--%";
  }
  return charging ? `${Math.round(pct)}%⚡` : `${Math.round(pct)}%`;
}
