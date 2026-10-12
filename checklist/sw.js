// Service worker do app Check List: só repassa as requisições para a internet
// (sem guardar cópia), para o app sempre abrir a versão mais nova.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', (e) => { if (e.request.method === 'GET') e.respondWith(fetch(e.request)); });
