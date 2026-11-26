import { APP_CONFIG } from "./config.js";
import { createTranslator, resolveLocale } from "./i18n.js";
import { logger } from "./logger.js";
import {
  createQrWebcamScanner,
  parsePairingInput,
  runPairingHandshake,
  scrubPairHashFromUrl
} from "./pairing.js";
import { createSignalingClient } from "./signaling.js";
import { createIndexedDbStore, MAX_PAIRED_CAMERAS } from "./storage.js";
import {
  computeRotatedVideoLayout,
  detectDefaultViewerName,
  detectIosSafariBanner,
  formatBatteryBadge,
  formatRelativeLastConnected
} from "./ui.js";
import {
  createWebRtcViewerSession,
  formatDurationSec,
  nextNightMode,
  shouldShowCountdown,
  shouldShowSlowHint,
  timeoutProgressFraction,
  ViewerConnectionStep
} from "./webrtc.js";

const storage = createIndexedDbStore();
const signaling = createSignalingClient({ storage });

let locale = "en";
let t = createTranslator(locale);
let activeSession = null;
let activeCamera = null;
let qrScanner = null;
let wakeLockSentinel = null;
let hudHideTimer = null;
let lastTapTime = 0;
let viewerHistoryPushed = false;

let viewerState = {
  connState: "CONNECTING",
  connExtra: {},
  progress: null,
  fps: APP_CONFIG.webrtc.video.targetFps,
  battery: null,
  torchEnabled: false,
  nightMode: "AUTO",
  isNightActive: false,
  audioEnabled: false,
  rotationDeg: 0,
  fitMode: "fit",
  hudVisible: true,
  isPttActive: false,
  isAudioDucked: false
};

const $ = (id) => document.getElementById(id);

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function applyTranslations() {
  document.documentElement.lang = locale;
  for (const el of document.querySelectorAll("[data-i18n]")) {
    const key = el.getAttribute("data-i18n");
    if (key) el.textContent = t(key);
  }
  const autoOpt = $("lang-select")?.querySelector('option[value="auto"]');
  if (autoOpt) autoOpt.textContent = t("home_lang_auto");
  const pasteInput = $("pair-paste-input");
  if (pasteInput) {
    pasteInput.placeholder = t("pair_paste_placeholder", {
      url: APP_CONFIG.project.webViewerBaseUrl
    });
  }
  const renameInput = $("rename-input");
  if (renameInput) renameInput.placeholder = t("rename_placeholder");
  const bannerText = $("ios-pwa-banner-text");
  if (bannerText) bannerText.textContent = t("ios_pwa_banner");
}

async function acquireWakeLock() {
  try {
    if ("wakeLock" in navigator && typeof navigator.wakeLock.request === "function") {
      wakeLockSentinel = await navigator.wakeLock.request("screen");
      wakeLockSentinel.addEventListener("release", () => {
        wakeLockSentinel = null;
      });
    }
  } catch (_) {}
}

async function releaseWakeLock() {
  if (wakeLockSentinel) {
    try {
      await wakeLockSentinel.release();
    } catch (_) {}
    wakeLockSentinel = null;
  }
}

function syncVideoLayout() {
  const stage = $("video-stage");
  const video = $("remote-video");
  if (!stage || !video) return;
  const layout = computeRotatedVideoLayout({
    containerWidth: stage.clientWidth || window.innerWidth,
    containerHeight: stage.clientHeight || window.innerHeight,
    videoWidth: video.videoWidth || APP_CONFIG.webrtc.video.width,
    videoHeight: video.videoHeight || APP_CONFIG.webrtc.video.height,
    rotationDeg: viewerState.rotationDeg,
    fitMode: viewerState.fitMode
  });
  video.style.width = `${layout.width}px`;
  video.style.height = `${layout.height}px`;
  video.style.objectFit = viewerState.fitMode === "fill" ? "cover" : "contain";
  video.style.transform = layout.transform;
}

function syncAudioElement() {
  const video = $("remote-video");
  if (!video) return;
  video.muted = !viewerState.audioEnabled || viewerState.isAudioDucked;
}

