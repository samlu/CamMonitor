import test from "node:test";
import assert from "node:assert/strict";
import { generateIdentityKeyPair } from "../src/crypto.js";
import { createMemoryStore } from "../src/storage.js";
import {
  candidateDedupeKey,
  ControlCommand,
  createInitialProgress,
  createWebRtcViewerSession,
  extractCandidateIp,
  isLoopbackIp,
  isPrivateLocalIp,
  nextNightMode,
  parseControlCommand,
  queryLocalNetworkPermissionState,
  shouldFilterRemoteCandidate,
  shouldShowCountdown,
  shouldShowSlowHint,
  timeoutProgressFraction,
  ViewerConnectionStep
} from "../src/webrtc.js";

test("parseControlCommand round-trips all 10 ControlCommand variants", () => {
  const cmds = [
    ControlCommand.torch(true),
    ControlCommand.torchReport(false),
    ControlCommand.pttStart(),
    ControlCommand.pttStop(),
    ControlCommand.rename("Nursery 寶寶房"),
    ControlCommand.battery(85, true),
    ControlCommand.queryBattery(),
    ControlCommand.setNightMode("NIGHT"),
    ControlCommand.nightModeReport("AUTO", true),
    ControlCommand.fpsReport(24)
  ];
  for (const c of cmds) {
    assert.deepEqual(parseControlCommand(JSON.stringify(c)), c);
  }
  assert.equal(parseControlCommand("bad json"), null);
  assert.equal(parseControlCommand(JSON.stringify({ cmd: "UNKNOWN" })), null);
});

test("ViewerConnectionProgress thresholds match Android Models.kt", () => {
  const p0 = createInitialProgress(1200);
  assert.equal(shouldShowCountdown(p0), false);
  assert.equal(shouldShowSlowHint(p0), false);
  assert.equal(timeoutProgressFraction(p0), 1);

  const p2 = {
    ...p0,
    currentStep: ViewerConnectionStep.REACHING_CAMERA,
    remainingTimeoutSec: 13
  };
  assert.equal(shouldShowCountdown(p2), true);
  assert.equal(shouldShowSlowHint(p2), false);

  const p5 = { ...p2, remainingTimeoutSec: 10 };
  assert.equal(shouldShowSlowHint(p5), true);
  assert.equal(shouldShowSlowHint({ ...p5, isCameraWakingUp: true }), false);
});

test("candidateDedupeKey and nextNightMode cycle", () => {
  assert.equal(
    candidateDedupeKey({ sdpMid: "0", sdpMLineIndex: 0, sdp: "cand1" }),
    "0:0:cand1"
  );
  assert.equal(nextNightMode("AUTO"), "NIGHT");
  assert.equal(nextNightMode("NIGHT"), "NORMAL");
  assert.equal(nextNightMode("NORMAL"), "AUTO");
});

test("Chrome LNA remote ICE candidate filter always drops loopback, drops private LAN only when LNA not granted, and preserves srflx with private raddr", async () => {
  const loopback4 = "candidate:1 1 udp 2122129151 127.0.0.1 50942 typ host generation 0";
  const loopback6 = "candidate:2 1 udp 2122202367 ::1 44380 typ host generation 0";
  const lanHost = "candidate:3 1 udp 2122260223 192.168.100.107 55390 typ host generation 0";
  const srflx = "candidate:4 1 udp 1686052607 111.240.66.72 65253 typ srflx raddr 192.168.100.107 rport 55390 generation 0";

  assert.equal(extractCandidateIp(srflx), "111.240.66.72");
  assert.equal(isLoopbackIp("127.0.0.1"), true);
  assert.equal(isLoopbackIp("::1"), true);
  assert.equal(isPrivateLocalIp("192.168.100.107"), true);
  assert.equal(isPrivateLocalIp("10.0.0.5"), true);
  assert.equal(isPrivateLocalIp("172.16.1.2"), true);
  assert.equal(isPrivateLocalIp("111.240.66.72"), false);

  // When LNA is NOT granted (e.g. Chrome 158 "prompt"/"denied"): drop loopback + private host, KEEP srflx!
  assert.equal(shouldFilterRemoteCandidate(loopback4, { localNetworkGranted: false }), true);
  assert.equal(shouldFilterRemoteCandidate(loopback6, { localNetworkGranted: false }), true);
  assert.equal(shouldFilterRemoteCandidate(lanHost, { localNetworkGranted: false }), true);
  assert.equal(shouldFilterRemoteCandidate(srflx, { localNetworkGranted: false }), false);

  // When LNA IS granted (or unsupported browser): drop loopback only, KEEP private host + srflx!
  assert.equal(shouldFilterRemoteCandidate(loopback4, { localNetworkGranted: true }), true);
  assert.equal(shouldFilterRemoteCandidate(loopback6, { localNetworkGranted: true }), true);
  assert.equal(shouldFilterRemoteCandidate(lanHost, { localNetworkGranted: true }), false);
  assert.equal(shouldFilterRemoteCandidate(srflx, { localNetworkGranted: true }), false);

  // Permission query helper
  assert.equal(await queryLocalNetworkPermissionState(null), "unsupported");
  assert.equal(
    await queryLocalNetworkPermissionState({
      async query({ name }) {
        if (name === "local-network") return { state: "prompt" };
        throw new TypeError("unsupported");
      }
    }),
    "prompt"
  );
});

