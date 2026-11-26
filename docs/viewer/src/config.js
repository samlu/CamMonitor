// AUTO-GENERATED from cammonitor.config.json — DO NOT EDIT MANUALLY.
// Run `node scripts/sync-web-config.mjs` after editing cammonitor.config.json.
export const APP_CONFIG = Object.freeze({
  "project": {
    "githubOwner": "samlu",
    "githubRepo": "CamMonitor",
    "webViewerBaseUrl": "https://samlu.github.io/CamMonitor/viewer/"
  },
  "signaling": {
    "topicPrefix": "cammonitor",
    "keepAliveSec": 30,
    "maxReconnectBackoffMs": 15000,
    "brokers": [
      {
        "host": "broker.hivemq.com",
        "wssPort": 8884,
        "wssPath": "/mqtt",
        "primary": true
      },
      {
        "host": "broker.emqx.io",
        "wssPort": 8884,
        "wssPath": "/mqtt",
        "primary": false
      }
    ]
  },
  "webrtc": {
    "iceServers": [
      {
        "urls": "stun:stun.l.google.com:19302"
      },
      {
        "urls": "stun:stun1.l.google.com:19302"
      }
    ],
    "video": {
      "width": 1280,
      "height": 720,
      "targetFps": 20
    }
  },
  "limits": {
    "maxPairedCameras": 8,
    "maxConcurrentViewers": 2
  },
  "logging": {
    "webMemoryEntries": 300
  }
});
