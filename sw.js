// Offline support. Bundle file names are content-hashed and change every build,
// so nothing is precached: pages go network-first, everything else cache-first,
// and each response is stored as it is fetched.
const CACHE = 'photo-unroll-v1';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  const store = (res) => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
    return res;
  };
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).then(store).catch(() => caches.match(req)));
  } else {
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then(store)));
  }
});