function resetHudAutoHide() {
  clearTimeout(hudHideTimer);
  if (viewerState.hudVisible && activeCamera && viewerState.connState === "LIVE") {
    hudHideTimer = setTimeout(() => {
      viewerState.hudVisible = false;
      renderViewerHud();
    }, 5000);
  }
}

function renderViewerHud() {
  const hud = $("viewer-hud");
  const floatingBat = $("floating-battery");
  if (!hud) return;
  hud.classList.toggle("hud-hidden", !viewerState.hudVisible);
  if (floatingBat) {
    floatingBat.classList.toggle("hidden", viewerState.hudVisible);
    const val = formatBatteryBadge(viewerState.battery?.pct, viewerState.battery?.charging);
    floatingBat.textContent = `📷 ${val}`;
  }

  $("viewer-cam-name").textContent = activeCamera?.name || "Camera";
  const dot = $("viewer-status-dot");
  const statusEl = $("viewer-status-text");
  const isLive = viewerState.connState === "LIVE";
  dot.classList.toggle("live", isLive);
  if (isLive) {
    statusEl.textContent = t("viewer_status_live", { fps: viewerState.fps });
  } else if (viewerState.connState === "DISCONNECTED") {
    statusEl.textContent = t("viewer_status_disconnected");
  } else if (viewerState.connState === "FAILED") {
    statusEl.textContent = t("viewer_status_failed");
  } else if (viewerState.connState === "CAMERA_TIMEOUT") {
    statusEl.textContent = t("viewer_status_timeout", { sec: viewerState.connExtra.timeoutSec || 15 });
  } else {
    statusEl.textContent = t("viewer_status_connecting");
  }

  const batVal = formatBatteryBadge(viewerState.battery?.pct, viewerState.battery?.charging);
  $("hud-battery-pill").textContent = `📷 ${batVal}`;

  const muteBtn = $("ctrl-mute-btn");
  muteBtn.classList.toggle("active", viewerState.audioEnabled);
  muteBtn.firstChild.textContent = viewerState.audioEnabled ? "🔊 " : "🔇 ";

  const torchBtn = $("ctrl-torch-btn");
  torchBtn.classList.toggle("active", viewerState.torchEnabled);

  const nightBtn = $("ctrl-night-btn");
  const nightLabelKey =
    viewerState.nightMode === "NIGHT"
      ? "ctrl_night_night"
      : viewerState.nightMode === "NORMAL"
      ? "ctrl_night_normal"
      : "ctrl_night_auto";
  $("ctrl-night-label").textContent = t(nightLabelKey);
  nightBtn.classList.toggle("active", viewerState.nightMode === "NIGHT");
  nightBtn.classList.toggle("night-active", viewerState.isNightActive);

  $("ctrl-fit-label").textContent = t(viewerState.fitMode === "fill" ? "ctrl_fill" : "ctrl_fit");

  const pttBtn = $("ctrl-ptt-btn");
  pttBtn.classList.toggle("transmitting", viewerState.isPttActive);
  $("ctrl-ptt-label").textContent = t(viewerState.isPttActive ? "ctrl_ptt_active" : "ctrl_ptt_idle");
}

