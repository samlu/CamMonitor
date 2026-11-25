import { deriveEcdhKey } from "./crypto.js";
import { logger } from "./logger.js";
import { computeTypicalConnectMs } from "./storage.js";
import { sanitizeDeviceName } from "./ui.js";

export const DEFAULT_TIMEOUT_SEC = 15;
export const DEFAULT_MAX_ATTEMPTS = 5;
export const OFFER_RETRY_INTERVAL_MS = 3000;
export const COUNTDOWN_REVEAL_AFTER_SEC = 2;
export const SLOW_HINT_AFTER_SEC = 5;
export const FIRST_FRAME_FALLBACK_TIMEOUT_MS = 1500;
export const AUTO_RETRY_AFTER_TIMEOUT_MS = 10_000;
export const DISCONNECT_RECONNECT_DELAY_MS = 3000;
export const BATTERY_QUERY_INTERVAL_MS = 15_000;
export const PTT_IDLE_RELEASE_MS = 15_000;

export const ICE_SERVERS = [
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun1.l.google.com:19302" }
];

export const ViewerConnectionStep = Object.freeze({
  CONNECTING_NETWORK: 0,
  REACHING_CAMERA: 1,
  ESTABLISHING_LINK: 2,
  STARTING_VIDEO: 3
});

export function createInitialProgress(typicalConnectMs = null) {
  return {
    currentStep: ViewerConnectionStep.CONNECTING_NETWORK,
    step1DurationMs: null,
    step2DurationMs: null,
    step3DurationMs: null,
    step4DurationMs: null,
    attempt: 1,
    maxAttempts: DEFAULT_MAX_ATTEMPTS,
    remainingTimeoutSec: DEFAULT_TIMEOUT_SEC,
    totalTimeoutSec: DEFAULT_TIMEOUT_SEC,
    isCameraWakingUp: false,
    typicalConnectMs
  };
}

export function elapsedWaitSec(progress) {
  return Math.max(0, (progress.totalTimeoutSec || DEFAULT_TIMEOUT_SEC) - (progress.remainingTimeoutSec ?? 0));
}

export function shouldShowCountdown(progress) {
  return (
    progress.currentStep === ViewerConnectionStep.REACHING_CAMERA &&
    (elapsedWaitSec(progress) >= COUNTDOWN_REVEAL_AFTER_SEC || progress.attempt > 1)
  );
}

export function shouldShowSlowHint(progress) {
  return (
    progress.currentStep === ViewerConnectionStep.REACHING_CAMERA &&
    !progress.isCameraWakingUp &&
    elapsedWaitSec(progress) >= SLOW_HINT_AFTER_SEC
  );
}

export function timeoutProgressFraction(progress) {
  if (!progress.totalTimeoutSec || progress.totalTimeoutSec <= 0) return 0;
  const clamped = Math.max(0, Math.min(progress.totalTimeoutSec, progress.remainingTimeoutSec));
  return clamped / progress.totalTimeoutSec;
}

export function formatDurationSec(durationMs) {
  const clampedMs = Math.max(100, Number(durationMs) || 100);
  return (clampedMs / 1000).toFixed(1);
}

export const ControlCommand = {
  torch: (enabled) => ({ cmd: "TORCH", enabled: Boolean(enabled) }),
  torchReport: (enabled) => ({ cmd: "TORCH_REPORT", enabled: Boolean(enabled) }),
  pttStart: () => ({ cmd: "PTT_START" }),
  pttStop: () => ({ cmd: "PTT_STOP" }),
  rename: (name) => ({ cmd: "RENAME", name: String(name) }),
  battery: (pct, charging) => ({ cmd: "BATTERY", pct: Number(pct), charging: Boolean(charging) }),
  queryBattery: () => ({ cmd: "QUERY_BATTERY" }),
  setNightMode: (mode) => ({ cmd: "SET_NIGHT_MODE", mode: String(mode) }),
  nightModeReport: (mode, active) => ({
    cmd: "NIGHT_MODE_REPORT",
    mode: String(mode),
    active: Boolean(active)
  }),
  fpsReport: (fps) => ({ cmd: "FPS_REPORT", fps: Number(fps) })
};