function createMockPeerConnection() {
  const calls = [];
  const addedCandidates = [];
  let remoteDescSet = false;
  const sender = {
    track: null,
    async replaceTrack(t) {
      this.track = t;
      calls.push(["replaceTrack", t]);
    }
  };
  const localDc = {
    label: "control",
    readyState: "connecting",
    sent: [],
    send(d) {
      this.sent.push(d);
    },
    close() {
      this.readyState = "closed";
    }
  };
  const pc = {
    connectionState: "new",
    calls,
    addedCandidates,
    localDc,
    sender,
    isRemoteDescSet: () => remoteDescSet,
    addTransceiver(kind, init) {
      calls.push(["addTransceiver", kind, init.direction]);
      return kind === "audio" ? { sender } : {};
    },
    createDataChannel(label, init) {
      calls.push(["createDataChannel", label, init.ordered]);
      return localDc;
    },
    async createOffer() {
      calls.push(["createOffer"]);
      return { type: "offer", sdp: "v=0\r\nm=video\r\nm=audio\r\nm=application" };
    },
    async setLocalDescription(desc) {
      calls.push(["setLocalDescription", desc.type]);
    },
    async setRemoteDescription(desc) {
      remoteDescSet = true;
      calls.push(["setRemoteDescription", desc.type]);
    },
    async addIceCandidate(cand) {
      addedCandidates.push(cand);
    },
    close() {
      calls.push(["close"]);
    }
  };
  return pc;
}