function renderCenterOverlay() {
  const wrap = $("viewer-center-overlay");
  const card = $("viewer-status-card");
  if (!wrap || !card) return;

  const st = viewerState.connState;
  if (st === "LIVE") {
    wrap.classList.add("hidden");
    card.innerHTML = "";
    return;
  }
  wrap.classList.remove("hidden");

  if (st === "CAMERA_TIMEOUT") {
    const sec = viewerState.connExtra.timeoutSec || 15;
    card.innerHTML = `
      <div class="status-card-title">${escapeHtml(t("viewer_status_timeout", { sec }))}</div>
      <div class="muted">${escapeHtml(t("viewer_timeout_desc"))}</div>
      <div class="modal-actions">
        <button id="overlay-back-btn" class="btn-outline" type="button">${escapeHtml(t("action_back"))}</button>
        <button id="overlay-reconnect-btn" class="btn-primary" type="button">${escapeHtml(t("action_reconnect_now"))}</button>
      </div>
    `;
    $("overlay-back-btn")?.addEventListener("click", () => exitViewer());
    $("overlay-reconnect-btn")?.addEventListener("click", () => activeSession?.reconnect());
    return;
  }

  if (st === "REJECTED") {
    const reason = viewerState.connExtra.reason || "REJECTED";
    const msg =
      reason === "MAX_CAPACITY_REACHED"
        ? t("viewer_rejected_capacity", { max: APP_CONFIG.limits.maxConcurrentViewers })
        : t("viewer_rejected", { reason });
    card.innerHTML = `
      <div class="status-card-title">${escapeHtml(t("viewer_status_failed"))}</div>
      <div class="error-text">${escapeHtml(msg)}</div>
      <div class="modal-actions">
        <button id="overlay-back-btn" class="btn-outline" type="button">${escapeHtml(t("action_back"))}</button>
        <button id="overlay-reconnect-btn" class="btn-primary" type="button">${escapeHtml(t("action_retry"))}</button>
      </div>
    `;
    $("overlay-back-btn")?.addEventListener("click", () => exitViewer());
    $("overlay-reconnect-btn")?.addEventListener("click", () => activeSession?.reconnect());
    return;
  }

  if (st === "FAILED" || st === "DISCONNECTED") {
    const title = st === "FAILED" ? t("viewer_status_failed") : t("viewer_status_disconnected");
    const desc =
      st === "FAILED"
        ? viewerState.connExtra.reason === "LOCAL_NETWORK_BLOCKED"
          ? t("viewer_lna_blocked_desc")
          : viewerState.connExtra.reason === "SIGNALING_FAILED"
            ? t("viewer_signaling_failed_desc")
            : t("viewer_symmetric_nat_desc")
        : "";
    card.innerHTML = `
      <div class="status-card-title">${escapeHtml(title)}</div>
      ${desc ? `<div class="muted">${escapeHtml(desc)}</div>` : ""}
      <div class="modal-actions">
        <button id="overlay-back-btn" class="btn-outline" type="button">${escapeHtml(t("action_back"))}</button>
        <button id="overlay-reconnect-btn" class="btn-primary" type="button">${escapeHtml(t("action_reconnect_now"))}</button>
      </div>
    `;
    $("overlay-back-btn")?.addEventListener("click", () => exitViewer());
    $("overlay-reconnect-btn")?.addEventListener("click", () => activeSession?.reconnect());
    return;
  }

  const p = viewerState.progress;
  if (!p) return;

  const steps = [
    {
      idx: ViewerConnectionStep.CONNECTING_NETWORK,
      active: t("viewer_step_network_active"),
      done: t("viewer_step_network_done"),
      pending: t("viewer_step_network_active"),
      dur: p.step1DurationMs
    },
    {
      idx: ViewerConnectionStep.REACHING_CAMERA,
      active: p.isCameraWakingUp ? t("viewer_step_camera_waking") : t("viewer_step_camera_active"),
      done: t("viewer_step_camera_done"),
      pending: t("viewer_step_camera_pending"),
      dur: p.step2DurationMs
    },
    {
      idx: ViewerConnectionStep.ESTABLISHING_LINK,
      active: t("viewer_step_link_active"),
      done: t("viewer_step_link_done"),
      pending: t("viewer_step_link_pending"),
      dur: p.step3DurationMs
    },
    {
      idx: ViewerConnectionStep.STARTING_VIDEO,
      active: t("viewer_step_video_active"),
      done: t("viewer_step_video_done"),
      pending: t("viewer_step_video_pending"),
      dur: p.step4DurationMs
    }
  ];

  const itemsHtml = steps
    .map((s) => {
      const isDone = p.currentStep > s.idx;
      const isActive = p.currentStep === s.idx;
      const cls = isDone ? "done" : isActive ? "active" : "";
      const mark = isDone ? "✓" : isActive ? "●" : "○";
      const label = isDone ? s.done : isActive ? s.active : s.pending;
      const dur = isDone && s.dur != null ? `${formatDurationSec(s.dur)}s` : "";
      return `<li class="step-item ${cls}"><span>${mark} ${escapeHtml(label)}</span><span class="step-dur">${escapeHtml(dur)}</span></li>`;
    })
    .join("");

  const typicalHtml =
    p.typicalConnectMs != null
      ? `<div class="muted" style="font-size:0.84rem;">${escapeHtml(
          t("viewer_usually_connects_in", { sec: formatDurationSec(p.typicalConnectMs) })
        )}</div>`
      : "";

  const countdownHtml = shouldShowCountdown(p)
    ? `
      <div class="muted" style="font-size:0.8rem;">${escapeHtml(
        t("viewer_attempt_countdown", { attempt: p.attempt, max: p.maxAttempts, sec: p.remainingTimeoutSec })
      )}</div>
      <div class="progress-track"><div class="progress-fill" style="width:${Math.round(
        timeoutProgressFraction(p) * 100
      )}%"></div></div>
    `
    : "";

  const slowHintHtml = shouldShowSlowHint(p)
    ? `<div class="hint-warn">${escapeHtml(t("viewer_slow_hint"))}</div>`
    : "";

  card.innerHTML = `
    <div class="status-card-title">${escapeHtml(t("viewer_connecting_to", { name: activeCamera?.name || "Camera" }))}</div>
    ${typicalHtml}
    <ul class="step-list">${itemsHtml}</ul>
    ${countdownHtml}
    ${slowHintHtml}
    <div class="modal-actions">
      <button id="overlay-back-btn" class="btn-outline" type="button">${escapeHtml(t("action_back"))}</button>
    </div>
  `;
  $("overlay-back-btn")?.addEventListener("click", () => exitViewer());
}