export function parseControlCommand(jsonStr) {
  try {
    const obj = typeof jsonStr === "string" ? JSON.parse(jsonStr) : jsonStr;
    if (!obj || typeof obj.cmd !== "string") return null;
    switch (obj.cmd) {
      case "TORCH":
        if (typeof obj.enabled !== "boolean") return null;
        return { cmd: "TORCH", enabled: obj.enabled };
      case "TORCH_REPORT":
        if (typeof obj.enabled !== "boolean") return null;
        return { cmd: "TORCH_REPORT", enabled: obj.enabled };
      case "PTT_START":
        return { cmd: "PTT_START" };
      case "PTT_STOP":
        return { cmd: "PTT_STOP" };
      case "RENAME":
        if (typeof obj.name !== "string") return null;
        return { cmd: "RENAME", name: obj.name };
      case "BATTERY":
        if (typeof obj.pct !== "number" || !Number.isFinite(obj.pct)) return null;
        return { cmd: "BATTERY", pct: obj.pct, charging: Boolean(obj.charging) };
      case "QUERY_BATTERY":
        return { cmd: "QUERY_BATTERY" };
      case "SET_NIGHT_MODE":
        if (typeof obj.mode !== "string") return null;
        return { cmd: "SET_NIGHT_MODE", mode: obj.mode };
      case "NIGHT_MODE_REPORT":
        if (typeof obj.mode !== "string") return null;
        return { cmd: "NIGHT_MODE_REPORT", mode: obj.mode, active: Boolean(obj.active) };
      case "FPS_REPORT":
        if (typeof obj.fps !== "number" || !Number.isFinite(obj.fps)) return null;
        return { cmd: "FPS_REPORT", fps: obj.fps };
      default:
        return null;
    }
  } catch (_) {
    return null;
  }
}

export function candidateDedupeKey(cand) {
  return `${cand.sdpMid ?? ""}:${cand.sdpMLineIndex ?? 0}:${cand.sdp ?? ""}`;
}

export function nextNightMode(current) {
  if (current === "AUTO") return "NIGHT";
  if (current === "NIGHT") return "NORMAL";
  return "AUTO";
}

