import { describe, expect, it } from "vitest";

import {
  normalizeApiError,
  normalizeAppState,
  normalizeClockTest,
  normalizeSessionBootstrap,
} from "./api";

describe("API response normalization", () => {
  it("unwraps the shared data envelope", () => {
    const session = normalizeSessionBootstrap({
      data: {
        csrfToken: "csrf",
        ownerKey: "owner",
        state: {
          counter: { value: 4, version: 2 },
          pushSubscriptions: [],
          notificationHistory: [],
        },
      },
    });

    expect(session.ownerKey).toBe("owner");
    expect(session.state.counter).toEqual({ value: 4, version: 2 });
  });

  it("accepts flat state fields while preserving safe defaults", () => {
    const state = normalizeAppState({ counterValue: 15 });

    expect(state.counter.value).toBe(10);
    expect(state.pushSubscriptions).toEqual([]);
    expect(state.notificationHistory).toEqual([]);
  });

  it("maps a running clock response to ACTIVE", () => {
    expect(normalizeClockTest({ data: { status: "RUNNING", acceptedCount: 2 } })).toMatchObject({
      status: "ACTIVE",
      sentCount: 2,
      totalCount: 5,
    });
  });

  it("keeps an explicitly empty clock run as null", () => {
    expect(normalizeClockTest({ data: { clockTest: null } })).toBeNull();
  });

  it("reads the backend's nested error envelope", () => {
    expect(
      normalizeApiError({ error: { code: "ACCESS_CODE_INVALID", message: "Invalid code" } }),
    ).toEqual({ code: "ACCESS_CODE_INVALID", message: "Invalid code" });
  });
});