test("WebRtcViewerSession creates video(recvonly), audio(sendrecv), DataChannel('control') BEFORE createOffer, handles dual DC, ICE buffering/dedup, and PTT pointercancel safety", async () => {
  const pa = await generateIdentityKeyPair();
  const storage = createMemoryStore();
  await storage.getOrCreateIdentity("Test Browser");
  await storage.saveCamera({
    deviceId: "pa-1",
    name: "Living Room",
    publicKeyBase64: pa.publicKeySpkiBase64
  });
  const cam = await storage.getCamera("pa-1");

  const msgHandlers = new Set();
  const connHandlers = new Set();
  const sentSignals = [];
  const signaling = {
    onMessage: (cb) => {
      msgHandlers.add(cb);
      return () => msgHandlers.delete(cb);
    },
    onConnectionChange: (cb) => {
      connHandlers.add(cb);
      return () => connHandlers.delete(cb);
    },
    async startListening() {
      return true;
    },
    async sendSignalingMessage(id, msg) {
      sentSignals.push({ id, msg });
      return true;
    }
  };

  let mockPc;
  const duckStates = [];
  const receivedCommands = [];
  let micAcquireCount = 0;

  // Fake mic prompt that resolves asynchronously so we can cancel mid-prompt
  let resolveGum;
  const fakeTrack = {
    enabled: true,
    readyState: "live",
    stopped: false,
    stop() {
      this.stopped = true;
      this.readyState = "ended";
    }
  };

  const session = createWebRtcViewerSession({
    camera: cam,
    storage,
    signaling,
    peerConnectionFactory: () => {
      mockPc = createMockPeerConnection();
      return mockPc;
    },
    getUserMediaFn: () => {
      micAcquireCount += 1;
      if (micAcquireCount === 1) {
        return new Promise((r) => {
          resolveGum = () => r({ getAudioTracks: () => [{ ...fakeTrack }] });
        });
      }
      return Promise.resolve({ getAudioTracks: () => [fakeTrack] });
    },
    onDuckAudio: (d) => duckStates.push(d),
    onCommand: (cmd) => receivedCommands.push(cmd)
  });

  session.start();
  await new Promise((r) => setTimeout(r, 20));

  // Verify transceiver + DataChannel order BEFORE createOffer
  assert.deepEqual(mockPc.calls.slice(0, 4), [
    ["addTransceiver", "video", "recvonly"],
    ["addTransceiver", "audio", "sendrecv"],
    ["createDataChannel", "control", true],
    ["createOffer"]
  ]);
  assert.equal(sentSignals[0].msg.type, "OFFER");

  // Buffer remote ICE candidate before Answer (and deduplicate duplicate)
  for (const cb of msgHandlers) {
    await cb({ senderId: "pa-1", message: { type: "ICE_CANDIDATE", sdpMid: "0", sdpMLineIndex: 0, sdp: "cand-1" } });
    await cb({ senderId: "pa-1", message: { type: "ICE_CANDIDATE", sdpMid: "0", sdpMLineIndex: 0, sdp: "cand-1" } });
  }
  assert.equal(mockPc.addedCandidates.length, 0);

  // Receive CAMERA_WAKING + ANSWER -> drains buffered candidate once
  for (const cb of msgHandlers) {
    await cb({ senderId: "pa-1", message: { type: "CAMERA_WAKING", cameraName: "Hallway" } });
    await cb({ senderId: "pa-1", message: { type: "ANSWER", sdp: "v=0\r\nans", cameraName: "Hallway", fps: 24 } });
  }
  assert.equal(mockPc.addedCandidates.length, 1);
  assert.equal((await storage.getCamera("pa-1")).name, "Hallway");

  // Dual DataChannel: PA opens a second "control" channel -> viewer receives on PA channel and sends on open channel
  const paDc = {
    label: "control",
    readyState: "open",
    sent: [],
    send(d) {
      this.sent.push(d);
    },
    close() {}
  };
  mockPc.ondatachannel({ channel: paDc });
  assert.ok(paDc.sent.some((s) => JSON.parse(s).cmd === "QUERY_BATTERY"));
  paDc.onmessage({ data: JSON.stringify({ cmd: "BATTERY", pct: 77, charging: true }) });
  assert.deepEqual(receivedCommands[0], { cmd: "BATTERY", pct: 77, charging: true });

  // First-time PTT interrupted by pointercancel while getUserMedia prompt is open -> must abort cleanly!
  const firstPtt = session.startPtt();
  session.stopPtt(); // simulates pointercancel during prompt
  resolveGum();
  assert.equal(await firstPtt, false);
  assert.equal(duckStates.length, 0);
  assert.equal(sentSignals.some((s) => s.msg.type === "PTT_START"), false);

  // Second PTT press succeeds, ducks audio, sends PTT_START / PTT_STOP
  assert.equal(await session.startPtt(), true);
  assert.equal(fakeTrack.enabled, true);
  assert.deepEqual(duckStates, [true]);
  assert.equal(JSON.parse(paDc.sent.at(-1)).cmd, "PTT_START");

  session.stopPtt();
  assert.equal(fakeTrack.enabled, false);
  assert.deepEqual(duckStates, [true, false]);
  assert.equal(JSON.parse(paDc.sent.at(-1)).cmd, "PTT_STOP");

  session.close();
});

test("Chrome LNA filter also drops CGNAT 100.64.0.0/10 and unspecified 0.0.0.0 / :: addresses", () => {
  const cgnat = "candidate:1 1 udp 2122260223 100.64.12.34 50000 typ host";
  const unspecV4 = "candidate:2 1 udp 2122260223 0.0.0.0 50000 typ host";
  const unspecV6 = "candidate:3 1 udp 2122260223 :: 50000 typ host";
  assert.equal(shouldFilterRemoteCandidate(cgnat, { localNetworkGranted: false }), true);
  assert.equal(shouldFilterRemoteCandidate(cgnat, { localNetworkGranted: true }), false);
  assert.equal(shouldFilterRemoteCandidate(unspecV4, { localNetworkGranted: true }), true);
  assert.equal(shouldFilterRemoteCandidate(unspecV6, { localNetworkGranted: true }), true);
});

