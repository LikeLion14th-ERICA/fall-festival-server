import type { CapabilitySnapshot } from "./types";

export interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
}

interface NavigatorWithStandalone extends Navigator {
  standalone?: boolean;
}

export function isStandalone(): boolean {
  if (typeof window === "undefined") return false;
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    (window.navigator as NavigatorWithStandalone).standalone === true
  );
}

export function detectPlatform(): CapabilitySnapshot["platform"] {
  if (typeof window === "undefined") return "unknown";
  const userAgent = window.navigator.userAgent.toLowerCase();
  const isTouchMac = userAgent.includes("macintosh") && window.navigator.maxTouchPoints > 1;
  if (/iphone|ipad|ipod/.test(userAgent) || isTouchMac) return "ios";
  if (userAgent.includes("android")) return "android";
  if (userAgent) return "desktop";
  return "unknown";
}

export function getCapabilitySnapshot(): CapabilitySnapshot {
  const notifications = typeof window !== "undefined" && "Notification" in window;
  return {
    secureContext: window.isSecureContext,
    serviceWorker: "serviceWorker" in window.navigator,
    push: "PushManager" in window,
    notifications,
    standalone: isStandalone(),
    online: window.navigator.onLine,
    notificationPermission: notifications ? window.Notification.permission : "unsupported",
    platform: detectPlatform(),
  };
}

export async function requestPersistentStorage(): Promise<boolean> {
  if (!window.navigator.storage?.persist) return false;
  if (await window.navigator.storage.persisted()) return true;
  return window.navigator.storage.persist();
}
