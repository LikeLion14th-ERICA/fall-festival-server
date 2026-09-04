import { describe, expect, it } from "vitest";

import { clampCounter, projectCounter, sortOperations } from "./counter";
import type { CounterOperation } from "./types";

function operation(sequence: number, delta: -1 | 1): CounterOperation {
  return {
    operationId: `operation-${sequence}`,
    ownerKey: "owner-a",
    delta,
    sequence,
    createdAt: new Date(sequence).toISOString(),
    attempts: 0,
  };
}

describe("counter projection", () => {
  it("keeps values inside the allowed range", () => {
    expect(clampCounter(-1)).toBe(0);
    expect(clampCounter(11)).toBe(10);
  });

  it("applies queued operations in FIFO order", () => {
    const operations = [operation(3, -1), operation(1, 1), operation(2, 1)];

    expect(sortOperations(operations).map((item) => item.sequence)).toEqual([1, 2, 3]);
    expect(projectCounter(0, operations)).toBe(1);
  });

  it("applies boundaries at each operation rather than only at the end", () => {
    expect(projectCounter(0, [operation(1, -1), operation(2, 1)])).toBe(1);
  });
});
