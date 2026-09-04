"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  ApiError,
  bootstrapSession,
  deletePushSubscription,
  fetchClockTest,
  fetchState,
  fetchVapidPublicKey,
  registerPushSubscription,
  sendCounterOperation,
  sendImmediateNotification,
  startClockTest,
  stopClockTest,
} from "@/lib/api";
import { MAX_COUNTER, MIN_COUNTER, projectCounter } from "@/lib/counter";
import {
  enqueueCounterOperation,
  getCachedSession,
  getForeignPendingCount,
  getPendingOperations,
  removeCounterOperation,
  updateCachedState,
} from "@/lib/idb";
import {
  detectLocale,
  formatDateTime,
  localeNames,
  saveLocale,
  translate,
  type MessageKey,
} from "@/lib/i18n";
import {
  getCapabilitySnapshot,
  isStandalone,
  requestPersistentStorage,
  type BeforeInstallPromptEvent,
} from "@/lib/platform";
import {
  createBrowserSubscription,
  getBrowserSubscription,
  registerServiceWorker,
} from "@/lib/push";
import type {
  AppState,
  CachedSession,
  CapabilitySnapshot,
  ClockTestStatus,
  CounterOperation,
  Locale,
  NotificationDeliveryStatus,
  NotificationHistoryItem,
  SessionBootstrap,
} from "@/lib/types";

type Phase = "diagnosing" | "preinstall" | "checking" | "access" | "unavailable" | "ready";
type BusyAction =
  | "access"
  | "counter"
  | "enable-push"
  | "disable-push"
  | "test-now"
  | "start-clock"
  | "stop-clock"
  | "refresh"
  | null;
type NoticeTone = "info" | "success" | "warning" | "error";

interface Notice {
  key: MessageKey;
  tone: NoticeTone;
}

const HISTORY_LIMIT = 20;

function mergeState(current: AppState, incoming: AppState): AppState {
  return {
    ...incoming,
    ownerKey: incoming.ownerKey ?? current.ownerKey,
    vapidPublicKey: incoming.vapidPublicKey ?? current.vapidPublicKey,
  };
}

function isAuthenticationError(error: unknown): error is ApiError {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}

function isPermanentOperationError(error: unknown): error is ApiError {
  return error instanceof ApiError && [400, 409, 422].includes(error.status);
}

function historyTimestamp(item: NotificationHistoryItem): number {
  const value = item.acknowledgedAt ?? item.acceptedAt ?? item.sentAt ?? item.scheduledAt;
  const parsed = value ? Date.parse(value) : Number.NaN;
  return Number.isNaN(parsed) ? 0 : parsed;
}

