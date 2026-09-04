import type {
  AppState,
  ClockTestState,
  ClockTestStatus,
  CounterState,
  NotificationDeliveryStatus,
  NotificationHistoryItem,
  NotificationKind,
  PushSubscriptionState,
  SessionBootstrap,
} from "./types";

const configuredBasePath = process.env.NEXT_PUBLIC_API_BASE_PATH?.trim();
export const API_BASE_PATH = configuredBasePath?.startsWith("/")
  ? configuredBasePath.replace(/\/$/, "")
  : "/api/v1";

type UnknownRecord = Record<string, unknown>;

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function unwrapEnvelope(value: unknown): unknown {
  if (!isRecord(value)) return value;
  return isRecord(value.data) ? value.data : value;
}

function pickString(record: UnknownRecord, ...keys: string[]): string | undefined {
  for (const key of keys) {
    if (typeof record[key] === "string" && record[key]) return record[key] as string;
  }
  return undefined;
}

function pickNumber(record: UnknownRecord, fallback: number, ...keys: string[]): number {
  for (const key of keys) {
    if (typeof record[key] === "number" && Number.isFinite(record[key])) {
      return record[key] as number;
    }
  }
  return fallback;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

export function normalizeApiError(value: unknown): { code?: string; message?: string } {
  const root = isRecord(value) ? value : {};
  const nested = isRecord(root.error) ? root.error : root;
  return {
    code: pickString(nested, "code", "errorCode") ?? pickString(root, "code", "errorCode"),
    message:
      pickString(nested, "message") ??
      (typeof root.error === "string" ? root.error : undefined) ??
      pickString(root, "message"),
  };
}

function normalizeCounter(value: unknown): CounterState {
  if (typeof value === "number") {
    return { value: Math.min(10, Math.max(0, value)), version: 0 };
  }

  const record = isRecord(value) ? value : {};
  return {
    value: Math.min(10, Math.max(0, pickNumber(record, 0, "value", "count", "counterValue"))),
    version: Math.max(0, pickNumber(record, 0, "version")),
  };
}

function normalizeSubscription(value: unknown): PushSubscriptionState | null {
  if (!isRecord(value)) return null;
  const id = pickString(value, "id", "subscriptionId");
  if (!id) return null;

  return {
    id,
    endpoint: pickString(value, "endpoint"),
    createdAt: pickString(value, "createdAt", "created_at"),
    lastAcceptedAt: pickString(value, "lastAcceptedAt", "last_accepted_at", "lastSuccessAt"),
  };
}

function normalizeClockStatus(value: unknown): ClockTestStatus {
  const status = typeof value === "string" ? value.toUpperCase() : "IDLE";
  if (status === "RUNNING") return "ACTIVE";
  if (status === "CANCELLED" || status === "CANCELED") return "STOPPED";
  if (["IDLE", "ACTIVE", "COMPLETED", "STOPPED", "FAILED"].includes(status)) {
    return status as ClockTestStatus;
  }
  return "IDLE";
}

export function normalizeClockTest(value: unknown): ClockTestState | null {
  const payload = unwrapEnvelope(value);
  if (payload === null || payload === undefined) return null;
  if (!isRecord(payload)) return null;

  if (
    (Object.hasOwn(payload, "clockTest") && payload.clockTest === null) ||
    (Object.hasOwn(payload, "clockTestRun") && payload.clockTestRun === null)
  ) {
    return null;
  }

  const nested = isRecord(payload.clockTest)
    ? payload.clockTest
    : isRecord(payload.clockTestRun)
      ? payload.clockTestRun
      : payload;

  if (Object.keys(nested).length === 0) return null;
  return {
    status: normalizeClockStatus(nested.status),
    runId: pickString(nested, "runId", "id", "testRunId"),
    startedAt: pickString(nested, "startedAt", "startAt"),
    endsAt: pickString(nested, "endsAt", "endAt", "expiresAt"),
    nextScheduledAt: pickString(nested, "nextScheduledAt", "nextAt"),
    sentCount: Math.max(0, pickNumber(nested, 0, "sentCount", "acceptedCount", "deliveredCount")),
    failedCount: Math.max(0, pickNumber(nested, 0, "failedCount")),
    totalCount: Math.max(0, pickNumber(nested, 5, "totalCount", "targetCount")),
  };
}

function normalizeNotificationStatus(value: unknown): NotificationDeliveryStatus {
  const status = typeof value === "string" ? value.toUpperCase() : "SCHEDULED";
  if (status === "DELIVERED" || status === "DISPLAYED") return "RECEIVED";
  if (["SCHEDULED", "SENDING", "ACCEPTED", "FAILED", "RECEIVED", "ACKNOWLEDGED"].includes(status)) {
    return status as NotificationDeliveryStatus;
  }
  return "SCHEDULED";
}

function normalizeNotificationKind(value: unknown): NotificationKind {
  const kind = typeof value === "string" ? value.toUpperCase() : "CLOCK";
  return kind === "TEST_NOW" || kind === "IMMEDIATE" ? "TEST_NOW" : "CLOCK";
}

function normalizeHistoryItem(value: unknown, index: number): NotificationHistoryItem | null {
  if (!isRecord(value)) return null;
  const id = pickString(value, "id", "notificationId", "eventId") ?? `history-${index}`;
  return {
    id,
    kind: normalizeNotificationKind(value.kind ?? value.type),
    sequence:
      typeof value.sequence === "number"
        ? value.sequence
        : typeof value.sequenceNumber === "number"
          ? value.sequenceNumber
          : undefined,
    status: normalizeNotificationStatus(value.status),
    scheduledAt: pickString(value, "scheduledAt", "scheduled_at"),
    sentAt: pickString(value, "sentAt", "sent_at"),
    acceptedAt: pickString(value, "acceptedAt", "accepted_at"),
    acknowledgedAt: pickString(
      value,
      "acknowledgedAt",
      "receivedAt",
      "acknowledged_at",
      "received_at",
    ),
    errorCode: pickString(value, "errorCode", "error_code"),
  };
}

export function normalizeAppState(value: unknown): AppState {
  const payload = unwrapEnvelope(value);
  const root = isRecord(payload) ? payload : {};
  const state = isRecord(root.state) ? root.state : root;
  const subscriptionsValue = state.pushSubscriptions ?? state.subscriptions;
  const historyValue = state.notificationHistory ?? state.notifications ?? state.notificationEvents;

  return {
    ownerKey: pickString(state, "ownerKey", "owner_key") ?? pickString(root, "ownerKey", "owner_key"),
    counter: normalizeCounter(state.counter ?? state.counterValue ?? state.progress),
    pushSubscriptions: asArray(subscriptionsValue)
      .map(normalizeSubscription)
      .filter((item): item is PushSubscriptionState => item !== null),
    clockTest: normalizeClockTest(state.clockTest ?? state.clockTestRun ?? null),
    notificationHistory: asArray(historyValue)
      .map(normalizeHistoryItem)
      .filter((item): item is NotificationHistoryItem => item !== null),
    serverTime: pickString(state, "serverTime", "server_time"),
    vapidPublicKey:
      pickString(state, "vapidPublicKey", "vapid_public_key") ??
      pickString(root, "vapidPublicKey", "vapid_public_key"),
  };
}

export function normalizeSessionBootstrap(value: unknown): SessionBootstrap {
  const payload = unwrapEnvelope(value);
  const root = isRecord(payload) ? payload : {};
  const csrfToken = pickString(root, "csrfToken", "csrf_token");
  const ownerKey = pickString(root, "ownerKey", "owner_key");
  if (!csrfToken || !ownerKey) {
    throw new Error("Session response is missing csrfToken or ownerKey");
  }

  const state = normalizeAppState(root.state ?? root);
  return {
    csrfToken,
    ownerKey,
    state: { ...state, ownerKey },
  };
}

async function request<T>(
  pathname: string,
  init: RequestInit = {},
  csrfToken?: string,
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  if (csrfToken) headers.set("X-CSRF-Token", csrfToken);

  let response: Response;
  try {
    response = await fetch(`${API_BASE_PATH}${pathname}`, {
      ...init,
      headers,
      credentials: "include",
      cache: "no-store",
    });
  } catch (error) {
    throw error instanceof Error ? error : new Error("Network request failed");
  }

  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) {
    const errorPayload = normalizeApiError(payload);
    throw new ApiError(
      errorPayload.message ?? `Request failed with ${response.status}`,
      response.status,
      errorPayload.code,
    );
  }

  return payload as T;
}

