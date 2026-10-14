// Service worker do app Koneg (/checklist/).
// Com internet: busca sempre a versão mais nova e guarda uma cópia.
// Sem internet: abre a última cópia guardada (os dados vêm do cache do Firestore).
const CACHE = 'koneg-app-v2';
const EXTERNOS = ['www.gstatic.com', 'fonts.googleapis.com', 'fonts.gstatic.com', 'cdnjs.cloudflare.com'];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/checklist/', '/icones/logo-koneg.png'])).catch(() => {}));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Bibliotecas e fontes de fora (endereços com versão fixa): usa a cópia, senão baixa e guarda
  if (EXTERNOS.includes(url.hostname)) {
    e.respondWith(caches.match(req).then((c) => c || fetch(req).then((r) => {
      if (r && (r.ok || r.type === 'opaque')) { const cp = r.clone(); caches.open(CACHE).then((ca) => ca.put(req, cp)); }
      return r;
    })));
    return;
  }
  if (url.origin !== self.location.origin) return;

  // Páginas e arquivos do próprio site: internet primeiro, cópia guardada se estiver sem conexão
  e.respondWith(fetch(req).then((r) => {
    if (r && r.ok) { const cp = r.clone(); caches.open(CACHE).then((ca) => ca.put(req.mode === 'navigate' ? '/checklist/' : req, cp)); }
    return r;
  }).catch(() => (req.mode === 'navigate' ? caches.match('/checklist/') : caches.match(req))
    .then((c) => c || Response.error())));
});
