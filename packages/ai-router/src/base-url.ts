/**
 * Central Base-URL normalizer for OpenAI-compatible providers.
 * Handles: trailing slashes, missing/duplicate /v1, localhost, LAN IPs,
 * custom endpoints. Never produces /v1/v1 or //chat/completions.
 */

export interface NormalizedBaseUrl {
  /** Base without trailing slash, WITH version prefix when appropriate, e.g. https://api.openai.com/v1 */
  baseUrl: string;
  /** e.g. https://api.openai.com/v1/chat/completions */
  chatCompletionsUrl: string;
  /** e.g. https://api.openai.com/v1/models (null when provider has no /models) */
  modelsUrl: string | null;
  isLocal: boolean;
  usesHttp: boolean;
}

function stripTrailingSlashes(s: string): string {
  return s.replace(/\/+$/, "");
}

export function isLocalHost(host: string): boolean {
  const h = host.toLowerCase();
  return (
    h === "localhost" ||
    h === "127.0.0.1" ||
    h === "[::1]" ||
    h.startsWith("10.") ||
    h.startsWith("192.168.") ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(h)
  );
}

/**
 * Normalize a user-supplied base URL.
 * @param raw e.g. "https://api.openai.com/v1/", "http://192.168.1.10:11434", "http://localhost:11434/v1"
 * @param opts.allowNoV1 for providers whose /models lives at root (rare). Default appends /v1 when missing.
 */
export function normalizeBaseUrl(raw: string, opts: { allowNoV1?: boolean } = {}): NormalizedBaseUrl {
  if (!raw || typeof raw !== "string") throw new Error("Base URL is required.");
  let trimmed = raw.trim();
  if (!/^https?:\/\//i.test(trimmed)) {
    throw new Error(`Invalid Base URL "${raw}": must start with http:// or https://`);
  }
  trimmed = stripTrailingSlashes(trimmed);
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    throw new Error(`Invalid Base URL "${raw}": unparseable.`);
  }
  if (!url.hostname) throw new Error(`Invalid Base URL "${raw}": missing host.`);

  const isLocal = isLocalHost(url.hostname);
  const usesHttp = url.protocol === "http:";

  // Collapse duplicate slashes in path (but keep protocol://)
  let pathname = url.pathname.replace(/\/{2,}/g, "/");
  pathname = stripTrailingSlashes(pathname);
  if (pathname === "/") pathname = "";

  // Avoid /v1/v1: only append /v1 when path doesn't already end with /v1
  // and doesn't already point at a deeper versioned path like /api/v1.
  const endsWithV1 = /\/v1$/i.test(pathname);
  if (!opts.allowNoV1 && !endsWithV1) {
    pathname = `${pathname}/v1`;
  }
  // Never produce empty path issues
  const base = `${url.protocol}//${url.host}${pathname}`;
  const baseUrl = stripTrailingSlashes(base);
  return {
    baseUrl,
    chatCompletionsUrl: `${baseUrl}/chat/completions`,
    modelsUrl: `${baseUrl}/models`,
    isLocal,
    usesHttp,
  };
}

/** Join helper that never produces double slashes. */
export function joinUrl(base: string, ...parts: string[]): string {
  const cleanBase = stripTrailingSlashes(base);
  const cleanParts = parts.map((p) => p.replace(/^\/+|\/+$/g, "")).filter(Boolean);
  return `${cleanBase}/${cleanParts.join("/")}`;
}
