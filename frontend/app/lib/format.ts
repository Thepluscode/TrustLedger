/** Formatting helpers — financial values always tabular + currency-aware (design.md §5.3). */

const FALLBACK_LOCALE = "en-GB";
const LOCALE_KEY = "trustledger.locale";

/**
 * The operator's locale: explicit preference first, then the browser, then en-GB.
 *
 * Currency always comes from the data and is never inferred from the locale — an operator in Lagos
 * reviewing a EUR payout must see EUR. Locale governs presentation only: separators, date order.
 *
 * SSR-safe: every consumer is a client component that renders formatted values only after its
 * useEffect resolves, so the server never emits a locale-dependent string the client could disagree
 * with on hydration.
 */
export function operatorLocale(): string {
  if (typeof window === "undefined") return FALLBACK_LOCALE;
  try {
    return window.localStorage.getItem(LOCALE_KEY) || window.navigator.language || FALLBACK_LOCALE;
  } catch {
    // localStorage throws in some private-browsing modes. Presentation-only, so fall back quietly.
    return FALLBACK_LOCALE;
  }
}

export function money(amount: string | number, currency: string): string {
  const n = typeof amount === "string" ? parseFloat(amount) : amount;
  if (Number.isNaN(n)) return String(amount);
  try {
    return new Intl.NumberFormat(operatorLocale(), { style: "currency", currency }).format(n);
  } catch {
    return `${n.toFixed(2)} ${currency}`;
  }
}

/** Locale-aware plain decimal, for scores and counts that carry no currency. */
export function decimal(value: string | number, fractionDigits = 2): string {
  const n = typeof value === "string" ? parseFloat(value) : value;
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString(operatorLocale(), {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

export function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

/**
 * Timestamps carry the year. An audit and reconciliation UI rendering "02 Aug, 14:33" cannot
 * distinguish a 2025 entry from a 2026 one, which defeats the purpose of an audit trail.
 * Stored values stay UTC; only the rendering is localised.
 */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return d.toLocaleString(operatorLocale(), {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