export function createWebRtcViewerSession({
  camera,
  storage,
  signaling,
  onStateChange = () => {},
  onProgressChange = () => {},
  onStream = () => {},
  onCommand = () => {},
  onCameraUpdate = () => {},
  onDuckAudio = () => {},
  onMicAcquired = () => {},
  onPttStateChange = () => {},
  peerConnectionFactory = null,
  getUserMediaFn = null,
  nowFn = () => Date.now()
}) {
  let pc = null;
  let audioTransceiver = null;
  const dataChannels = new Set();
  let closed = false;
  let sessionGen = 0;

  let currentState = "CONNECTING";
  let progress = createInitialProgress(computeTypicalConnectMs(camera.connectDurationsMs));
  let connectStartMs = 0;
  let stepStartMs = 0;
  let hasRenderedFirstFrame = false;
  let isRemoteDescSet = false;
  let cameraName = camera.name || "Camera";

  const seenRemoteCandidates = new Set();
  const pendingRemoteCandidates = [];
  const localCandidates = [];
  let localOfferSdp = null;
  let sharedKeyBytes = null;

  let unsubSignalingMsg = null;
  let unsubSignalingConn = null;
  let countdownTimer = null;
  let retryTimer = null;
  let autoRetryTimeoutTimer = null;
  let disconnectReconnectTimer = null;
  let firstFrameFallbackTimer = null;
  let batteryPollTimer = null;
  const burstTimers = [];

  // PTT state
  let micTrack = null;
  let micIdleTimer = null;
  let pttPressSeq = 0;
  let isPttTransmitting = false;

  function setState(nextState, extra = {}) {
    currentState = nextState;
    onStateChange(nextState, extra);
  }

  function updateProgress(patch) {
    progress = { ...progress, ...patch };
    onProgressChange({ ...progress });
  }

  function clearConnectionTimers() {
    clearInterval(countdownTimer);
    countdownTimer = null;
    clearTimeout(retryTimer);
    retryTimer = null;
    clearTimeout(autoRetryTimeoutTimer);
    autoRetryTimeoutTimer = null;
    clearTimeout(disconnectReconnectTimer);
    disconnectReconnectTimer = null;
    clearTimeout(firstFrameFallbackTimer);
    firstFrameFallbackTimer = null;
    clearInterval(batteryPollTimer);
    batteryPollTimer = null;
    while (burstTimers.length) {
      clearTimeout(burstTimers.pop());
    }
  }

  function releaseMicHardware() {
    clearTimeout(micIdleTimer);
    micIdleTimer = null;
    if (audioTransceiver?.sender && typeof audioTransceiver.sender.replaceTrack === "function") {
      try {
        audioTransceiver.sender.replaceTrack(null).catch(() => {});
      } catch (_) {}
    }
    if (micTrack) {
      try {
        micTrack.stop();
      } catch (_) {}
      micTrack = null;
    }
  }

  function scheduleMicIdleRelease() {
    clearTimeout(micIdleTimer);
    micIdleTimer = setTimeout(() => {
      if (!isPttTransmitting) {
        logger.d("PTT", "Releasing idle microphone track after 15s");
        releaseMicHardware();
      }
    }, PTT_IDLE_RELEASE_MS);
  }

  function sendCommand(cmdObj) {
    const payload = JSON.stringify(cmdObj);
    const openChannels = Array.from(dataChannels).filter((dc) => dc.readyState === "open");
    if (openChannels.length === 0) return false;
    const target = openChannels[openChannels.length - 1];
    try {
      target.send(payload);
      return true;
    } catch (err) {
      logger.w("DataChannel", `Send failed: ${err.message}`);
      return false;
    }
  }

  function bindDataChannel(dc) {
    if (!dc || dataChannels.has(dc)) return;
    dataChannels.add(dc);

    const handleOpen = () => {
      logger.i("DataChannel", `DataChannel "${dc.label}" open`);
      sendCommand(ControlCommand.queryBattery());
    };

    dc.onopen = handleOpen;
    if (dc.readyState === "open") {
      handleOpen();
    }
    dc.onmessage = (ev) => {
      const cmd = parseControlCommand(ev.data);
      if (!cmd) return;
      if (cmd.cmd === "RENAME") {
        const sanitized = sanitizeDeviceName(cmd.name, cameraName);
        cameraName = sanitized;
        storage.updateCamera(camera.deviceId, { name: sanitized }).catch(() => {});
        onCameraUpdate({ name: sanitized });
      }
      onCommand(cmd);
    };
    dc.onclose = () => {
      dataChannels.delete(dc);
    };
    dc.onerror = (err) => {
      logger.w("DataChannel", `Error on channel: ${err?.message || "unknown"}`);
    };
  }

  async function flushPendingIce() {
    if (!pc || !isRemoteDescSet) return;
    while (pendingRemoteCandidates.length > 0) {
      const cand = pendingRemoteCandidates.shift();
      try {
        await pc.addIceCandidate(cand);
      } catch (err) {
        logger.w("WebRTC", `Failed adding buffered ICE candidate: ${err.message}`);
      }
    }
  }

  async function addRemoteIceCandidate(msg) {
    const key = candidateDedupeKey(msg);
    if (seenRemoteCandidates.has(key)) return;
    seenRemoteCandidates.add(key);

    const cand = {
      sdpMid: msg.sdpMid,
      sdpMLineIndex: msg.sdpMLineIndex,
      candidate: msg.sdp
    };
    if (pc && isRemoteDescSet) {
      try {
        await pc.addIceCandidate(cand);
      } catch (err) {
        logger.w("WebRTC", `Failed adding remote ICE candidate: ${err.message}`);
      }
    } else {
      pendingRemoteCandidates.push(cand);
    }
  }

  async function completeToLive() {
    if (closed || currentState === "LIVE") return;
    const now = nowFn();
    const step4Dur = Math.max(50, now - stepStartMs);
    const totalDur = Math.max(100, now - connectStartMs);
    updateProgress({
      step4DurationMs: progress.step4DurationMs ?? step4Dur
    });
    setState("LIVE");
    await storage.recordConnectDuration(camera.deviceId, totalDur, now).catch(() => {});
    logger.i("WebRTC", `Stream LIVE (total=${totalDur}ms)`);
  }

  function teardownPeer() {
    clearConnectionTimers();
    if (isPttTransmitting) {
      isPttTransmitting = false;
      onDuckAudio(false);
      onPttStateChange(false);
    }
    releaseMicHardware();
    for (const dc of dataChannels) {
      try {
        dc.close();
      } catch (_) {}
    }
    dataChannels.clear();
    if (pc) {
      try {
        pc.onicecandidate = null;
        pc.ontrack = null;
        pc.ondatachannel = null;
        pc.onconnectionstatechange = null;
        pc.close();
      } catch (_) {}
      pc = null;
    }
    audioTransceiver = null;
    isRemoteDescSet = false;
    localOfferSdp = null;
    localCandidates.length = 0;
    pendingRemoteCandidates.length = 0;
    seenRemoteCandidates.clear();
  }

  async function startAttempt() {
    if (closed) return;
    const gen = ++sessionGen;
    teardownPeer();

    connectStartMs = nowFn();
    stepStartMs = connectStartMs;
    hasRenderedFirstFrame = false;
    setState("CONNECTING");
    const refreshedCam = (await storage.getCamera(camera.deviceId)) || camera;
    updateProgress(createInitialProgress(computeTypicalConnectMs(refreshedCam.connectDurationsMs)));

    const identity = await storage.getOrCreateIdentity();
    sharedKeyBytes = await deriveEcdhKey(identity.ecdhPrivateKey, camera.publicKeyBase64);

    await signaling.startListening(camera.broker || null);
    if (closed || gen !== sessionGen) return;

    const afterMqtt = nowFn();
    const step1Dur = Math.max(50, afterMqtt - stepStartMs);
    stepStartMs = afterMqtt;
    updateProgress({
      currentStep: ViewerConnectionStep.REACHING_CAMERA,
      step1DurationMs: step1Dur,
      attempt: 1,
      remainingTimeoutSec: DEFAULT_TIMEOUT_SEC
    });

    const createPc =
      peerConnectionFactory ||
      ((cfg) => new globalThis.RTCPeerConnection(cfg));
    try {
      pc = createPc({ iceServers: ICE_SERVERS, iceCandidatePoolSize: 2 });
    } catch (err) {
      logger.e("WebRTC", `RTCPeerConnection creation failed: ${err.message}`);
      setState("FAILED", { reason: "INIT_FAILED" });
      return;
    }

    // Mandatory Unified Plan order: video (mid 0, recvonly) -> audio (mid 1, sendrecv) -> DataChannel
    pc.addTransceiver("video", { direction: "recvonly" });
    audioTransceiver = pc.addTransceiver("audio", { direction: "sendrecv" });
    const localDc = pc.createDataChannel("control", { ordered: true });
    bindDataChannel(localDc);

    pc.ondatachannel = (ev) => {
      if (ev?.channel) bindDataChannel(ev.channel);
    };

    const mediaStream = typeof globalThis.MediaStream === "function" ? new globalThis.MediaStream() : null;

    pc.ontrack = (ev) => {
      if (closed || gen !== sessionGen) return;
      const track = ev.track;
      const stream = ev.streams?.[0] || mediaStream;
      if (stream && track && !stream.getTracks().includes(track)) {
        try {
          stream.addTrack(track);
        } catch (_) {}
      }
      if (stream) {
        onStream(stream, track);
      }
    };

    pc.onicecandidate = (ev) => {
      if (closed || gen !== sessionGen || !ev?.candidate || !ev.candidate.candidate) return;
      const candObj = {
        sdpMid: ev.candidate.sdpMid ?? "0",
        sdpMLineIndex: ev.candidate.sdpMLineIndex ?? 0,
        sdp: ev.candidate.candidate
      };
      localCandidates.push(candObj);
      signaling
        .sendSignalingMessage(
          camera.deviceId,
          { type: "ICE_CANDIDATE", ...candObj },
          sharedKeyBytes
        )
        .catch(() => {});
    };

    pc.onconnectionstatechange = () => {
      if (closed || gen !== sessionGen || !pc) return;
      const st = pc.connectionState;
      logger.i("WebRTC", `PeerConnectionState -> ${st}`);
      if (st === "connected") {
        const now = nowFn();
        const step3Dur = Math.max(50, now - stepStartMs);
        stepStartMs = now;
        updateProgress({
          currentStep: ViewerConnectionStep.STARTING_VIDEO,
          step1DurationMs: progress.step1DurationMs ?? 100,
          step2DurationMs: progress.step2DurationMs ?? 200,
          step3DurationMs: step3Dur
        });
        if (hasRenderedFirstFrame) {
          completeToLive();
        } else {
          clearTimeout(firstFrameFallbackTimer);
          firstFrameFallbackTimer = setTimeout(() => {
            if (!closed && gen === sessionGen && pc?.connectionState === "connected") {
              completeToLive();
            }
          }, FIRST_FRAME_FALLBACK_TIMEOUT_MS);
        }
        burstTimers.push(
          setTimeout(() => sendCommand(ControlCommand.queryBattery()), 500),
          setTimeout(() => sendCommand(ControlCommand.queryBattery()), 2000)
        );
        clearInterval(batteryPollTimer);
        batteryPollTimer = setInterval(() => {
          sendCommand(ControlCommand.queryBattery());
        }, BATTERY_QUERY_INTERVAL_MS);
      } else if (st === "failed") {
        setState("FAILED", { reason: "SYMMETRIC_NAT" });
      } else if (st === "disconnected") {
        setState("DISCONNECTED");
        clearTimeout(disconnectReconnectTimer);
        disconnectReconnectTimer = setTimeout(() => {
          if (!closed && gen === sessionGen && currentState === "DISCONNECTED") {
            logger.i("WebRTC", "Auto-reconnecting after 3s disconnect...");
            startAttempt();
          }
        }, DISCONNECT_RECONNECT_DELAY_MS);
      }
    };

    try {
      const offer = await pc.createOffer();
      if (closed || gen !== sessionGen) return;
      await pc.setLocalDescription(offer);
      if (closed || gen !== sessionGen) return;
      localOfferSdp = offer.sdp;
      await signaling.sendSignalingMessage(
        camera.deviceId,
        { type: "OFFER", sdp: localOfferSdp },
        sharedKeyBytes,
        { isOffer: true }
      );
    } catch (err) {
      logger.e("WebRTC", `Offer creation failed: ${err.message}`);
      setState("FAILED", { reason: "OFFER_FAILED" });
      return;
    }

    // Bounded 15s countdown
    let remaining = DEFAULT_TIMEOUT_SEC;
    countdownTimer = setInterval(() => {
      if (closed || gen !== sessionGen || isRemoteDescSet) {
        clearInterval(countdownTimer);
        countdownTimer = null;
        return;
      }
      remaining = Math.max(0, remaining - 1);
      updateProgress({ remainingTimeoutSec: remaining });
      if (remaining <= 0) {
        clearInterval(countdownTimer);
        countdownTimer = null;
        clearTimeout(retryTimer);
        retryTimer = null;
        logger.w("WebRTC", `Camera did not respond within ${DEFAULT_TIMEOUT_SEC}s`);
        setState("CAMERA_TIMEOUT", { timeoutSec: DEFAULT_TIMEOUT_SEC });
        autoRetryTimeoutTimer = setTimeout(() => {
          if (!closed && gen === sessionGen && currentState === "CAMERA_TIMEOUT") {
            logger.i("WebRTC", "Auto-retrying after CameraTimeout...");
            startAttempt();
          }
        }, AUTO_RETRY_AFTER_TIMEOUT_MS);
      }
    }, 1000);

    // Bounded Offer retries (every 3s up to 5 attempts)
    let attempt = 1;
    const scheduleRetry = () => {
      if (closed || gen !== sessionGen || isRemoteDescSet || attempt >= DEFAULT_MAX_ATTEMPTS) return;
      retryTimer = setTimeout(async () => {
        if (
          closed ||
          gen !== sessionGen ||
          isRemoteDescSet ||
          currentState === "CAMERA_TIMEOUT" ||
          !localOfferSdp
        ) {
          return;
        }
        attempt += 1;
        updateProgress({ attempt });
        if (attempt >= 2 && currentState === "CONNECTING") {
          setState("WAITING_RESPONSE");
        }
        await signaling.sendSignalingMessage(
          camera.deviceId,
          { type: "OFFER", sdp: localOfferSdp },
          sharedKeyBytes,
          { isOffer: true }
        );
        for (const cand of localCandidates) {
          signaling
            .sendSignalingMessage(
              camera.deviceId,
              { type: "ICE_CANDIDATE", ...cand },
              sharedKeyBytes
            )
            .catch(() => {});
        }
        scheduleRetry();
      }, OFFER_RETRY_INTERVAL_MS);
    };
    scheduleRetry();
  }

  function start() {
    closed = false;
    unsubSignalingMsg = signaling.onMessage(async ({ senderId, message }) => {
      if (closed || senderId !== camera.deviceId || !message) return;
      switch (message.type) {
        case "CAMERA_WAKING": {
          if (message.cameraName) {
            cameraName = sanitizeDeviceName(message.cameraName, cameraName);
            onCameraUpdate({ name: cameraName });
          }
          updateProgress({ isCameraWakingUp: true });
          break;
        }
        case "ANSWER": {
          clearInterval(countdownTimer);
          countdownTimer = null;
          clearTimeout(retryTimer);
          retryTimer = null;
          const now = nowFn();
          const step2Dur = Math.max(50, now - stepStartMs);
          stepStartMs = now;
          updateProgress({
            currentStep: ViewerConnectionStep.ESTABLISHING_LINK,
            step2DurationMs: step2Dur,
            isCameraWakingUp: false
          });
          if (message.cameraName) {
            cameraName = sanitizeDeviceName(message.cameraName, cameraName);
            storage.updateCamera(camera.deviceId, { name: cameraName }).catch(() => {});
            onCameraUpdate({ name: cameraName });
          }
          if (typeof message.fps === "number") {
            onCameraUpdate({ fps: message.fps });
          }
          if (pc) {
            try {
              await pc.setRemoteDescription({ type: "answer", sdp: message.sdp });
              isRemoteDescSet = true;
              if (currentState !== "LIVE") {
                setState("STARTING_STREAM");
              }
              await flushPendingIce();
              for (const cand of localCandidates) {
                signaling
                  .sendSignalingMessage(
                    camera.deviceId,
                    { type: "ICE_CANDIDATE", ...cand },
                    sharedKeyBytes
                  )
                  .catch(() => {});
              }
            } catch (err) {
              logger.e("WebRTC", `Failed setting remote answer: ${err.message}`);
            }
          }
          break;
        }
        case "ICE_CANDIDATE":
          await addRemoteIceCandidate(message);
          break;
        case "REJECT":
          clearConnectionTimers();
          setState("REJECTED", { reason: message.reason || "REJECTED" });
          break;
        default:
          break;
      }
    });

    unsubSignalingConn = signaling.onConnectionChange((connected) => {
      if (
        connected &&
        !closed &&
        (currentState === "DISCONNECTED" ||
          currentState === "FAILED" ||
          currentState === "CAMERA_TIMEOUT")
      ) {
        logger.i("WebRTC", "Signaling restored — reconnecting camera session");
        startAttempt();
      }
    });

    startAttempt();
  }

  async function startPtt() {
    const seq = ++pttPressSeq;
    clearTimeout(micIdleTimer);
    micIdleTimer = null;

    if (!audioTransceiver?.sender) return false;

    if (!micTrack || micTrack.readyState === "ended") {
      const gum =
        getUserMediaFn ||
        ((c) => globalThis.navigator.mediaDevices.getUserMedia(c));
      try {
        const stream = await gum({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          },
          video: false
        });
        const newTrack = stream.getAudioTracks()[0] || null;
        if (!newTrack) return false;

        // If user released/cancelled while iOS permission prompt was open, abort cleanly
        if (closed || seq !== pttPressSeq) {
          try {
            newTrack.stop();
          } catch (_) {}
          return false;
        }

        micTrack = newTrack;
        await audioTransceiver.sender.replaceTrack(micTrack);
        onMicAcquired();
      } catch (err) {
        logger.w("PTT", `Microphone acquisition failed: ${err.message}`);
        return false;
      }
    }

    if (closed || seq !== pttPressSeq) {
      if (micTrack) micTrack.enabled = false;
      return false;
    }

    micTrack.enabled = true;
    isPttTransmitting = true;
    onDuckAudio(true);
    onPttStateChange(true);
    sendCommand(ControlCommand.pttStart());
    return true;
  }

  function stopPtt() {
    pttPressSeq += 1;
    if (micTrack) {
      micTrack.enabled = false;
      scheduleMicIdleRelease();
    }
    if (isPttTransmitting) {
      isPttTransmitting = false;
      onDuckAudio(false);
      onPttStateChange(false);
      sendCommand(ControlCommand.pttStop());
    }
  }

  return {
    start,
    reconnect: () => startAttempt(),
    notifyFirstFrame() {
      hasRenderedFirstFrame = true;
      if (
        progress.currentStep === ViewerConnectionStep.STARTING_VIDEO ||
        pc?.connectionState === "connected"
      ) {
        completeToLive();
      }
    },
    sendCommand,
    setTorch: (enabled) => sendCommand(ControlCommand.torch(enabled)),
    setNightMode: (mode) => sendCommand(ControlCommand.setNightMode(mode)),
    queryBattery: () => sendCommand(ControlCommand.queryBattery()),
    startPtt,
    stopPtt,
    close() {
      closed = true;
      sessionGen += 1;
      if (unsubSignalingMsg) {
        unsubSignalingMsg();
        unsubSignalingMsg = null;
      }
      if (unsubSignalingConn) {
        unsubSignalingConn();
        unsubSignalingConn = null;
      }
      teardownPeer();
    },
    _getState: () => currentState,
    _getProgress: () => ({ ...progress })
  };
}