export function PwaTestApp() {
  const [locale, setLocale] = useState<Locale>("ko");
  const [phase, setPhase] = useState<Phase>("diagnosing");
  const [capabilities, setCapabilities] = useState<CapabilitySnapshot | null>(null);
  const [installPrompt, setInstallPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [session, setSession] = useState<SessionBootstrap | null>(null);
  const [appState, setAppState] = useState<AppState | null>(null);
  const [pendingOperations, setPendingOperations] = useState<CounterOperation[]>([]);
  const [foreignPendingCount, setForeignPendingCount] = useState(0);
  const [browserSubscription, setBrowserSubscription] = useState<PushSubscription | null>(null);
  const [serverSubscriptionId, setServerSubscriptionId] = useState<string | null>(null);
  const [accessCode, setAccessCode] = useState("");
  const [accessFeedback, setAccessFeedback] = useState<"invalid" | "unavailable" | null>(null);
  const [busyAction, setBusyAction] = useState<BusyAction>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);
  const [storageAvailable, setStorageAvailable] = useState(true);

  const sessionRef = useRef<SessionBootstrap | null>(null);
  const stateRef = useRef<AppState | null>(null);
  const pendingRef = useRef<CounterOperation[]>([]);
  const verifiedRef = useRef(false);
  const flushPromiseRef = useRef<Promise<void> | null>(null);

  const t = useCallback(
    (key: MessageKey, values?: Record<string, string | number>) => translate(locale, key, values),
    [locale],
  );

  const refreshCapabilities = useCallback(() => {
    const snapshot = getCapabilitySnapshot();
    setCapabilities(snapshot);
    return snapshot;
  }, []);

  const replacePendingOperations = useCallback((operations: CounterOperation[]) => {
    pendingRef.current = operations;
    setPendingOperations(operations);
  }, []);

  const commitState = useCallback(async (nextState: AppState) => {
    stateRef.current = nextState;
    setAppState(nextState);
    setLastUpdatedAt(new Date().toISOString());

    const currentSession = sessionRef.current;
    if (!currentSession) return;
    try {
      await updateCachedState(currentSession.ownerKey, currentSession.csrfToken, nextState);
    } catch {
      setStorageAvailable(false);
      setNotice({ key: "syncFailed", tone: "warning" });
    }
  }, []);

  const hydrateSession = useCallback(
    async (nextSession: SessionBootstrap | CachedSession, verified: boolean) => {
      const normalizedSession: SessionBootstrap = {
        csrfToken: nextSession.csrfToken,
        ownerKey: nextSession.ownerKey,
        state: { ...nextSession.state, ownerKey: nextSession.ownerKey },
      };

      sessionRef.current = normalizedSession;
      stateRef.current = normalizedSession.state;
      verifiedRef.current = verified;
      setSession(normalizedSession);
      setAppState(normalizedSession.state);
      setLastUpdatedAt(
        "cachedAt" in nextSession ? nextSession.cachedAt : new Date().toISOString(),
      );
      // State deliberately omits endpoint details, so the current browser's
      // subscription id is resolved by the idempotent upsert below.
      setServerSubscriptionId(null);

      try {
        const [pending, foreign] = await Promise.all([
          getPendingOperations(normalizedSession.ownerKey),
          getForeignPendingCount(normalizedSession.ownerKey),
          updateCachedState(
            normalizedSession.ownerKey,
            normalizedSession.csrfToken,
            normalizedSession.state,
          ),
        ]);
        replacePendingOperations(pending);
        setForeignPendingCount(foreign);
        setStorageAvailable(true);
      } catch {
        replacePendingOperations([]);
        setStorageAvailable(false);
        setNotice({ key: "syncFailed", tone: "warning" });
      }

      setPhase("ready");
      if (verified) void requestPersistentStorage().catch(() => false);
    },
    [replacePendingOperations],
  );

  const checkInstalledSession = useCallback(
    async (submittedAccessCode?: string) => {
      setBusyAction(submittedAccessCode ? "access" : null);
      setAccessFeedback(null);
      setNotice(null);
      if (!submittedAccessCode) setPhase("checking");

      let cachedSession: CachedSession | null = null;
      try {
        cachedSession = await getCachedSession();
      } catch {
        setStorageAvailable(false);
      }

      if (!window.navigator.onLine) {
        if (cachedSession && !submittedAccessCode) {
          await hydrateSession(cachedSession, false);
          setNotice({ key: "serverUnavailable", tone: "warning" });
        } else if (submittedAccessCode) {
          setPhase("access");
          setAccessFeedback("unavailable");
        } else {
          setPhase("unavailable");
        }
        setBusyAction(null);
        return;
      }

      try {
        const activeSession = await bootstrapSession(submittedAccessCode);
        await hydrateSession(activeSession, true);
        setAccessCode("");
      } catch (error) {
        if (isAuthenticationError(error)) {
          verifiedRef.current = false;
          sessionRef.current = null;
          stateRef.current = null;
          setSession(null);
          setAppState(null);
          setPhase("access");
          setAccessFeedback(submittedAccessCode ? "invalid" : null);
        } else if (submittedAccessCode) {
          setPhase("access");
          setAccessFeedback("unavailable");
        } else if (cachedSession) {
          await hydrateSession(cachedSession, false);
          setNotice({ key: "serverUnavailable", tone: "warning" });
        } else {
          setPhase("unavailable");
        }
      } finally {
        setBusyAction(null);
      }
    },
    [hydrateSession],
  );

  const reloadServerState = useCallback(
    async (quiet = false) => {
      const currentSession = sessionRef.current;
      if (!currentSession || !window.navigator.onLine || !verifiedRef.current) return;
      if (!quiet) setBusyAction("refresh");

      try {
        const [nextState, clockTest] = await Promise.all([
          fetchState(),
          fetchClockTest().catch(() => stateRef.current?.clockTest ?? null),
        ]);
        const merged = mergeState(stateRef.current ?? nextState, {
          ...nextState,
          ownerKey: currentSession.ownerKey,
          clockTest,
        });
        await commitState(merged);
        if (!quiet) setNotice({ key: "refreshed", tone: "success" });
      } catch (error) {
        if (isAuthenticationError(error)) {
          verifiedRef.current = false;
          sessionRef.current = null;
          setSession(null);
          setPhase("access");
          setNotice({ key: "sessionExpired", tone: "warning" });
        } else if (!quiet) {
          setNotice({ key: "serverUnavailable", tone: "error" });
        }
      } finally {
        if (!quiet) setBusyAction(null);
      }
    },
    [commitState],
  );

  const flushQueue = useCallback((): Promise<void> => {
    if (flushPromiseRef.current) return flushPromiseRef.current;

    const task = (async () => {
      const currentSession = sessionRef.current;
      if (!currentSession || !window.navigator.onLine || !verifiedRef.current) return;

      let operations: CounterOperation[];
      try {
        operations = await getPendingOperations(currentSession.ownerKey);
        replacePendingOperations(operations);
      } catch {
        setStorageAvailable(false);
        setNotice({ key: "syncFailed", tone: "warning" });
        return;
      }

      for (const operation of operations) {
        try {
          const counter = await sendCounterOperation(
            operation.operationId,
            operation.delta,
            currentSession.csrfToken,
          );
          await removeCounterOperation(operation.sequence);
          const remaining = pendingRef.current.filter(
            (pending) => pending.sequence !== operation.sequence,
          );
          replacePendingOperations(remaining);

          const currentState = stateRef.current;
          if (currentState) await commitState({ ...currentState, counter });
        } catch (error) {
          if (isAuthenticationError(error)) {
            verifiedRef.current = false;
            sessionRef.current = null;
            setSession(null);
            setPhase("access");
            setNotice({ key: "sessionExpired", tone: "warning" });
            break;
          }

          if (isPermanentOperationError(error)) {
            await removeCounterOperation(operation.sequence).catch(() => undefined);
            const remaining = pendingRef.current.filter(
              (pending) => pending.sequence !== operation.sequence,
            );
            replacePendingOperations(remaining);
            await reloadServerState(true);
          } else {
            setNotice({ key: "syncFailed", tone: "warning" });
            break;
          }
        }
      }
    })();

    flushPromiseRef.current = task.finally(() => {
      flushPromiseRef.current = null;
    });
    return flushPromiseRef.current;
  }, [commitState, reloadServerState, replacePendingOperations]);

  useEffect(() => {
    const detectedLocale = detectLocale();
    // Locale lives in browser storage, so it can only be reconciled after hydration.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLocale(detectedLocale);
    saveLocale(detectedLocale);

    const initialCapabilities = refreshCapabilities();
    if (initialCapabilities.serviceWorker && initialCapabilities.secureContext) {
      void registerServiceWorker().catch(() => refreshCapabilities());
    }

    const onBeforeInstallPrompt = (event: Event) => {
      event.preventDefault();
      setInstallPrompt(event as BeforeInstallPromptEvent);
    };
    const onAppInstalled = () => {
      setInstallPrompt(null);
      setNotice({ key: "installDone", tone: "success" });
      refreshCapabilities();
    };
    const onOnline = () => {
      refreshCapabilities();
      if (isStandalone()) void checkInstalledSession();
    };
    const onOffline = () => refreshCapabilities();
    const onVisibilityChange = () => {
      if (document.visibilityState !== "visible" || !isStandalone()) return;
      refreshCapabilities();
      if (verifiedRef.current) {
        void reloadServerState(true).then(() => flushQueue());
      } else if (window.navigator.onLine) {
        void checkInstalledSession();
      }
    };

    window.addEventListener("beforeinstallprompt", onBeforeInstallPrompt);
    window.addEventListener("appinstalled", onAppInstalled);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    document.addEventListener("visibilitychange", onVisibilityChange);

    if (initialCapabilities.standalone) {
      void checkInstalledSession();
    } else {
      setPhase("preinstall");
    }

    return () => {
      window.removeEventListener("beforeinstallprompt", onBeforeInstallPrompt);
      window.removeEventListener("appinstalled", onAppInstalled);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [checkInstalledSession, flushQueue, refreshCapabilities, reloadServerState]);

  useEffect(() => {
    if (phase !== "ready" || !session || !verifiedRef.current) return;
    void flushQueue();

    if (!capabilities?.push || !capabilities.notifications) return;
    void (async () => {
      try {
        const subscription = await getBrowserSubscription();
        setBrowserSubscription(subscription);
        if (!subscription) return;
        const registered = await registerPushSubscription(
          subscription.toJSON(),
          session.csrfToken,
          locale,
          Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Seoul",
        );
        if (registered) {
          setServerSubscriptionId(registered.id);
          const currentState = stateRef.current;
          if (
            currentState &&
            !currentState.pushSubscriptions.some((item) => item.id === registered.id)
          ) {
            await commitState({
              ...currentState,
              pushSubscriptions: [...currentState.pushSubscriptions, registered],
            });
          }
        }
      } catch {
        setNotice({ key: "serverUnavailable", tone: "warning" });
      }
    })();
  }, [
    capabilities?.notifications,
    capabilities?.push,
    commitState,
    flushQueue,
    locale,
    phase,
    session,
  ]);

  const clockTestNeedsPolling =
    appState?.clockTest?.status === "ACTIVE" ||
    (appState?.clockTest?.status === "COMPLETED" &&
      appState.clockTest.sentCount + appState.clockTest.failedCount <
        (appState.clockTest.totalCount || 5));

  useEffect(() => {
    if (phase !== "ready" || !clockTestNeedsPolling) return;
    const timer = window.setInterval(() => void reloadServerState(true), 10_000);
    return () => window.clearInterval(timer);
  }, [clockTestNeedsPolling, phase, reloadServerState]);

  const displayedCounter = useMemo(
    () => projectCounter(appState?.counter.value ?? 0, pendingOperations),
    [appState?.counter.value, pendingOperations],
  );

  const sortedHistory = useMemo(
    () =>
      [...(appState?.notificationHistory ?? [])]
        .sort((left, right) => historyTimestamp(right) - historyTimestamp(left))
        .slice(0, HISTORY_LIMIT),
    [appState?.notificationHistory],
  );

  const pushSupported = Boolean(
    capabilities?.secureContext &&
      capabilities.serviceWorker &&
      capabilities.push &&
      capabilities.notifications,
  );
  const subscribed = browserSubscription !== null;
  const clockActive = appState?.clockTest?.status === "ACTIVE";

  const handleLocaleChange = (nextLocale: Locale) => {
    setLocale(nextLocale);
    saveLocale(nextLocale);
  };

  const handleInstall = async () => {
    if (!installPrompt) return;
    await installPrompt.prompt();
    const choice = await installPrompt.userChoice;
    setInstallPrompt(null);
    setNotice({
      key: choice.outcome === "accepted" ? "installDone" : "installDeclined",
      tone: choice.outcome === "accepted" ? "success" : "warning",
    });
  };

  const handleAccessSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedCode = accessCode.trim();
    if (!normalizedCode) return;
    await checkInstalledSession(normalizedCode);
  };

  const handleCounterChange = async (delta: -1 | 1) => {
    const currentSession = sessionRef.current;
    if (!currentSession || !storageAvailable) return;
    setBusyAction("counter");
    try {
      const operation = await enqueueCounterOperation(currentSession.ownerKey, delta);
      const pending = [...pendingRef.current, operation];
      replacePendingOperations(pending);
      setNotice({
        key: window.navigator.onLine ? "syncFailed" : "queuedOffline",
        tone: "info",
      });
      if (window.navigator.onLine && verifiedRef.current) {
        await flushQueue();
        if (pendingRef.current.length === 0) setNotice(null);
      }
    } catch {
      setStorageAvailable(false);
      setNotice({ key: "syncFailed", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  const handleEnableNotifications = async () => {
    const currentSession = sessionRef.current;
    if (!currentSession || !window.navigator.onLine || !verifiedRef.current) {
      setNotice({ key: "actionUnavailableOffline", tone: "warning" });
      return;
    }

    setBusyAction("enable-push");
    try {
      const permission = await window.Notification.requestPermission();
      refreshCapabilities();
      if (permission !== "granted") {
        setNotice({ key: "permissionDeniedBody", tone: "warning" });
        return;
      }

      const vapidKey = await fetchVapidPublicKey(stateRef.current?.vapidPublicKey);
      const subscription = await createBrowserSubscription(vapidKey);
      const registered = await registerPushSubscription(
        subscription.toJSON(),
        currentSession.csrfToken,
        locale,
        Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Seoul",
      );
      setBrowserSubscription(subscription);
      if (registered) setServerSubscriptionId(registered.id);
      await reloadServerState(true);
      setNotice({ key: "subscriptionActive", tone: "success" });
    } catch {
      setNotice({ key: "genericError", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  const handleDisableNotifications = async () => {
    const currentSession = sessionRef.current;
    if (!currentSession || !window.navigator.onLine || !verifiedRef.current) {
      setNotice({ key: "actionUnavailableOffline", tone: "warning" });
      return;
    }

    setBusyAction("disable-push");
    try {
      let id = serverSubscriptionId;
      if (!id && browserSubscription) {
        const registered = await registerPushSubscription(
          browserSubscription.toJSON(),
          currentSession.csrfToken,
          locale,
          Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Seoul",
        );
        id = registered?.id ?? null;
      }
      if (id) await deletePushSubscription(id, currentSession.csrfToken);
      await browserSubscription?.unsubscribe();
      setBrowserSubscription(null);
      setServerSubscriptionId(null);
      await reloadServerState(true);
      setNotice({ key: "subscriptionInactive", tone: "success" });
    } catch {
      setNotice({ key: "genericError", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  const handleImmediateTest = async () => {
    const currentSession = sessionRef.current;
    if (!currentSession || !subscribed) {
      setNotice({ key: "enableBeforeTest", tone: "warning" });
      return;
    }
    if (!window.navigator.onLine || !verifiedRef.current) {
      setNotice({ key: "actionUnavailableOffline", tone: "warning" });
      return;
    }

    setBusyAction("test-now");
    try {
      await sendImmediateNotification(currentSession.csrfToken);
      setNotice({ key: "immediateSent", tone: "success" });
      window.setTimeout(() => void reloadServerState(true), 2_000);
    } catch {
      setNotice({ key: "genericError", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  const handleStartClock = async () => {
    const currentSession = sessionRef.current;
    if (!currentSession || !subscribed) {
      setNotice({ key: "enableBeforeTest", tone: "warning" });
      return;
    }
    if (!window.navigator.onLine || !verifiedRef.current) {
      setNotice({ key: "actionUnavailableOffline", tone: "warning" });
      return;
    }

    setBusyAction("start-clock");
    try {
      const clockTest = await startClockTest(currentSession.csrfToken);
      const currentState = stateRef.current;
      if (currentState) await commitState({ ...currentState, clockTest });
      setNotice({ key: "clockStarted", tone: "success" });
    } catch {
      setNotice({ key: "genericError", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  const handleStopClock = async () => {
    const currentSession = sessionRef.current;
    if (!currentSession || !window.navigator.onLine || !verifiedRef.current) {
      setNotice({ key: "actionUnavailableOffline", tone: "warning" });
      return;
    }

    setBusyAction("stop-clock");
    try {
      await stopClockTest(currentSession.csrfToken);
      await reloadServerState(true);
      setNotice({ key: "clockStoppedNotice", tone: "success" });
    } catch {
      setNotice({ key: "genericError", tone: "error" });
    } finally {
      setBusyAction(null);
    }
  };

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">{t("appEyebrow")}</p>
          <h1>{t("appName")}</h1>
        </div>
        <label className="language-control">
          <span>{t("languageLabel")}</span>
          <select
            value={locale}
            onChange={(event) => handleLocaleChange(event.target.value as Locale)}
          >
            {(Object.keys(localeNames) as Locale[]).map((item) => (
              <option key={item} value={item}>
                {localeNames[item]}
              </option>
            ))}
          </select>
        </label>
      </header>

      {capabilities && (
        <div className="context-strip" aria-label={t("diagnosticsTitle")}>
          <span className={capabilities.standalone ? "status-dot good" : "status-dot"}>
            {capabilities.standalone ? t("installed") : t("browserMode")}
          </span>
          <span className={capabilities.online ? "status-dot good" : "status-dot warning"}>
            {capabilities.online ? t("online") : t("offline")}
          </span>
        </div>
      )}

      {notice && (
        <div className={`notice ${notice.tone}`} role="status" aria-live="polite">
          <p>{t(notice.key)}</p>
          <button type="button" onClick={() => setNotice(null)} aria-label={t("dismiss")}>
            ×
          </button>
        </div>
      )}

      {phase === "diagnosing" && <LoadingCard t={t} />}

      {phase === "preinstall" && capabilities && (
        <InstallGate
          capabilities={capabilities}
          canPrompt={installPrompt !== null}
          onInstall={handleInstall}
          t={t}
        />
      )}

      {phase === "checking" && <LoadingCard t={t} />}

      {phase === "access" && (
        <AccessCodeGate
          accessCode={accessCode}
          busy={busyAction === "access"}
          feedback={accessFeedback}
          onAccessCodeChange={setAccessCode}
          onSubmit={handleAccessSubmit}
          t={t}
        />
      )}

      {phase === "unavailable" && (
        <section className="gate-card" aria-labelledby="unavailable-title">
          <div className="gate-symbol" aria-hidden="true">
            !
          </div>
          <h2 id="unavailable-title">{t("unavailableTitle")}</h2>
          <p>{t("unavailableBody")}</p>
          <button className="primary-button" type="button" onClick={() => checkInstalledSession()}>
            {t("retry")}
          </button>
        </section>
      )}

      {phase === "ready" && appState && session && capabilities && (
        <div className="dashboard">
          <section className="intro-row">
            <p>{t("dashboardIntro")}</p>
            <span>
              {t("sessionLabel")} · {session.ownerKey.slice(0, 8)}
            </span>
          </section>

          {foreignPendingCount > 0 && (
            <div className="inline-warning" role="status">
              {t("isolatedOperations", { count: foreignPendingCount })}
            </div>
          )}

          <CounterPanel
            busy={busyAction === "counter"}
            displayedValue={displayedCounter}
            onChange={handleCounterChange}
            pendingCount={pendingOperations.length}
            storageAvailable={storageAvailable}
            t={t}
          />

          <NotificationPanel
            appState={appState}
            busyAction={busyAction}
            capabilities={capabilities}
            clockActive={clockActive}
            locale={locale}
            onDisable={handleDisableNotifications}
            onEnable={handleEnableNotifications}
            onImmediateTest={handleImmediateTest}
            onStartClock={handleStartClock}
            onStopClock={handleStopClock}
            pushSupported={pushSupported}
            subscribed={subscribed}
            t={t}
          />

          <HistoryPanel history={sortedHistory} locale={locale} t={t} />

          <section className="utility-card">
            <div>
              <h2>{t("diagnosticsTitle")}</h2>
              {lastUpdatedAt && (
                <p>{t("lastUpdated", { time: formatDateTime(locale, lastUpdatedAt) })}</p>
              )}
            </div>
            <button
              className="secondary-button compact"
              type="button"
              disabled={busyAction === "refresh" || !capabilities.online}
              onClick={() => reloadServerState(false)}
            >
              {busyAction === "refresh" ? t("refreshing") : t("refresh")}
            </button>
          </section>
          <Diagnostics capabilities={capabilities} t={t} />
        </div>
      )}
    </main>
  );
}

interface TranslateProp {
  t: (key: MessageKey, values?: Record<string, string | number>) => string;
}

function LoadingCard({ t }: TranslateProp) {
  return (
    <section className="gate-card" aria-busy="true" aria-live="polite">
      <div className="loader" aria-hidden="true" />
      <h2>{t("loadingTitle")}</h2>
      <p>{t("loadingBody")}</p>
    </section>
  );
}

interface InstallGateProps extends TranslateProp {
  capabilities: CapabilitySnapshot;
  canPrompt: boolean;
  onInstall(): Promise<void>;
}

function InstallGate({ capabilities, canPrompt, onInstall, t }: InstallGateProps) {
  const instructionKey =
    capabilities.platform === "ios"
      ? "installIosSteps"
      : capabilities.platform === "android"
        ? "installAndroidSteps"
        : "installOtherSteps";
  const titleKey =
    capabilities.platform === "ios"
      ? "installIosTitle"
      : capabilities.platform === "android"
        ? "installAndroidTitle"
        : "installOtherTitle";

  return (
    <div className="gate-stack">
      <section className="gate-card install-card" aria-labelledby="install-title">
        <div className="install-mark" aria-hidden="true">
          <span>+</span>
        </div>
        <p className="section-kicker">{t(titleKey)}</p>
        <h2 id="install-title">{t("installTitle")}</h2>
        <p>{t("installBody")}</p>
        <div className="instruction-box">
          <strong>{t(titleKey)}</strong>
          <span>{t(instructionKey)}</span>
        </div>
        {canPrompt ? (
          <button className="primary-button" type="button" onClick={() => void onInstall()}>
            {t("installButton")}
          </button>
        ) : (
          <p className="helper-text">{t("installPromptUnavailable")}</p>
        )}
        <p className="privacy-note">{t("installPrivacy")}</p>
      </section>
      <Diagnostics capabilities={capabilities} t={t} />
    </div>
  );
}

interface AccessCodeGateProps extends TranslateProp {
  accessCode: string;
  busy: boolean;
  feedback: "invalid" | "unavailable" | null;
  onAccessCodeChange(value: string): void;
  onSubmit(event: FormEvent<HTMLFormElement>): Promise<void>;
}

function AccessCodeGate({
  accessCode,
  busy,
  feedback,
  onAccessCodeChange,
  onSubmit,
  t,
}: AccessCodeGateProps) {
  const feedbackKey = feedback === "invalid" ? "accessRejected" : "serverUnavailable";
  return (
    <section className="gate-card" aria-labelledby="access-title">
      <div className="gate-symbol code" aria-hidden="true">
        #
      </div>
      <h2 id="access-title">{t("accessTitle")}</h2>
      <p>{t("accessBody")}</p>
      <form className="access-form" onSubmit={(event) => void onSubmit(event)}>
        <label htmlFor="access-code">{t("accessCodeLabel")}</label>
        <input
          id="access-code"
          name="accessCode"
          type="password"
          value={accessCode}
          onChange={(event) => onAccessCodeChange(event.target.value)}
          placeholder={t("accessCodePlaceholder")}
          autoComplete="one-time-code"
          autoCapitalize="characters"
          spellCheck={false}
          required
          disabled={busy}
          aria-describedby={feedback ? "access-feedback" : undefined}
          aria-invalid={feedback === "invalid"}
        />
        {feedback && (
          <p id="access-feedback" className={`form-feedback ${feedback}`} role="alert">
            {t(feedbackKey)}
          </p>
        )}
        <button className="primary-button" type="submit" disabled={busy || !accessCode.trim()}>
          {busy ? t("accessSubmitting") : t("accessSubmit")}
        </button>
      </form>
    </section>
  );
}

interface CounterPanelProps extends TranslateProp {
  busy: boolean;
  displayedValue: number;
  pendingCount: number;
  storageAvailable: boolean;
  onChange(delta: -1 | 1): Promise<void>;
}

function CounterPanel({
  busy,
  displayedValue,
  pendingCount,
  storageAvailable,
  onChange,
  t,
}: CounterPanelProps) {
  const atMinimum = displayedValue <= MIN_COUNTER;
  const atMaximum = displayedValue >= MAX_COUNTER;

  return (
    <section className="feature-card counter-card" aria-labelledby="counter-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">0 — 10</p>
          <h2 id="counter-title">{t("counterTitle")}</h2>
        </div>
        <span className={pendingCount > 0 ? "sync-chip pending" : "sync-chip"} aria-live="polite">
          {pendingCount > 0 ? t("pendingOperations", { count: pendingCount }) : t("allSynced")}
        </span>
      </div>
      <p className="section-description">{t("counterBody")}</p>

      <div className="counter-readout" aria-live="polite">
        <span>{displayedValue}</span>
        <small>/ 10</small>
      </div>

      <div
        className="stamp-progress"
        role="progressbar"
        aria-label={t("counterProgress", { value: displayedValue })}
        aria-valuemin={MIN_COUNTER}
        aria-valuemax={MAX_COUNTER}
        aria-valuenow={displayedValue}
      >
        {Array.from({ length: MAX_COUNTER }, (_, index) => (
          <span key={index} className={index < displayedValue ? "filled" : undefined} />
        ))}
      </div>

      <div className="counter-actions">
        <button
          className="counter-button"
          type="button"
          disabled={busy || atMinimum || !storageAvailable}
          onClick={() => void onChange(-1)}
          aria-label={atMinimum ? t("minimumReached") : t("decrease")}
        >
          −
        </button>
        <button
          className="counter-button primary"
          type="button"
          disabled={busy || atMaximum || !storageAvailable}
          onClick={() => void onChange(1)}
          aria-label={atMaximum ? t("maximumReached") : t("increase")}
        >
          +
        </button>
      </div>
    </section>
  );
}

interface NotificationPanelProps extends TranslateProp {
  appState: AppState;
  busyAction: BusyAction;
  capabilities: CapabilitySnapshot;
  clockActive: boolean;
  locale: Locale;
  pushSupported: boolean;
  subscribed: boolean;
  onEnable(): Promise<void>;
  onDisable(): Promise<void>;
  onImmediateTest(): Promise<void>;
  onStartClock(): Promise<void>;
  onStopClock(): Promise<void>;
}

function NotificationPanel({
  appState,
  busyAction,
  capabilities,
  clockActive,
  locale,
  pushSupported,
  subscribed,
  onEnable,
  onDisable,
  onImmediateTest,
  onStartClock,
  onStopClock,
  t,
}: NotificationPanelProps) {
  const clock = appState.clockTest;
  const permissionDenied = capabilities.notificationPermission === "denied";
  const statusKey = clockStatusKey(clock?.status ?? "IDLE");

  return (
    <section className="feature-card notification-card" aria-labelledby="notifications-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">WEB PUSH</p>
          <h2 id="notifications-title">{t("notificationsTitle")}</h2>
        </div>
        <span className={subscribed ? "sync-chip active" : "sync-chip"}>
          {subscribed ? t("permissionGranted") : t("permissionDefault")}
        </span>
      </div>
      <p className="section-description">{t("notificationsBody")}</p>

      {!pushSupported && (
        <div className="support-message error" role="alert">
          <strong>{t("pushUnsupportedTitle")}</strong>
          <span>{t("pushUnsupportedBody")}</span>
        </div>
      )}

      {pushSupported && permissionDenied && (
        <div className="support-message warning" role="alert">
          <strong>{t("permissionDeniedTitle")}</strong>
          <span>{t("permissionDeniedBody")}</span>
        </div>
      )}

      {pushSupported && !permissionDenied && (
        <div className="notification-connect">
          <p>{subscribed ? t("subscriptionActive") : t("subscriptionInactive")}</p>
          <button
            className={subscribed ? "secondary-button" : "primary-button"}
            type="button"
            disabled={busyAction !== null || !capabilities.online}
            onClick={() => void (subscribed ? onDisable() : onEnable())}
          >
            {subscribed
              ? busyAction === "disable-push"
                ? t("disablingNotifications")
                : t("disableNotifications")
              : busyAction === "enable-push"
                ? t("enablingNotifications")
                : t("enableNotifications")}
          </button>
        </div>
      )}

      <div className="test-now-row">
        <div>
          <strong>{t("kindTestNow")}</strong>
          <span>{t("subscriptionActive")}</span>
        </div>
        <button
          className="secondary-button compact"
          type="button"
          disabled={!subscribed || busyAction !== null || !capabilities.online}
          onClick={() => void onImmediateTest()}
        >
          {busyAction === "test-now" ? t("sendingNow") : t("sendNow")}
        </button>
      </div>

      <div className="clock-panel">
        <div className="clock-header">
          <div>
            <h3>{t("clockTitle")}</h3>
            <p>{t("clockBody")}</p>
          </div>
          <span className={`clock-status ${clock?.status?.toLowerCase() ?? "idle"}`}>
            {t(statusKey)}
          </span>
        </div>

        {clock && (
          <dl className="clock-metrics">
            <div>
              <dt>{t("runProgress", { sent: clock.sentCount, failed: clock.failedCount })}</dt>
              <dd>{clock.totalCount || 5}</dd>
            </div>
            {clock.nextScheduledAt && (
              <div>
                <dt>{t("nextAt", { time: formatDateTime(locale, clock.nextScheduledAt) })}</dt>
                <dd>→</dd>
              </div>
            )}
            {clock.endsAt && (
              <div>
                <dt>{t("endsAt", { time: formatDateTime(locale, clock.endsAt) })}</dt>
                <dd>✓</dd>
              </div>
            )}
          </dl>
        )}

        <button
          className={clockActive ? "danger-button" : "primary-button"}
          type="button"
          disabled={!subscribed || busyAction !== null || !capabilities.online}
          onClick={() => void (clockActive ? onStopClock() : onStartClock())}
        >
          {clockActive
            ? busyAction === "stop-clock"
              ? t("stoppingClock")
              : t("stopClock")
            : busyAction === "start-clock"
              ? t("startingClock")
              : t("startClock")}
        </button>
      </div>
    </section>
  );
}

function clockStatusKey(status: ClockTestStatus): MessageKey {
  const keys: Record<ClockTestStatus, MessageKey> = {
    IDLE: "clockIdle",
    ACTIVE: "clockActive",
    COMPLETED: "clockCompleted",
    STOPPED: "clockStopped",
    FAILED: "clockFailed",
  };
  return keys[status];
}

function historyStatusKey(status: NotificationDeliveryStatus): MessageKey {
  const keys: Record<NotificationDeliveryStatus, MessageKey> = {
    SCHEDULED: "statusScheduled",
    SENDING: "statusSending",
    ACCEPTED: "statusAccepted",
    FAILED: "statusFailed",
    RECEIVED: "statusReceived",
    ACKNOWLEDGED: "statusAcknowledged",
  };
  return keys[status];
}

interface HistoryPanelProps extends TranslateProp {
  history: NotificationHistoryItem[];
  locale: Locale;
}

function HistoryPanel({ history, locale, t }: HistoryPanelProps) {
  return (
    <section className="feature-card history-card" aria-labelledby="history-title">
      <div className="section-heading">
        <div>
          <p className="section-kicker">DELIVERY LOG</p>
          <h2 id="history-title">{t("historyTitle")}</h2>
        </div>
        <span className="history-count">{history.length}</span>
      </div>
      <p className="section-description">{t("historyBody")}</p>

      {history.length === 0 ? (
        <div className="empty-state">{t("historyEmpty")}</div>
      ) : (
        <ol className="history-list">
          {history.map((item) => {
            const time = item.acknowledgedAt ?? item.acceptedAt ?? item.sentAt ?? item.scheduledAt;
            return (
              <li key={item.id}>
                <div className="history-marker" aria-hidden="true" />
                <div className="history-content">
                  <div className="history-primary">
                    <strong>{item.kind === "TEST_NOW" ? t("kindTestNow") : t("kindClock")}</strong>
                    {item.sequence !== undefined && (
                      <span>{t("sequence", { sequence: item.sequence })}</span>
                    )}
                  </div>
                  <div className="history-secondary">
                    <span className={`delivery-status ${item.status.toLowerCase()}`}>
                      {t(historyStatusKey(item.status))}
                    </span>
                    {time && <time dateTime={time}>{formatDateTime(locale, time)}</time>}
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

interface DiagnosticsProps extends TranslateProp {
  capabilities: CapabilitySnapshot;
}

function Diagnostics({ capabilities, t }: DiagnosticsProps) {
  const permissionKey: MessageKey =
    capabilities.notificationPermission === "granted"
      ? "permissionGranted"
      : capabilities.notificationPermission === "denied"
        ? "permissionDenied"
        : capabilities.notificationPermission === "default"
          ? "permissionDefault"
          : "unsupported";
  const rows: Array<[MessageKey, boolean, string]> = [
    ["secureContext", capabilities.secureContext, capabilities.secureContext ? t("yes") : t("no")],
    ["serviceWorker", capabilities.serviceWorker, capabilities.serviceWorker ? t("supported") : t("unsupported")],
    ["pushApi", capabilities.push && capabilities.notifications, capabilities.push && capabilities.notifications ? t("supported") : t("unsupported")],
    ["standaloneMode", capabilities.standalone, capabilities.standalone ? t("yes") : t("no")],
    ["connection", capabilities.online, capabilities.online ? t("online") : t("offline")],
    ["notificationPermission", capabilities.notificationPermission === "granted", t(permissionKey)],
  ];

  return (
    <section className="diagnostics-card" aria-labelledby="diagnostics-heading">
      <div>
        <h2 id="diagnostics-heading">{t("diagnosticsTitle")}</h2>
        <p>{t("diagnosticsBody")}</p>
      </div>
      <dl>
        {rows.map(([label, positive, value]) => (
          <div key={label}>
            <dt>{t(label)}</dt>
            <dd className={positive ? "positive" : undefined}>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
