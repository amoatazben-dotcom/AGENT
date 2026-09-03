/**
 * AI Router — multi-provider with dynamic DB-backed registry.
 *
 * - Static env-based providers (backwards compatible).
 * - NEW: OpenAICompatibleProvider (any Base URL + key + model).
 * - NEW: AIProviderRegistry / AIProviderFactory building runtime instances
 *   from ProviderConfig records (DB), with primary→fallback failover.
 * - Claude tool_use parsing fixed (was dropping tool calls).
 */
import type { ModelProvider } from "@agentforge/protocol";
import type { AIEvent, AIProvider, CanonicalMessage, ChatRequest, ModelInfo, ToolDefinition } from "./types.js";
import { OpenAICompatibleProvider } from "./openai-compatible.js";
import { throwForStatus, providerError, withRetry } from "./errors.js";
import { normalizeBaseUrl } from "./base-url.js";

export type { AIEvent, AIProvider, CanonicalMessage, ChatRequest, ModelInfo, ToolDefinition };
export * from "./openai-compatible.js";
export * from "./base-url.js";
export * from "./errors.js";
export * from "./types.js";

// ==========================================
// Preset defaults (Base URL only — NEVER keys)
// ==========================================
export const PROVIDER_PRESETS: Record<string, { type: string; baseUrl: string; label: string }> = {
  openai: { type: "openai", baseUrl: "https://api.openai.com/v1", label: "OpenAI" },
  anthropic: { type: "anthropic", baseUrl: "https://api.anthropic.com", label: "Anthropic" },
  gemini: { type: "gemini", baseUrl: "https://generativelanguage.googleapis.com", label: "Gemini" },
  openrouter: { type: "openrouter", baseUrl: "https://openrouter.ai/api/v1", label: "OpenRouter" },
  groq: { type: "groq", baseUrl: "https://api.groq.com/openai/v1", label: "Groq" },
  deepseek: { type: "deepseek", baseUrl: "https://api.deepseek.com/v1", label: "DeepSeek" },
  xai: { type: "xai", baseUrl: "https://api.x.ai/v1", label: "xAI" },
  mistral: { type: "mistral", baseUrl: "https://api.mistral.ai/v1", label: "Mistral" },
  ollama: { type: "ollama", baseUrl: "http://localhost:11434/v1", label: "Ollama" },
  lmstudio: { type: "lmstudio", baseUrl: "http://localhost:1234/v1", label: "LM Studio" },
};

// ==========================================
// OpenAI Provider (via generic compatible adapter)
// ==========================================
export class OpenAIProvider extends OpenAICompatibleProvider {
  override provider: ModelProvider = "openai";
  constructor(apiKey = process.env.OPENAI_API_KEY || "", baseUrl = "https://api.openai.com/v1") {
    super({ apiKey, baseUrl, providerLabel: "openai" });
  }
  override async models(): Promise<ModelInfo[]> {
    try {
      const ids = await this.fetchModelIds();
      if (ids.length > 0) return ids.map((id) => ({ id, name: id, provider: "openai" as ModelProvider, contextWindow: 128000 }));
    } catch { /* fall through to static */ }
    return [
      { id: "gpt-4o", name: "GPT-4o (Omni)", provider: "openai", contextWindow: 128000 },
      { id: "gpt-4o-mini", name: "GPT-4o Mini", provider: "openai", contextWindow: 128000 },
    ];
  }
}

// ==========================================
// Google Gemini Provider (native API)
// ==========================================
export class GeminiProvider implements AIProvider {
  provider: ModelProvider = "gemini";
  private apiKey: string;

  constructor(apiKey = process.env.GOOGLE_AI_API_KEY || process.env.GEMINI_API_KEY || "") {
    this.apiKey = apiKey;
  }

