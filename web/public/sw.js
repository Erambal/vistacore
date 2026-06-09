/* ═══════════════════════════════════════════
   VistaCore — Service Worker
   App-shell caching for instant loads + offline
   shell. Never touches /api/ (live proxies,
   streams, auth) or cross-origin requests.
   ═══════════════════════════════════════════ */

const CACHE = 'vistacore-shell-v2';

// Core files that make up the app shell.
const SHELL = [
  '/',
  '/index.html',
  '/styles.css',
  '/app.js',
  '/iptv.js',
  '/player.js',
  '/manifest.json',
  '/assets/logo.png',
  '/assets/icon-192.png',
  '/assets/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // addAll is atomic — use individual puts so one missing asset
      // doesn't abort the whole install.
      .then((cache) => Promise.all(
        SHELL.map((url) => cache.add(url).catch(() => null))
      ))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;

  // Only handle same-origin GETs. Everything else (POST auth, cross-origin
  // fonts/CDNs/Google) goes straight to the network untouched.
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // Never cache or intercept API traffic — proxies, streams, EPG, images,
  // TMDB and auth must always hit the Worker live.
  if (url.pathname.startsWith('/api/')) return;

  // Navigations: network-first so users get fresh HTML, fall back to the
  // cached shell when offline.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((resp) => {
          const copy = resp.clone();
          caches.open(CACHE).then((c) => c.put('/index.html', copy)).catch(() => {});
          return resp;
        })
        .catch(() => caches.match('/index.html').then((r) => r || caches.match('/')))
    );
    return;
  }

  // Code (JS/CSS): network-first so a fresh deploy is picked up on the next
  // load, falling back to cache only when offline. Avoids serving stale logic.
  if (/\.(js|css)$/.test(url.pathname)) {
    event.respondWith(
      fetch(req)
        .then((resp) => {
          if (resp && resp.ok) {
            const copy = resp.clone();
            caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match(req))
    );
    return;
  }

  // Other static assets (images, fonts, manifest): stale-while-revalidate —
  // instant from cache, refreshed in the background.
  event.respondWith(
    caches.match(req).then((cached) => {
      const network = fetch(req)
        .then((resp) => {
          if (resp && resp.ok) {
            const copy = resp.clone();
            caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});