async function renderHome() {
  const identity = await storage.getOrCreateIdentity(detectDefaultViewerName());
  $("viewer-name-display").textContent = identity.deviceName;

  const cameras = await storage.listCameras();
  $("cameras-heading").textContent = t("home_cameras_heading", {
    count: cameras.length,
    max: MAX_PAIRED_CAMERAS
  });
  const listEl = $("camera-list");
  const emptyEl = $("empty-state");
  listEl.innerHTML = "";

  if (cameras.length === 0) {
    emptyEl.classList.remove("hidden");
  } else {
    emptyEl.classList.add("hidden");
    const now = Date.now();
    for (const cam of cameras) {
      const card = document.createElement("div");
      card.className = "camera-card";
      card.innerHTML = `
        <div class="camera-card-info">
          <div class="camera-card-name">${escapeHtml(cam.name)}</div>
          <div class="camera-card-sub">${escapeHtml(formatRelativeLastConnected(cam.lastConnectedAt, now, t))}</div>
        </div>
        <div class="camera-card-actions">
          <button class="btn-danger-ghost" type="button">${escapeHtml(t("home_unpair"))}</button>
          <button class="btn-tonal" type="button">${escapeHtml(t("home_connect"))} ▶</button>
        </div>
      `;
      const [unpairBtn, connectBtn] = card.querySelectorAll("button");
      unpairBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        openUnpairModal(cam);
      });
      connectBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        openViewer(cam);
      });
      card.addEventListener("click", () => openViewer(cam));
      listEl.appendChild(card);
    }
  }

  const dismissed = await storage.getSetting("ios_pwa_banner_dismissed", false);
  const showBanner = !dismissed && detectIosSafariBanner();
  $("ios-pwa-banner").classList.toggle("hidden", !showBanner);
}

