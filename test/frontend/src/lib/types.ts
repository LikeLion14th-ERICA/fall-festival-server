export type Locale = "ko" | "en" | "zh";

export interface CounterState {
  value: number;
  version: number;
}

export interface PushSubscriptionState {
  id: string;
  endpoint?: string;
  createdAt?: string;
  lastAcceptedAt?: string;
}

export type ClockTestStatus = "IDLE" | "ACTIVE" | "COMPLETED" | "STOPPED" | "FAILED";

export interface ClockTestState {
  status: ClockTestStatus;
  runId?: string;
  startedAt?: string;
  endsAt?: string;
  nextScheduledAt?: string;
  sentCount: number;
  failedCount: number;
  totalCount: number;
}

export type NotificationKind = "TEST_NOW" | "CLOCK";

export type NotificationDeliveryStatus =
  | "SCHEDULED"
  | "SENDING"
  | "ACCEPTED"
  | "FAILED"
  | "RECEIVED"
  | "ACKNOWLEDGED";

export interface NotificationHistoryItem {
  id: string;
  kind: NotificationKind;
  sequence?: number;
  status: NotificationDeliveryStatus;
  scheduledAt?: string;
  sentAt?: string;
  acceptedAt?: string;
  acknowledgedAt?: string;
  errorCode?: string;
}

export interface AppState {
  ownerKey?: string;
  counter: CounterState;
  pushSubscriptions: PushSubscriptionState[];
  clockTest: ClockTestState | null;
  notificationHistory: NotificationHistoryItem[];
  serverTime?: string;
  vapidPublicKey?: string;
}

export interface SessionBootstrap {
  csrfToken: string;
  ownerKey: string;
  state: AppState;
}

export interface CounterOperation {
  operationId: string;
  ownerKey: string;
  delta: -1 | 1;
  sequence: number;
  createdAt: string;
  attempts: number;
  lastError?: string;
}

export interface CachedSession {
  ownerKey: string;
  csrfToken: string;
  state: AppState;
  cachedAt: string;
}

export interface CapabilitySnapshot {
  secureContext: boolean;
  serviceWorker: boolean;
  push: boolean;
  notifications: boolean;
  standalone: boolean;
  online: boolean;
  notificationPermission: NotificationPermission | "unsupported";
  platform: "ios" | "android" | "desktop" | "unknown";
}
