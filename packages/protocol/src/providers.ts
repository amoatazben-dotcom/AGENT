import { z } from "zod";

// Provider Configuration (user-supplied, DB-backed)
// NOTE: standalone module — base protocol lives in index.ts and re-exports this file.

// ==========================================
// Provider Configuration (user-supplied, DB-backed)
// ==========================================
export const ProviderTypeSchema = z.enum([
  "openai",
  "openai_compatible",
  "anthropic",
  "gemini",
  "xai",
  "groq",
  "mistral",
  "openrouter",
  "deepseek",
  "ollama",
  "lmstudio",
  "custom",
]);
export type ProviderType = z.infer<typeof ProviderTypeSchema>;

export const ProviderStatusSchema = z.enum([
  "unknown",
  "connected",
  "auth_failed",
  "invalid_url",
  "timeout",
  "unreachable",
  "no_models",
  "rate_limited",
  "invalid_response",
]);
export type ProviderStatus = z.infer<typeof ProviderStatusSchema>;

export const ProviderConfigSchema = z.object({
  id: z.string(),
  name: z.string().min(1),
  type: ProviderTypeSchema,
  baseUrl: z.string().min(1),
  // NOTE: apiKey is write-only. Never returned by API; use hasApiKey + apiKeyMasked.
  apiKey: z.string().optional(),
  hasApiKey: z.boolean().default(false),
  apiKeyMasked: z.string().default(""),
  organizationId: z.string().optional(),
  projectId: z.string().optional(),
  defaultModel: z.string().default(""),
  enabled: z.boolean().default(true),
  isDefault: z.boolean().default(false),
  supportsStreaming: z.boolean().default(true),
  supportsTools: z.boolean().default(true),
  supportsVision: z.boolean().default(false),
  customHeaders: z.record(z.string()).default({}),
  timeoutMs: z.number().int().min(1000).max(300000).default(60000),
  status: ProviderStatusSchema.default("unknown"),
  lastTestedAt: z.string().nullable().optional(),
  lastError: z.string().nullable().optional(),
  latencyMs: z.number().nullable().optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type ProviderConfig = z.infer<typeof ProviderConfigSchema>;

export const CreateProviderRequestSchema = z.object({
  name: z.string().min(1),
  type: ProviderTypeSchema,
  baseUrl: z.string().min(1),
  apiKey: z.string().default(""),
  organizationId: z.string().optional(),
  projectId: z.string().optional(),
  defaultModel: z.string().default(""),
  enabled: z.boolean().default(true),
  isDefault: z.boolean().default(false),
  supportsStreaming: z.boolean().default(true),
  supportsTools: z.boolean().default(true),
  supportsVision: z.boolean().default(false),
  customHeaders: z.record(z.string()).default({}),
  timeoutMs: z.number().int().min(1000).max(300000).default(60000),
});
export type CreateProviderRequest = z.infer<typeof CreateProviderRequestSchema>;

export const UpdateProviderRequestSchema = CreateProviderRequestSchema.partial();
export type UpdateProviderRequest = z.infer<typeof UpdateProviderRequestSchema>;

export const TestConnectionResultSchema = z.object({
  status: ProviderStatusSchema,
  latencyMs: z.number(),
  httpStatus: z.number().nullable().optional(),
  message: z.string(),
  models: z.array(z.string()).default([]),
  modelCount: z.number().default(0),
});
export type TestConnectionResult = z.infer<typeof TestConnectionResultSchema>;

// ==========================================
// Unified error model
// ==========================================
export const AgentForgeErrorCodeSchema = z.enum([
  "NETWORK_ERROR",
  "PROVIDER_AUTH",
  "PROVIDER_RATE_LIMIT",
  "PROVIDER_UNAVAILABLE",
  "INVALID_MODEL",
  "CONTEXT_LENGTH_EXCEEDED",
  "TOOL_EXECUTION_ERROR",
  "APPROVAL_REJECTED",
  "DATABASE_ERROR",
  "TIMEOUT",
  "VALIDATION_ERROR",
  "NOT_FOUND",
  "UNAUTHORIZED",
]);
export type AgentForgeErrorCode = z.infer<typeof AgentForgeErrorCodeSchema>;

export interface AgentForgeError {
  code: AgentForgeErrorCode;
  message: string;
  httpStatus: number;
  retryable: boolean;
  providerStatus?: ProviderStatus;
}

export function toUserMessage(code: AgentForgeErrorCode, detail?: string): string {
  switch (code) {
    case "PROVIDER_AUTH": return "Authentication failed. Check your API key.";
    case "PROVIDER_RATE_LIMIT": return "Rate limited by the provider. Try again shortly.";
    case "PROVIDER_UNAVAILABLE": return "AI provider is unreachable. Check Base URL / network.";
    case "INVALID_MODEL": return `Invalid model${detail ? `: ${detail}` : ". Check the Model ID."}`;
    case "CONTEXT_LENGTH_EXCEEDED": return "Conversation is too long for this model. Start a new chat or shorten history.";
    case "TIMEOUT": return "Request timed out. Try again or increase timeout.";
    case "NETWORK_ERROR": return "Network error. Check connectivity and Gateway URL.";
    default: return detail || "Something went wrong.";
  }
}