function openViewer(cam) {
  if (activeSession) {
    activeSession.close();
    activeSession = null;
  }
  activeCamera = { ...cam };
  viewerState = {
    connState: "CONNECTING",
    connExtra: {},
    progress: null,
    fps: APP_CONFIG.webrtc.video.targetFps,
    battery: null,
    torchEnabled: false,
    nightMode: cam.nightModeSetting || "AUTO",
    isNightActive: false,
    audioEnabled: Boolean(cam.audioEnabled),
    rotationDeg: 0,
    fitMode: "fit",
    hudVisible: true,
    isPttActive: false,
    isAudioDucked: false
  };

  if (!viewerHistoryPushed) {
    try {
      history.pushState({ view: "viewer", deviceId: cam.deviceId }, "");
      viewerHistoryPushed = true;
    } catch (_) {}
  }

  $("home-view").classList.add("hidden");
  $("viewer-view").classList.remove("hidden");
  const video = $("remote-video");
  video.srcObject = null;
  syncAudioElement();
  syncVideoLayout();
  renderViewerHud();
  renderCenterOverlay();
  resetHudAutoHide();
  acquireWakeLock();

  activeSession = createWebRtcViewerSession({
    camera: activeCamera,
    storage,
    signaling,
    onStateChange: (state, extra) => {
      viewerState.connState = state;
      viewerState.connExtra = extra || {};
      if (state === "LIVE") {
        resetHudAutoHide();
      } else {
        clearTimeout(hudHideTimer);
        viewerState.hudVisible = true;
      }
      renderViewerHud();
      renderCenterOverlay();
    },
    onProgressChange: (p) => {
      viewerState.progress = p;
      renderCenterOverlay();
    },
    onStream: (stream) => {
      video.srcObject = stream;
      syncAudioElement();
      video.play().catch((err) => {
        if (!video.muted) {
          logger.w("Video", `Unmuted autoplay blocked (${err?.name || "error"}); falling back to muted playback`);
          viewerState.audioEnabled = false;
          syncAudioElement();
          renderViewerHud();
          video.play().catch(() => {});
        }
      });
    },
    onCommand: (cmd) => {
      switch (cmd.cmd) {
        case "BATTERY":
          viewerState.battery = { pct: cmd.pct, charging: cmd.charging };
          renderViewerHud();
          break;
        case "TORCH_REPORT":
          viewerState.torchEnabled = cmd.enabled;
          renderViewerHud();
          break;
        case "NIGHT_MODE_REPORT":
          viewerState.nightMode = cmd.mode;
          viewerState.isNightActive = cmd.active;
          storage.updateCamera(activeCamera.deviceId, { nightModeSetting: cmd.mode }).catch(() => {});
          renderViewerHud();
          break;
        case "FPS_REPORT":
          viewerState.fps = cmd.fps;
          renderViewerHud();
          break;
        case "RENAME":
          activeCamera.name = cmd.name;
          renderViewerHud();
          break;
        default:
          break;
      }
    },
    onCameraUpdate: (patch) => {
      if (patch.name) activeCamera.name = patch.name;
      if (typeof patch.fps === "number") viewerState.fps = patch.fps;
      renderViewerHud();
      renderCenterOverlay();
    },
    onDuckAudio: (duck) => {
      viewerState.isAudioDucked = duck;
      syncAudioElement();
    },
    onMicAcquired: () => {
      // iOS Safari may pause <video> when transitioning audio session to playAndRecord
      video.play().catch(() => {});
    },
    onPttStateChange: (active) => {
      viewerState.isPttActive = active;
      renderViewerHud();
    }
  });

  activeSession.start();
}

async function exitViewer({ fromPopState = false } = {}) {
  clearTimeout(hudHideTimer);
  if (activeSession) {
    activeSession.close();
    activeSession = null;
  }
  activeCamera = null;
  if (!fromPopState && viewerHistoryPushed) {
    viewerHistoryPushed = false;
    try {
      history.back();
    } catch (_) {}
  } else {
    viewerHistoryPushed = false;
  }
  const video = $("remote-video");
  if (video) video.srcObject = null;
  if (document.fullscreenElement) {
    document.exitFullscreen?.().catch(() => {});
  }
  await releaseWakeLock();
  await signaling.stopListening();
  $("viewer-view").classList.add("hidden");
  $("home-view").classList.remove("hidden");
  await renderHome();
}

function stopQrScanner() {
  if (qrScanner) {
    qrScanner.stop();
    qrScanner = null;
  }
}