  async *chat(request: ChatRequest): AsyncIterable<AIEvent> {
    const key = this.apiKey || process.env.GEMINI_API_KEY || "";
    if (!key) {
      throw providerError("PROVIDER_AUTH" as any, "Gemini API key is missing. Configure in providers settings.", { retryable: false });
    }

    const modelName = request.model.includes("gemini") ? request.model : "gemini-1.5-flash";
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelName}:streamGenerateContent?key=${encodeURIComponent(key)}&alt=sse`;

    const contents: Array<{ role: string; parts: Array<{ text?: string; functionCall?: any; functionResponse?: any }> }> = [];
    let systemInstruction: { parts: [{ text: string }] } | undefined;

    for (const msg of request.messages) {
      if (msg.role === "system") {
        systemInstruction = { parts: [{ text: msg.content }] };
      } else if (msg.role === "user") {
        contents.push({ role: "user", parts: [{ text: msg.content }] });
      } else if (msg.role === "assistant") {
        const parts: any[] = [];
        if (msg.content) parts.push({ text: msg.content });
        if (msg.toolCalls) {
          for (const tc of msg.toolCalls) {
            parts.push({ functionCall: { name: tc.name, args: tc.arguments } });
          }
        }
        contents.push({ role: "model", parts });
      } else if (msg.role === "tool") {
        contents.push({
          role: "user",
          parts: [{ functionResponse: { name: msg.name || "tool_result", response: { content: msg.content } } }],
        });
      }
    }

    const body: Record<string, any> = { contents };
    if (systemInstruction) body.systemInstruction = systemInstruction;
    if (request.tools && request.tools.length > 0) {
      body.tools = [{ functionDeclarations: request.tools.map((t) => ({ name: t.name, description: t.description, parameters: t.parameters })) }];
    }

    const res = await withRetry(() => fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }));
    if (!res.ok) await throwForStatus(res, "gemini chat");
    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || !trimmed.startsWith("data: ")) continue;
        const dataStr = trimmed.slice(6);
        try {
          const parsed = JSON.parse(dataStr);
          const candidate = parsed.candidates?.[0];
          const parts = candidate?.content?.parts;
          if (parts) {
            for (const part of parts) {
              if (part.text) yield { type: "delta", content: part.text };
              if (part.functionCall) {
                yield { type: "tool_call", id: `gemini_call_${Date.now()}_${Math.random().toString(36).slice(2)}`, name: part.functionCall.name, arguments: part.functionCall.args || {} };
              }
            }
          }
          if (parsed.usageMetadata) {
            yield { type: "usage", promptTokens: parsed.usageMetadata.promptTokenCount ?? 0, completionTokens: parsed.usageMetadata.candidatesTokenCount ?? 0, totalTokens: parsed.usageMetadata.totalTokenCount ?? 0 };
          }
        } catch { /* ignore */ }
      }
    }
    yield { type: "done" };
  }

  async models(): Promise<ModelInfo[]> {
    return [
      { id: "gemini-1.5-flash", name: "Gemini 1.5 Flash", provider: "gemini", contextWindow: 1000000 },
      { id: "gemini-1.5-pro", name: "Gemini 1.5 Pro", provider: "gemini", contextWindow: 2000000 },
      { id: "gemini-2.0-flash", name: "Gemini 2.0 Flash", provider: "gemini", contextWindow: 1000000 },
    ];
  }
}

// ==========================================
// Anthropic Claude Provider (native Messages API, tool_use fixed)
// ==========================================
export class ClaudeProvider implements AIProvider {
  provider: ModelProvider = "anthropic";
  private apiKey: string;
  private baseUrl: string;

  constructor(apiKey = process.env.ANTHROPIC_API_KEY || "", baseUrl = "https://api.anthropic.com") {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replace(/\/+$/, "");
  }

  async *chat(request: ChatRequest): AsyncIterable<AIEvent> {
    if (!this.apiKey) {
      throw providerError("PROVIDER_AUTH" as any, "Anthropic API key is missing. Configure in providers settings.", { retryable: false });
    }

    const systemMessages = request.messages.filter((m) => m.role === "system").map((m) => m.content).join("\n\n");
    const chatMessages = request.messages
      .filter((m) => m.role !== "system")
      .map((m) => ({ role: m.role === "tool" ? "user" : m.role, content: m.content }));

    const body: Record<string, any> = {
      model: request.model.includes("claude") ? request.model : "claude-3-5-sonnet-20241022",
      max_tokens: request.maxTokens ?? 4096,
      messages: chatMessages,
      stream: true,
    };
    if (systemMessages) body.system = systemMessages;
    if (request.tools && request.tools.length > 0) {
      body.tools = request.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters }));
    }

    const res = await withRetry(() =>
      fetch(`${this.baseUrl}/v1/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-api-key": this.apiKey, "anthropic-version": "2023-06-01" },
        body: JSON.stringify(body),
      })
    );
    if (!res.ok) await throwForStatus(res, "anthropic chat");
    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    // Anthropic SSE: event: <type>\ndata: <json>
    let pendingEvent = "";
    const toolBlocks: Record<number, { id: string; name: string; json: string }> = {};

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";
      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed.startsWith("event:")) {
          pendingEvent = trimmed.slice(6).trim();
          continue;
        }
        if (!trimmed.startsWith("data:")) continue;
        const dataStr = trimmed.slice(5).trim();
        try {
          const parsed = JSON.parse(dataStr);
          const evt = parsed.type || pendingEvent;
          if (evt === "content_block_delta" && parsed.delta?.text) {
            yield { type: "delta", content: parsed.delta.text };
          } else if (evt === "content_block_start" && parsed.content_block?.type === "tool_use") {
            const idx = parsed.index ?? 0;
            toolBlocks[idx] = { id: parsed.content_block.id, name: parsed.content_block.name, json: "" };
          } else if (evt === "content_block_delta" && parsed.delta?.type === "input_json_delta") {
            const idx = parsed.index ?? 0;
            if (!toolBlocks[idx]) toolBlocks[idx] = { id: `claude_${Date.now()}_${idx}`, name: "", json: "" };
            toolBlocks[idx].json += parsed.delta.partial_json || "";
          } else if (evt === "message_stop") {
            for (const tb of Object.values(toolBlocks)) {
              let args: Record<string, any> = {};
              try { args = tb.json ? JSON.parse(tb.json) : {}; } catch { args = { raw: tb.json }; }
              if (tb.name) yield { type: "tool_call", id: tb.id, name: tb.name, arguments: args };
            }
            yield { type: "done" };
            return;
          }
        } catch { /* ignore */ }
      }
    }
    for (const tb of Object.values(toolBlocks)) {
      let args: Record<string, any> = {};
      try { args = tb.json ? JSON.parse(tb.json) : {}; } catch { args = { raw: tb.json }; }
      if (tb.name) yield { type: "tool_call", id: tb.id, name: tb.name, arguments: args };
    }
  }

  async models(): Promise<ModelInfo[]> {
    return [
      { id: "claude-3-5-sonnet-20241022", name: "Claude 3.5 Sonnet", provider: "anthropic", contextWindow: 200000 },
      { id: "claude-3-5-haiku-20241022", name: "Claude 3.5 Haiku", provider: "anthropic", contextWindow: 200000 },
    ];
  }
}