test("WebRtcViewerSession extends Offer retry on CAMERA_WAKING, handles duplicate/split-brain ANSWER, and fires ICE watchdog retry", async () => {
  const pa = await generateIdentityKeyPair();
  const storage = createMemoryStore();
  await storage.getOrCreateIdentity("Test Browser");
  await storage.saveCamera({
    deviceId: "pa-resilience",
    name: "Porch",
    publicKeyBase64: pa.publicKeySpkiBase64
  });
  const cam = await storage.getCamera("pa-resilience");

  const msgHandlers = new Set();
  const sentOffers = [];
  let forceReconnectCalls = 0;
  const signaling = {
    onMessage: (cb) => {
      msgHandlers.add(cb);
      return () => msgHandlers.delete(cb);
    },
    onConnectionChange: () => () => {},
    async startListening() {
      return true;
    },
    async forceReconnect() {
      forceReconnectCalls += 1;
      return true;
    },
    async sendSignalingMessage(_id, msg) {
      if (msg.type === "OFFER") sentOffers.push(msg);
      return true;
    }
  };

  const pcs = [];
  const states = [];
  const session = createWebRtcViewerSession({
    camera: cam,
    storage,
    signaling,
    offerRetryIntervalMs: 30,
    wakingRetryIntervalMs: 250,
    iceWatchdogMs: 60,
    peerConnectionFactory: () => {
      const pc = createMockPeerConnection();
      pcs.push(pc);
      return pc;
    },
    onStateChange: (st, extra) => states.push({ st, extra })
  });

  session.start();
  await new Promise((r) => setTimeout(r, 15));
  assert.equal(sentOffers.length, 1);

  // CAMERA_WAKING must delay retry well past 30ms so PA isn't clobbered while waking
  for (const cb of msgHandlers) {
    await cb({ senderId: "pa-resilience", message: { type: "CAMERA_WAKING", cameraName: "Porch" } });
  }
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(sentOffers.length, 1, "Offer must NOT retransmit at 30ms after CAMERA_WAKING");

  // Apply Answer #1, then receive identical Answer #1 duplicate (ignored)
  for (const cb of msgHandlers) {
    await cb({ senderId: "pa-resilience", message: { type: "ANSWER", sdp: "v=0\r\nans1" } });
    await cb({ senderId: "pa-resilience", message: { type: "ANSWER", sdp: "v=0\r\nans1" } });
  }
  assert.equal(pcs.length, 1);

  // ICE stalls past 60ms watchdog -> auto-retries once with forceReconnect (creating PC #2)
  await new Promise((r) => setTimeout(r, 95));
  assert.equal(forceReconnectCalls, 1);
  assert.equal(pcs.length, 2);

  // Apply Answer #2 on PC #2, then receive a DIFFERENT Answer #3 while still not connected -> triggers resync (PC #3)
  for (const cb of msgHandlers) {
    await cb({ senderId: "pa-resilience", message: { type: "ANSWER", sdp: "v=0\r\nans2" } });
    await cb({ senderId: "pa-resilience", message: { type: "ANSWER", sdp: "v=0\r\nans3-different" } });
  }
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(pcs.length, 3);

  session.close();
});

test("WebRtcViewerSession fails cleanly with SIGNALING_FAILED when MQTT cannot connect", async () => {
  const pa = await generateIdentityKeyPair();
  const storage = createMemoryStore();
  await storage.getOrCreateIdentity("Test Browser");
  await storage.saveCamera({
    deviceId: "pa-offline",
    name: "Garage",
    publicKeyBase64: pa.publicKeySpkiBase64
  });
  const cam = await storage.getCamera("pa-offline");

  let forceCalls = 0;
  const signaling = {
    onMessage: () => () => {},
    onConnectionChange: () => () => {},
    async startListening() {
      return false;
    },
    async forceReconnect() {
      forceCalls += 1;
      return false;
    },
    async sendSignalingMessage() {
      return false;
    }
  };

  const states = [];
  const session = createWebRtcViewerSession({
    camera: cam,
    storage,
    signaling,
    peerConnectionFactory: () => createMockPeerConnection(),
    onStateChange: (st, extra) => states.push({ st, extra })
  });

  session.start();
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(forceCalls, 1);
  assert.equal(states.at(-1).st, "FAILED");
  assert.equal(states.at(-1).extra.reason, "SIGNALING_FAILED");
  session.close();
});
