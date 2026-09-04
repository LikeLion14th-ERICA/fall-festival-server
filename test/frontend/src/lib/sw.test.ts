import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { runInNewContext } from "node:vm";

import { describe, expect, it, vi } from "vitest";

interface PushEventFixture {
  data: { json(): Record<string, unknown> };
  waitUntil(promise: Promise<unknown>): void;
}

describe("service worker push payload", () => {
  it("shows a conventional nested notification and sends the token-only ACK", async () => {
    const listeners: Record<string, (event: unknown) => void> = {};
    const showNotification = vi.fn().mockResolvedValue(undefined);
    const fetchMock = vi.fn().mockResolvedValue({ ok: true });
    const worker = {
      addEventListener(type: string, listener: (event: unknown) => void) {
        listeners[type] = listener;
      },
      crypto: { randomUUID: () => "random-id" },
      location: { origin: "https://example.test" },
      registration: { showNotification },
      skipWaiting: vi.fn(),
    };

    const source = readFileSync(resolve(process.cwd(), "public/sw.js"), "utf8");
    runInNewContext(source, {
      Date,
      Intl,
      JSON,
      Math,
      Promise,
      Response,
      URL,
      caches: {},
      clients: {},
      fetch: fetchMock,
      self: worker,
    });

    let completion: Promise<unknown> | undefined;
    const payload = {
      notification: {
        body: "현재 시각 12:34",
        title: "시간 알림",
      },
      data: {
        ackToken: "one-use-token",
        notificationId: "notice-1",
        runId: "run-7",
        sequence: 3,
      },
    };
    const pushEvent: PushEventFixture = {
      data: { json: () => payload },
      waitUntil(promise) {
        completion = promise;
      },
    };

    listeners.push(pushEvent);
    await completion;

    expect(showNotification).toHaveBeenCalledWith(
      "시간 알림",
      expect.objectContaining({
        body: "현재 시각 12:34",
        tag: "notification-notice-1",
        data: expect.objectContaining({ notificationId: "notice-1", runId: "run-7", sequence: 3 }),
      }),
    );
    expect(showNotification.mock.calls[0][1].data).not.toHaveProperty("ackToken");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/me/notifications/ack",
      expect.objectContaining({
        credentials: "omit",
        body: expect.any(String),
      }),
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({
      ackToken: "one-use-token",
      notificationId: "notice-1",
    });
  });

  it("keeps clock notifications separate when only run and sequence identify them", async () => {
    const listeners: Record<string, (event: unknown) => void> = {};
    const showNotification = vi.fn().mockResolvedValue(undefined);
    const worker = {
      addEventListener(type: string, listener: (event: unknown) => void) {
        listeners[type] = listener;
      },
      crypto: { randomUUID: () => "random-id" },
      location: { origin: "https://example.test" },
      registration: { showNotification },
      skipWaiting: vi.fn(),
    };
    const source = readFileSync(resolve(process.cwd(), "public/sw.js"), "utf8");
    runInNewContext(source, {
      Date,
      Intl,
      JSON,
      Math,
      Promise,
      Response,
      URL,
      caches: {},
      clients: {},
      fetch: vi.fn().mockResolvedValue({ ok: true }),
      self: worker,
    });

    const emit = async (sequence: number) => {
      let completion: Promise<unknown> | undefined;
      listeners.push({
        data: { json: () => ({ body: "12:34", runId: "run-a", sequence }) },
        waitUntil(promise: Promise<unknown>) {
          completion = promise;
        },
      });
      await completion;
    };

    await emit(1);
    await emit(2);

    expect(showNotification.mock.calls.map((call) => call[1].tag)).toEqual([
      "clock-run-a-1",
      "clock-run-a-2",
    ]);
  });
});