// ==========================================
// xAI Grok Provider (OpenAI-compatible)
// ==========================================
export class GrokProvider extends OpenAICompatibleProvider {
  override provider: ModelProvider = "grok";
  constructor(apiKey = process.env.XAI_API_KEY || "") {
    super({ apiKey, baseUrl: "https://api.x.ai/v1", providerLabel: "grok" });
  }
  override async models(): Promise<ModelInfo[]> {
    try {
      const ids = await this.fetchModelIds();
      if (ids.length > 0) return ids.map((id) => ({ id, name: id, provider: "grok" as ModelProvider, contextWindow: 128000 }));
    } catch { /* static fallback */ }
    return [{ id: "grok-2-latest", name: "Grok 2 (xAI)", provider: "grok", contextWindow: 128000 }];
  }
}

// ==========================================
// Local / Ollama Provider (OpenAI-compatible)
// ==========================================
export class LocalProvider extends OpenAICompatibleProvider {
  override provider: ModelProvider = "local";
  constructor(baseUrl = process.env.LOCAL_AI_BASE_URL || "http://localhost:11434/v1") {
    super({ apiKey: "local", baseUrl, providerLabel: "local" });
  }
  override async models(): Promise<ModelInfo[]> {
    try {
      const ids = await this.fetchModelIds();
      if (ids.length > 0) return ids.map((id) => ({ id, name: id, provider: "local" as ModelProvider, contextWindow: 128000 }));
    } catch { /* static fallback */ }
    return [{ id: "llama3.2:latest", name: "Llama 3.2 (Local)", provider: "local", contextWindow: 128000 }];
  }
}

// ==========================================
// Provider Registry + Factory (DB-backed, no if/else sprawl)
// ==========================================
export interface RuntimeProviderConfig {
  id: string;
  name: string;
  type: string;
  baseUrl: string;
  apiKey: string;
  defaultModel: string;
  customHeaders?: Record<string, string>;
  timeoutMs?: number;
  organizationId?: string;
  projectId?: string;
}

