/**
 * Unified provider error model + parsing for OpenAI-compatible APIs.
 * Maps HTTP statuses to structured, user-friendly, non-leaking errors.
 */

export type ProviderErrorKind =
  | "NETWORK_ERROR"
  | "PROVIDER_AUTH"
  | "PROVIDER_RATE_LIMIT"
  | "PROVIDER_UNAVAILABLE"
  | "INVALID_MODEL"
  | "CONTEXT_LENGTH_EXCEEDED"
  | "TIMEOUT"
  | "INVALID_REQUEST"
  | "UNKNOWN";

export interface ProviderError extends Error {
  kind: ProviderErrorKind;
  httpStatus?: number;
  retryable: boolean;
  retryAfterMs?: number;
}

export function providerError(
  kind: ProviderErrorKind,
  message: string,
  opts: { httpStatus?: number; retryable?: boolean; retryAfterMs?: number } = {}
): ProviderError {
  const e = new Error(message) as ProviderError;
  e.name = "ProviderError";
  e.kind = kind;
  e.httpStatus = opts.httpStatus;
  e.retryable = opts.retryable ?? false;
  if (opts.retryAfterMs !== undefined) e.retryAfterMs = opts.retryAfterMs;
  return e;
}

function parseRetryAfter(res: Response): number | undefined {
  const v = res.headers.get("retry-after");
  if (!v) return undefined;
  const secs = Number(v);
  if (Number.isFinite(secs)) return secs * 1000;
  const date = Date.parse(v);
  if (!Number.isNaN(date)) return Math.max(0, date - Date.now());
  return undefined;
}

async function readErrorMessage(res: Response): Promise<string> {
  try {
    const text = await res.text();
    if (!text) return res.statusText || `HTTP ${res.status}`;
    try {
      const j = JSON.parse(text);
      return (
        j?.error?.message ||
        j?.message ||
        (typeof j?.error === "string" ? j.error : null) ||
        text.slice(0, 500)
      );
    } catch {
      return text.slice(0, 500);
    }
  } catch {
    return res.statusText || `HTTP ${res.status}`;
  }
}

/** Throw a structured ProviderError for non-OK responses. Never includes API keys. */
export async function throwForStatus(res: Response, context: string): Promise<never> {
  const msg = await readErrorMessage(res);
  const s = res.status;
  if (s === 401 || s === 403) {
    throw providerError("PROVIDER_AUTH", `Authentication failed (${s}). Check API key. ${msg}`, { httpStatus: s, retryable: false });
  }
  if (s === 408) {
    throw providerError("TIMEOUT", `Request timed out (${s}). ${msg}`, { httpStatus: s, retryable: true });
  }
  if (s === 404) {
    throw providerError("INVALID_MODEL", `Not found (${s}). Check Base URL / Model ID. ${msg}`, { httpStatus: s, retryable: false });
  }
  if (s === 400 || s === 422) {
    const lower = msg.toLowerCase();
    if (lower.includes("context") && (lower.includes("length") || lower.includes("token") || lower.includes("maximum"))) {
      throw providerError("CONTEXT_LENGTH_EXCEEDED", `Context length exceeded. ${msg}`, { httpStatus: s, retryable: false });
    }
    if (lower.includes("model")) {
      throw providerError("INVALID_MODEL", `Invalid model. ${msg}`, { httpStatus: s, retryable: false });
    }
    throw providerError("INVALID_REQUEST", `Invalid request (${s}). ${msg}`, { httpStatus: s, retryable: false });
  }
  if (s === 429) {
    throw providerError("PROVIDER_RATE_LIMIT", `Rate limited (429). ${msg}`, { httpStatus: s, retryable: true, retryAfterMs: parseRetryAfter(res) });
  }
  if (s >= 500) {
    throw providerError("PROVIDER_UNAVAILABLE", `Provider unavailable (${s}) in ${context}. ${msg}`, { httpStatus: s, retryable: true });
  }
  throw providerError("UNKNOWN", `Request failed (${s}) in ${context}. ${msg}`, { httpStatus: s, retryable: false });
}

export function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 429 || (status >= 500 && status <= 599);
}

/** Limited exponential backoff: retries only transient failures. */
export async function withRetry<T>(fn: () => Promise<T>, opts: { maxAttempts?: number; baseDelayMs?: number } = {}): Promise<T> {
  const max = opts.maxAttempts ?? 3;
  const base = opts.baseDelayMs ?? 500;
  let last: unknown;
  for (let attempt = 1; attempt <= max; attempt++) {
    try {
      return await fn();
    } catch (e: any) {
      last = e;
      const retryable = e?.retryable === true || e?.kind === "NETWORK_ERROR" || e?.kind === "TIMEOUT" || e?.kind === "PROVIDER_UNAVAILABLE" || e?.kind === "PROVIDER_RATE_LIMIT";
      if (!retryable || attempt === max) throw e;
      const wait = e?.retryAfterMs ?? base * 2 ** (attempt - 1);
      await new Promise((r) => setTimeout(r, Math.min(wait, 10000)));
    }
  }
  throw last;
}
