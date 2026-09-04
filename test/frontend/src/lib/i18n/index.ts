import { en } from "./en";
import { ko } from "./ko";
import type { Messages } from "./types";
import { zh } from "./zh";
import type { Locale } from "../types";

const dictionaries: Record<Locale, Messages> = { ko, en, zh };

const dateLocales: Record<Locale, string> = {
  ko: "ko-KR",
  en: "en-US",
  zh: "zh",
};

export const localeNames: Record<Locale, string> = {
  ko: "한국어",
  en: "English",
  zh: "中文",
};

export type MessageKey = keyof Messages;

export function translate(
  locale: Locale,
  key: MessageKey,
  values: Record<string, string | number> = {},
): string {
  return Object.entries(values).reduce(
    (message, [name, value]) => message.replaceAll(`{${name}}`, String(value)),
    dictionaries[locale][key],
  );
}

export function detectLocale(): Locale {
  if (typeof window === "undefined") {
    return "ko";
  }

  const saved = window.localStorage.getItem("espero-test-locale");
  if (saved === "ko" || saved === "en" || saved === "zh") {
    return saved;
  }

  const browserLocale = window.navigator.language.toLowerCase();
  if (browserLocale.startsWith("zh")) return "zh";
  if (browserLocale.startsWith("en")) return "en";
  return "ko";
}

export function saveLocale(locale: Locale): void {
  window.localStorage.setItem("espero-test-locale", locale);
  document.documentElement.lang = locale;
}

export function formatDateTime(locale: Locale, value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "—";

  return new Intl.DateTimeFormat(dateLocales[locale], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: "Asia/Seoul",
    timeZoneName: "short",
  }).format(date);
}