export class AIProviderFactory {
  static create(config: RuntimeProviderConfig): AIProvider {
    const t = (config.type || "openai_compatible").toLowerCase();
    switch (t) {
      case "anthropic":
        return new ClaudeProvider(config.apiKey, config.baseUrl || "https://api.anthropic.com");
      case "gemini":
        return new GeminiProvider(config.apiKey);
      case "openai":
        return new OpenAIProvider(config.apiKey, config.baseUrl || "https://api.openai.com/v1");
      case "xai":
        return new GrokProvider(config.apiKey);
      case "groq":
      case "mistral":
      case "openrouter":
      case "deepseek":
      case "ollama":
      case "lmstudio":
      case "custom":
      case "openai_compatible":
      default:
        return new OpenAICompatibleProvider({
          apiKey: config.apiKey,
          baseUrl: config.baseUrl,
          defaultModel: config.defaultModel,
          customHeaders: config.customHeaders,
          timeoutMs: config.timeoutMs,
          providerLabel: config.name || t,
        });
    }
  }

  static defaultBaseUrlFor(type: string): string {
    return PROVIDER_PRESETS[type?.toLowerCase()]?.baseUrl || "";
  }
}

export class AIProviderRegistry {
  private providers: Map<ModelProvider, AIProvider> = new Map();

  constructor() {
    this.registerProvider(new GeminiProvider());
    this.registerProvider(new OpenAIProvider());
    this.registerProvider(new ClaudeProvider());
    this.registerProvider(new GrokProvider());
    this.registerProvider(new LocalProvider());
  }

  registerProvider(provider: AIProvider) {
    this.providers.set(provider.provider, provider);
  }

  registerRuntime(id: string, provider: AIProvider) {
    this.providers.set(id as ModelProvider, provider);
  }

  getProvider(provider: ModelProvider): AIProvider {
    const p = this.providers.get(provider);
    if (!p) throw new Error(`Provider '${provider}' not registered.`);
    return p;
  }

  /** Build a runtime instance from a DB provider config (decrypted key passed in). */
  providerForConfig(config: RuntimeProviderConfig): AIProvider {
    return AIProviderFactory.create(config);
  }
}

// ==========================================
// AI Router with Failover (env providers + dynamic registry)
// ==========================================
export class AIRouter {
  private registry = new AIProviderRegistry();

  registerProvider(provider: AIProvider) {
    this.registry.registerProvider(provider);
  }

  getProvider(provider: ModelProvider): AIProvider {
    return this.registry.getProvider(provider);
  }

  get registryRef(): AIProviderRegistry {
    return this.registry;
  }

  /** Chat using a pre-built runtime provider instance (DB-backed path). */
  async *chatWithInstance(
    provider: AIProvider,
    request: ChatRequest,
    fallback?: { provider: AIProvider; model?: string; onFailover?: (err: Error) => void }
  ): AsyncIterable<AIEvent> {
    try {
      for await (const event of provider.chat(request)) yield event;
    } catch (err: any) {
      const retryable = err?.retryable !== false && err?.kind !== "PROVIDER_AUTH" && err?.kind !== "INVALID_MODEL" && err?.kind !== "INVALID_REQUEST";
      if (fallback && retryable) {
        fallback.onFailover?.(err);
        const fbRequest = { ...request, model: fallback.model || request.model };
        for await (const event of fallback.provider.chat(fbRequest)) yield event;
      } else {
        throw err;
      }
    }
  }

  async *chatWithFailover(
    request: ChatRequest,
    primaryProvider: ModelProvider,
    fallbackProvider?: ModelProvider,
    fallbackModel?: string
  ): AsyncIterable<AIEvent> {
    try {
      const provider = this.getProvider(primaryProvider);
      for await (const event of provider.chat(request)) yield event;
    } catch (err: any) {
      if (fallbackProvider && fallbackProvider !== primaryProvider) {
        console.warn(`[AIRouter] Primary ${primaryProvider} failed: ${err.message}. Failing over to ${fallbackProvider}`);
        const fb = this.getProvider(fallbackProvider);
        const fbRequest = { ...request, model: fallbackModel || request.model };
        for await (const event of fb.chat(fbRequest)) yield event;
      } else {
        throw err;
      }
    }
  }

  async listAllModels(): Promise<ModelInfo[]> {
    const list: ModelInfo[] = [];
    for (const name of ["gemini", "openai", "anthropic", "grok", "local"] as ModelProvider[]) {
      try {
        list.push(...(await this.getProvider(name).models()));
      } catch { /* ignore */ }
    }
    return list;
  }
}