export async function bootstrapSession(accessCode?: string): Promise<SessionBootstrap> {
  const payload = await request<unknown>("/session", {
    method: "POST",
    body: JSON.stringify(accessCode ? { accessCode } : {}),
  });
  return normalizeSessionBootstrap(payload);
}

export async function fetchState(): Promise<AppState> {
  return normalizeAppState(await request<unknown>("/me/state"));
}

export async function sendCounterOperation(
  operationId: string,
  delta: -1 | 1,
  csrfToken: string,
): Promise<CounterState> {
  const payload = unwrapEnvelope(
    await request<unknown>(
      "/me/counter-operations",
      { method: "POST", body: JSON.stringify({ operationId, delta }) },
      csrfToken,
    ),
  );
  const root = isRecord(payload) ? payload : {};
  return normalizeCounter(root.counter ?? root);
}

export async function fetchVapidPublicKey(fallback?: string): Promise<string> {
  if (fallback) return fallback;
  const payload = unwrapEnvelope(await request<unknown>("/public-config"));
  const root = isRecord(payload) ? payload : {};
  const key = pickString(root, "vapidPublicKey", "vapid_public_key", "publicKey");
  if (!key) throw new Error("VAPID public key is missing");
  return key;
}

export async function registerPushSubscription(
  subscription: PushSubscriptionJSON,
  csrfToken: string,
  locale: string,
  timeZone: string,
): Promise<PushSubscriptionState | null> {
  const payload = unwrapEnvelope(
    await request<unknown>(
      "/me/push-subscriptions",
      {
        method: "POST",
        body: JSON.stringify({
          endpoint: subscription.endpoint,
          expirationTime: subscription.expirationTime ?? null,
          keys: subscription.keys,
          locale,
          timeZone,
        }),
      },
      csrfToken,
    ),
  );
  const root = isRecord(payload) ? payload : {};
  return normalizeSubscription(root.subscription ?? root);
}

export async function deletePushSubscription(id: string, csrfToken: string): Promise<void> {
  await request<unknown>(
    `/me/push-subscriptions/${encodeURIComponent(id)}`,
    { method: "DELETE" },
    csrfToken,
  );
}

export async function sendImmediateNotification(csrfToken: string): Promise<void> {
  await request<unknown>(
    "/me/notifications/test-now",
    { method: "POST", body: JSON.stringify({}) },
    csrfToken,
  );
}

export async function startClockTest(csrfToken: string): Promise<ClockTestState | null> {
  const payload = await request<unknown>(
    "/me/clock-test",
    { method: "PUT", body: JSON.stringify({ durationMinutes: 5 }) },
    csrfToken,
  );
  return normalizeClockTest(payload);
}

export async function stopClockTest(csrfToken: string): Promise<void> {
  await request<unknown>("/me/clock-test", { method: "DELETE" }, csrfToken);
}

export async function fetchClockTest(): Promise<ClockTestState | null> {
  return normalizeClockTest(await request<unknown>("/me/clock-test"));
}