function openPairModal(defaultTab = "scan") {
  $("pair-error-msg").classList.add("hidden");
  $("pair-existing-connect-btn").classList.add("hidden");
  $("pair-status-msg").textContent = "";
  $("pair-paste-input").value = "";
  $("pair-modal").classList.remove("hidden");
  switchPairTab(defaultTab);
}

function closePairModal() {
  stopQrScanner();
  $("pair-modal").classList.add("hidden");
}

function switchPairTab(tab) {
  const isScan = tab === "scan";
  $("pair-tab-scan").classList.toggle("active", isScan);
  $("pair-tab-paste").classList.toggle("active", !isScan);
  $("pair-panel-scan").classList.toggle("hidden", !isScan);
  $("pair-panel-paste").classList.toggle("hidden", isScan);
  stopQrScanner();
  if (isScan) {
    qrScanner = createQrWebcamScanner({
      videoEl: $("qr-video"),
      onDetected: (payload) => startPairingFromPayload(payload),
      onError: () => {
        $("pair-error-msg").textContent = t("pair_camera_error");
        $("pair-error-msg").classList.remove("hidden");
        switchPairTab("paste");
      }
    });
    qrScanner.start();
  }
}

async function startPairingFromPayload(payload) {
  $("pair-modal").classList.remove("hidden");
  stopQrScanner();
  $("pair-error-msg").classList.add("hidden");
  $("pair-existing-connect-btn").classList.add("hidden");
  $("pair-status-msg").textContent = t("pair_status_connecting");

  const res = await runPairingHandshake({
    payload,
    storage,
    signaling,
    onStatus: (stage, p) => {
      if (stage === "CONNECTING_BROKER") {
        $("pair-status-msg").textContent = t("pair_status_connecting");
      } else if (stage === "BROKER_RETRYING") {
        $("pair-status-msg").textContent = t("pair_status_retrying_broker");
      } else if (stage === "AUTHENTICATING") {
        $("pair-status-msg").textContent = t("pair_status_authenticating", { name: p.name });
      }
    }
  });

  if (res.ok) {
    closePairModal();
    await renderHome();
    openViewer(res.camera);
    return;
  }

  await signaling.stopListening();
  $("pair-status-msg").textContent = "";
  const errEl = $("pair-error-msg");
  if (res.reason === "MAX_CAMERAS_REACHED") {
    errEl.textContent = t("pair_limit_reached", { max: MAX_PAIRED_CAMERAS });
  } else if (res.reason === "PUBLIC_KEY_MISMATCH") {
    errEl.textContent = t("pair_status_pubkey_mismatch");
  } else if (res.reason === "TIMEOUT") {
    errEl.textContent = t("pair_status_timeout");
    if (res.alreadyPairedCamera) {
      const btn = $("pair-existing-connect-btn");
      btn.textContent = t("pair_timeout_existing_btn", { name: res.alreadyPairedCamera.name });
      btn.classList.remove("hidden");
      btn.onclick = () => {
        closePairModal();
        openViewer(res.alreadyPairedCamera);
      };
    }
  } else {
    errEl.textContent = t("pair_invalid_input");
  }
  errEl.classList.remove("hidden");
}

function openUnpairModal(cam) {
  $("unpair-message").textContent = t("unpair_modal_message", { name: cam.name });
  $("unpair-modal").classList.remove("hidden");
  $("unpair-confirm-btn").onclick = async () => {
    if (activeCamera?.deviceId === cam.deviceId) {
      await exitViewer();
    }
    await storage.deleteCamera(cam.deviceId);
    $("unpair-modal").classList.add("hidden");
    await renderHome();
  };
}

function renderLogs() {
  const container = $("logs-container");
  if (!container) return;
  const list = logger.getEntries();
  if (list.length === 0) {
    container.innerHTML = `<span class="muted">${escapeHtml(t("logs_empty"))}</span>`;
    return;
  }
  container.innerHTML = list
    .map(
      (e) =>
        `<div class="log-${e.level}">[${escapeHtml(e.clock)} ${escapeHtml(e.rel)}] [${escapeHtml(
          e.level
        )}] [${escapeHtml(e.tag)}] ${escapeHtml(e.message)}</div>`
    )
    .join("");
  container.scrollTop = container.scrollHeight;
}

