import { sortOperations } from "./counter";
import type { AppState, CachedSession, CounterOperation } from "./types";

const DATABASE_NAME = "espero-pwa-test";
const DATABASE_VERSION = 1;
const OPERATIONS_STORE = "counter-operations";
const META_STORE = "meta";
const SESSION_META_KEY = "cached-session";

interface MetaRecord<T> {
  key: string;
  value: T;
}

let databasePromise: Promise<IDBDatabase> | null = null;

function requestAsPromise<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener("error", () => reject(request.error ?? new Error("IndexedDB request failed")), {
      once: true,
    });
  });
}

function transactionAsPromise(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.addEventListener("complete", () => resolve(), { once: true });
    transaction.addEventListener(
      "abort",
      () => reject(transaction.error ?? new Error("IndexedDB transaction aborted")),
      { once: true },
    );
    transaction.addEventListener(
      "error",
      () => reject(transaction.error ?? new Error("IndexedDB transaction failed")),
      { once: true },
    );
  });
}

export function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise;

  databasePromise = new Promise((resolve, reject) => {
    const request = window.indexedDB.open(DATABASE_NAME, DATABASE_VERSION);

    request.addEventListener("upgradeneeded", () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(OPERATIONS_STORE)) {
        const store = database.createObjectStore(OPERATIONS_STORE, {
          keyPath: "sequence",
          autoIncrement: true,
        });
        store.createIndex("operationId", "operationId", { unique: true });
        store.createIndex("ownerKey", "ownerKey", { unique: false });
      }

      if (!database.objectStoreNames.contains(META_STORE)) {
        database.createObjectStore(META_STORE, { keyPath: "key" });
      }
    });

    request.addEventListener(
      "success",
      () => {
        const database = request.result;
        database.addEventListener("versionchange", () => {
          database.close();
          databasePromise = null;
        });
        resolve(database);
      },
      { once: true },
    );
    request.addEventListener(
      "error",
      () => {
        databasePromise = null;
        reject(request.error ?? new Error("IndexedDB could not be opened"));
      },
      { once: true },
    );
  });

  return databasePromise;
}

export async function enqueueCounterOperation(
  ownerKey: string,
  delta: -1 | 1,
): Promise<CounterOperation> {
  const database = await openDatabase();
  const transaction = database.transaction(OPERATIONS_STORE, "readwrite");
  const store = transaction.objectStore(OPERATIONS_STORE);
  const draft = {
    operationId: crypto.randomUUID(),
    ownerKey,
    delta,
    createdAt: new Date().toISOString(),
    attempts: 0,
  };
  const sequence = Number(await requestAsPromise(store.add(draft)));
  await transactionAsPromise(transaction);

  return { ...draft, sequence };
}

export async function getPendingOperations(ownerKey: string): Promise<CounterOperation[]> {
  const database = await openDatabase();
  const transaction = database.transaction(OPERATIONS_STORE, "readonly");
  const records = (await requestAsPromise(
    transaction.objectStore(OPERATIONS_STORE).getAll(),
  )) as CounterOperation[];
  await transactionAsPromise(transaction);
  return sortOperations(records.filter((record) => record.ownerKey === ownerKey));
}

export async function getForeignPendingCount(ownerKey: string): Promise<number> {
  const database = await openDatabase();
  const transaction = database.transaction(OPERATIONS_STORE, "readonly");
  const records = (await requestAsPromise(
    transaction.objectStore(OPERATIONS_STORE).getAll(),
  )) as CounterOperation[];
  await transactionAsPromise(transaction);
  return records.filter((record) => record.ownerKey !== ownerKey).length;
}

export async function removeCounterOperation(sequence: number): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(OPERATIONS_STORE, "readwrite");
  transaction.objectStore(OPERATIONS_STORE).delete(sequence);
  await transactionAsPromise(transaction);
}

export async function getCachedSession(): Promise<CachedSession | null> {
  const database = await openDatabase();
  const transaction = database.transaction(META_STORE, "readonly");
  const record = (await requestAsPromise(
    transaction.objectStore(META_STORE).get(SESSION_META_KEY),
  )) as MetaRecord<CachedSession> | undefined;
  await transactionAsPromise(transaction);
  return record?.value ?? null;
}

export async function saveCachedSession(session: CachedSession): Promise<void> {
  const database = await openDatabase();
  const transaction = database.transaction(META_STORE, "readwrite");
  transaction.objectStore(META_STORE).put({ key: SESSION_META_KEY, value: session });
  await transactionAsPromise(transaction);
}

export async function updateCachedState(
  ownerKey: string,
  csrfToken: string,
  state: AppState,
): Promise<void> {
  return saveCachedSession({
    ownerKey,
    csrfToken,
    state,
    cachedAt: new Date().toISOString(),
  });
}
