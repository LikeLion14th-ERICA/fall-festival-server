import type { CounterOperation } from "./types";

export const MIN_COUNTER = 0;
export const MAX_COUNTER = 10;

export function clampCounter(value: number): number {
  return Math.min(MAX_COUNTER, Math.max(MIN_COUNTER, value));
}

export function sortOperations(operations: CounterOperation[]): CounterOperation[] {
  return [...operations].sort((left, right) => {
    if (left.sequence !== right.sequence) {
      return left.sequence - right.sequence;
    }

    return left.operationId.localeCompare(right.operationId);
  });
}

export function projectCounter(serverValue: number, operations: CounterOperation[]): number {
  return sortOperations(operations).reduce(
    (value, operation) => clampCounter(value + operation.delta),
    clampCounter(serverValue),
  );
}
