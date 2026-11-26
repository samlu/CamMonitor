const CACHE_NAME = "cammonitor-viewer-v5";
const APP_SHELL_URLS = [
  "./",
  "./index.html",
  "./styles.css",
  "./manifest.webmanifest",
  "./icons/icon-180.png",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./vendor/mqtt.min.js",
  "./vendor/jsQR.js",
  "./src/config.js",
  "./src/app.js",
  "./src/crypto.js",
  "./src/storage.js",
  "./src/signaling.js",
  "./src/pairing.js",
  "./src/webrtc.js",
  "./src/i18n.js",
  "./src/ui.js",
  "./src/logger.js"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL_URLS)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isAppCodeRequest(url) {
  return (
    url.pathname.endsWith("/styles.css") ||
    url.pathname.endsWith("/manifest.webmanifest") ||
    url.pathname.includes("/src/")
  );
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((resp) => {
          if (resp && resp.ok) {
            const copy = resp.clone();
            caches.open(CACHE_NAME).then((c) => c.put("./index.html", copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match("./index.html"))
    );
    return;
  }

  // Network-first for app ES modules & CSS to prevent HTML/JS version skew across deploys
  if (isAppCodeRequest(url)) {
    event.respondWith(
      fetch(request)
        .then((resp) => {
          if (resp && resp.ok) {
            const copy = resp.clone();
            caches.open(CACHE_NAME).then((c) => c.put(request, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match(request))
    );
    return;
  }

  // Cache-first (with background revalidate) for version-pinned vendor bundles & icons
  event.respondWith(
    caches.match(request).then((cached) => {
      const networkFetch = fetch(request)
        .then((resp) => {
          if (resp && resp.ok) {
            const copy = resp.clone();
            caches.open(CACHE_NAME).then((c) => c.put(request, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => cached);
      return cached || networkFetch;
    })
  );
});
