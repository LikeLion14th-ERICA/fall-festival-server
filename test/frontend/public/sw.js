/* global clients */

const SHELL_CACHE = "espero-pwa-shell-v2";
const SHELL_ASSETS = [
  "/",
  "/icon-192.png",
  "/icon-512.png",
  "/icon-maskable-512.png",
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-maskable.svg",
];
const DEFAULT_ACK_URL = "/test-api/me/notifications/ack";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => Promise.allSettled(SHELL_ASSETS.map((asset) => cache.add(asset))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key))),
      )
      .then(() => clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/test-api/")) return;

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(SHELL_CACHE).then((cache) => cache.put("/", copy));
          }
          return response;
        })
        .catch(async () => (await caches.match(request)) ?? (await caches.match("/")) ?? Response.error()),
    );
    return;
  }

  if (["style", "script", "image", "font"].includes(request.destination)) {
    event.respondWith(
      caches.match(request).then((cached) => {
        const network = fetch(request).then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(SHELL_CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        });
        return cached ?? network;
      }),
    );
  }
});

function readPushPayload(event) {
  if (!event.data) return {};
  try {
    const value = event.data.json();
    return value && typeof value === "object" ? value : {};
  } catch {
    return { body: event.data.text() };
  }
}

function pickValue(...values) {
  return values.find((value) => typeof value === "string" && value.length > 0);
}

function uniqueNotificationTag({ notificationId, runId, sequence }) {
  if (notificationId) return `notification-${notificationId}`;
  if (runId && sequence !== undefined) return `clock-${runId}-${sequence}`;
  if (self.crypto?.randomUUID) return `message-${self.crypto.randomUUID()}`;
  return `message-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function acknowledgeReceipt(notificationId, ackToken, ackUrl) {
  if (!notificationId || !ackToken) return;
  try {
    await fetch(ackUrl || DEFAULT_ACK_URL, {
      method: "POST",
      credentials: "omit",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        notificationId,
        ackToken,
        receivedAt: new Date().toISOString(),
      }),
    });
  } catch {
    // Receipt acknowledgement is best-effort and must never suppress the visible notification.
  }
}

self.addEventListener("push", (event) => {
  const raw = readPushPayload(event);
  // Support both Declarative Web Push and the conventional nested
  // notification envelope used by Web Push libraries.
  const declarative = raw.notification && typeof raw.notification === "object" ? raw.notification : {};
  const nestedData = declarative.data && typeof declarative.data === "object" ? declarative.data : {};
  const rawData = raw.data && typeof raw.data === "object" ? raw.data : {};
  const data = { ...rawData, ...nestedData };

  const notificationId = pickValue(raw.notificationId, declarative.notificationId, data.notificationId);
  const ackToken = pickValue(raw.ackToken, declarative.ackToken, data.ackToken);
  const runId = pickValue(raw.runId, declarative.runId, data.runId);
  const sequence = raw.sequence ?? declarative.sequence ?? data.sequence;
  const destination = pickValue(
    raw.url,
    raw.navigate,
    declarative.navigate,
    data.url,
    data.navigate,
    "/",
  );
  const title = pickValue(raw.title, declarative.title, "Espero") ?? "Espero";
  const body =
    pickValue(raw.body, declarative.body) ??
    new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(new Date());
  const tag = uniqueNotificationTag({ notificationId, runId, sequence });

  event.waitUntil(
    (async () => {
      await self.registration.showNotification(title, {
        body,
        icon: pickValue(raw.icon, declarative.icon, "/icon-192.png"),
        badge: pickValue(raw.badge, declarative.badge, "/icon-maskable-512.png"),
        lang: pickValue(raw.lang, declarative.lang),
        tag,
        renotify: false,
        requireInteraction: false,
        timestamp: Date.parse(pickValue(raw.sentAt, data.sentAt) ?? "") || Date.now(),
        data: {
          url: destination,
          notificationId,
          runId,
          sequence,
        },
      });

      await acknowledgeReceipt(
        notificationId,
        ackToken,
        pickValue(raw.ackUrl, declarative.ackUrl, data.ackUrl),
      );
    })(),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    (async () => {
      let destination = new URL("/", self.location.origin);
      try {
        const requested = new URL(event.notification.data?.url ?? "/", self.location.origin);
        if (requested.origin === self.location.origin) destination = requested;
      } catch {
        // Keep the safe same-origin fallback.
      }

      const windows = await clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin !== self.location.origin) continue;
        if ("navigate" in client) await client.navigate(destination.href);
        return client.focus();
      }
      return clients.openWindow(destination.href);
    })(),
  );
});