function wireEvents() {
  $("lang-select").addEventListener("change", async (e) => {
    const val = e.target.value;
    await storage.setSetting("locale", val);
    locale = resolveLocale(val === "auto" ? null : val);
    t = createTranslator(locale);
    applyTranslations();
    await renderHome();
  });

  $("ios-pwa-dismiss-btn").addEventListener("click", async () => {
    await storage.setSetting("ios_pwa_banner_dismissed", true);
    $("ios-pwa-banner").classList.add("hidden");
  });

  $("rename-viewer-btn").addEventListener("click", async () => {
    const id = await storage.getOrCreateIdentity();
    $("rename-input").value = id.deviceName;
    $("rename-modal").classList.remove("hidden");
  });
  $("rename-cancel-btn").addEventListener("click", () => $("rename-modal").classList.add("hidden"));
  $("rename-save-btn").addEventListener("click", async () => {
    await storage.setDeviceName($("rename-input").value);
    $("rename-modal").classList.add("hidden");
    await renderHome();
  });

  $("open-pair-btn").addEventListener("click", () => openPairModal("scan"));
  $("pair-close-btn").addEventListener("click", closePairModal);
  $("pair-tab-scan").addEventListener("click", () => switchPairTab("scan"));
  $("pair-tab-paste").addEventListener("click", () => switchPairTab("paste"));
  $("pair-paste-submit").addEventListener("click", () => {
    const parsed = parsePairingInput($("pair-paste-input").value);
    if (!parsed) {
      $("pair-error-msg").textContent = t("pair_invalid_input");
      $("pair-error-msg").classList.remove("hidden");
      return;
    }
    startPairingFromPayload(parsed);
  });

  $("unpair-cancel-btn").addEventListener("click", () => $("unpair-modal").classList.add("hidden"));

  $("open-logs-btn").addEventListener("click", () => {
    renderLogs();
    $("logs-modal").classList.remove("hidden");
  });
  $("logs-close-btn").addEventListener("click", () => $("logs-modal").classList.add("hidden"));
  $("logs-clear-btn").addEventListener("click", () => {
    logger.clear();
    renderLogs();
  });
  $("logs-copy-btn").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(logger.exportText());
      $("logs-copy-btn").textContent = t("logs_copied");
      setTimeout(() => {
        $("logs-copy-btn").textContent = t("logs_copy");
      }, 2000);
    } catch (_) {}
  });
  logger.subscribe(() => {
    if (!$("logs-modal").classList.contains("hidden")) renderLogs();
  });

  // Viewer interactions
  $("viewer-back-btn").addEventListener("click", () => exitViewer());
  const video = $("remote-video");
  video.addEventListener("loadedmetadata", syncVideoLayout);
  video.addEventListener("loadeddata", () => activeSession?.notifyFirstFrame());
  video.addEventListener("playing", () => activeSession?.notifyFirstFrame());
  window.addEventListener("resize", syncVideoLayout);

  $("video-stage").addEventListener("click", () => {
    const now = Date.now();
    if (now - lastTapTime < 280) {
      viewerState.fitMode = viewerState.fitMode === "fit" ? "fill" : "fit";
      syncVideoLayout();
      renderViewerHud();
      resetHudAutoHide();
      lastTapTime = 0;
      return;
    }
    lastTapTime = now;
    viewerState.hudVisible = !viewerState.hudVisible;
    renderViewerHud();
    resetHudAutoHide();
  });

  $("ctrl-mute-btn").addEventListener("click", () => {
    viewerState.audioEnabled = !viewerState.audioEnabled;
    syncAudioElement();
    if (viewerState.audioEnabled) {
      video.play().catch(() => {});
    }
    if (activeCamera) {
      storage.updateCamera(activeCamera.deviceId, { audioEnabled: viewerState.audioEnabled }).catch(() => {});
    }
    renderViewerHud();
    resetHudAutoHide();
  });

  $("ctrl-torch-btn").addEventListener("click", () => {
    const next = !viewerState.torchEnabled;
    viewerState.torchEnabled = next;
    activeSession?.setTorch(next);
    renderViewerHud();
    resetHudAutoHide();
  });

  $("ctrl-night-btn").addEventListener("click", () => {
    const next = nextNightMode(viewerState.nightMode);
    viewerState.nightMode = next;
    activeSession?.setNightMode(next);
    if (activeCamera) {
      storage.updateCamera(activeCamera.deviceId, { nightModeSetting: next }).catch(() => {});
    }
    renderViewerHud();
    resetHudAutoHide();
  });

  $("ctrl-rotate-btn").addEventListener("click", () => {
    viewerState.rotationDeg = (viewerState.rotationDeg + 90) % 360;
    syncVideoLayout();
    resetHudAutoHide();
  });

  $("ctrl-fit-btn").addEventListener("click", () => {
    viewerState.fitMode = viewerState.fitMode === "fit" ? "fill" : "fit";
    syncVideoLayout();
    renderViewerHud();
    resetHudAutoHide();
  });

  $("ctrl-fullscreen-btn").addEventListener("click", () => {
    const el = $("viewer-view");
    if (document.fullscreenElement) {
      document.exitFullscreen?.().catch(() => {});
    } else {
      el.requestFullscreen?.().catch(() => {});
    }
    resetHudAutoHide();
  });

  const pttBtn = $("ctrl-ptt-btn");
  pttBtn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    pttBtn.setPointerCapture?.(e.pointerId);
    activeSession?.startPtt();
    resetHudAutoHide();
  });
  for (const evName of ["pointerup", "pointercancel", "pointerleave"]) {
    pttBtn.addEventListener(evName, () => {
      activeSession?.stopPtt();
    });
  }

  window.addEventListener("keydown", (e) => {
    const tag = document.activeElement?.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA") return;
    if (e.code === "Escape" && activeCamera) {
      e.preventDefault();
      exitViewer();
      return;
    }
    if (e.code === "Space" && !e.repeat && activeSession) {
      e.preventDefault();
      activeSession.startPtt();
    }
  });
  window.addEventListener("popstate", () => {
    if (activeCamera) {
      exitViewer({ fromPopState: true });
    }
  });
  window.addEventListener("keyup", (e) => {
    if (e.code === "Space" && activeSession) {
      activeSession.stopPtt();
    }
  });

  window.addEventListener("online", () => signaling.handleNetworkOnline());
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      signaling.handlePageHidden();
    } else if (document.visibilityState === "visible") {
      signaling.handlePageVisible({ resumeIfWanted: Boolean(activeCamera) });
      if (activeCamera) acquireWakeLock();
    }
  });
  window.addEventListener("pageshow", (ev) => {
    if (ev.persisted && activeCamera) {
      signaling.handlePageVisible({ resumeIfWanted: true });
      activeSession?.reconnect();
      acquireWakeLock();
    }
  });
  window.addEventListener("pagehide", () => {
    activeSession?.stopPtt();
    signaling.stopListening();
    releaseWakeLock();
  });
}

async function bootstrap() {
  // Scrub #pair= from the address bar immediately before any async I/O
  const rawHash = scrubPairHashFromUrl();

  const savedLocalePref = await storage.getSetting("locale", "auto");
  locale = resolveLocale(savedLocalePref === "auto" ? null : savedLocalePref);
  t = createTranslator(locale);
  const langSelect = $("lang-select");
  if (langSelect) langSelect.value = savedLocalePref || "auto";
  applyTranslations();
  wireEvents();

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("./sw.js").catch(() => {});
  }

  await renderHome();

  if (rawHash) {
    const parsed = parsePairingInput(rawHash);
    if (parsed) {
      await startPairingFromPayload(parsed);
    } else {
      openPairModal("paste");
      $("pair-error-msg").textContent = t("pair_invalid_input");
      $("pair-error-msg").classList.remove("hidden");
    }
  }
}

bootstrap().catch((err) => {
  logger.e("App", `Bootstrap error: ${err.message}`);
});
