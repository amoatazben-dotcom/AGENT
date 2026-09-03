/**
 * Generic OpenAI-compatible provider.
 * Works with ANY OpenAI-compatible API: OpenAI, OpenRouter, Groq, DeepSeek,
 * xAI, Mistral, Ollama (/v1), LM Studio, vLLM, LiteLLM, custom servers.
 *
 * User supplies: baseUrl + apiKey + model. Nothing hardcoded.
 */
import type { AIEvent, AIProvider, CanonicalMessage, ChatRequest, ModelInfo, ToolDefinition } from "./types.js";
import type { ModelProvider } from "@agentforge/protocol";
import { normalizeBaseUrl } from "./base-url.js";
import { providerError, throwForStatus, withRetry } from "./errors.js";

export interface OpenAICompatibleConfig {
  apiKey?: string;
  baseUrl: string;
  defaultModel?: string;
  customHeaders?: Record<string, string>;
  timeoutMs?: number;
  providerLabel?: string;
}

export interface TestConnectionOutcome {
  ok: boolean;
  status: "connected" | "auth_failed" | "invalid_url" | "timeout" | "unreachable" | "no_models" | "rate_limited" | "invalid_response";
  latencyMs: number;
  httpStatus?: number;
  message: string;
  models: string[];
}

export class OpenAICompatibleProvider implements AIProvider {
  provider: ModelProvider = "openai_compatible";
  protected apiKey: string;
  protected baseUrl: string;
  protected chatUrl: string;
  protected modelsUrl: string | null;
  protected customHeaders: Record<string, string>;
  protected timeoutMs: number;
  protected label: string;

  constructor(config: OpenAICompatibleConfig) {
    const norm = normalizeBaseUrl(config.baseUrl);
    this.baseUrl = norm.baseUrl;
    this.chatUrl = norm.chatCompletionsUrl;
    this.modelsUrl = norm.modelsUrl;
    this.apiKey = config.apiKey || "";
    this.customHeaders = config.customHeaders || {};
    this.timeoutMs = config.timeoutMs ?? 60000;
    this.label = config.providerLabel || "openai_compatible";
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  private headers(): Record<string, string> {
    const h: Record<string, string> = { "Content-Type": "application/json", ...this.customHeaders };
    if (this.apiKey) h["Authorization"] = `Bearer ${this.apiKey}`;
    return h;
  }

  private toOpenAIMessages(messages: CanonicalMessage[]): any[] {
    return messages.map((m) => {
      if (m.role === "tool") {
        return { role: "tool", tool_call_id: m.toolCallId, content: m.content };
      }
      if (m.role === "assistant" && m.toolCalls) {
        return {
          role: "assistant",
          content: m.content || null,
          tool_calls: m.toolCalls.map((tc) => ({
            id: tc.id,
            type: "function",
            function: { name: tc.name, arguments: JSON.stringify(tc.arguments) },
          })),
        };
      }
      return { role: m.role, content: m.content };
    });
  }

  private toOpenAITools(tools?: ToolDefinition[]): any[] | undefined {
    if (!tools || tools.length === 0) return undefined;
    return tools.map((t) => ({
      type: "function",
      function: { name: t.name, description: t.description, parameters: t.parameters },
    }));
  }

  async *chat(request: ChatRequest): AsyncIterable<AIEvent> {
    const body: Record<string, any> = {
      model: request.model,
      messages: this.toOpenAIMessages(request.messages),
      temperature: request.temperature ?? 0.7,
      stream: true,
    };
    const tools = this.toOpenAITools(request.tools);
    if (tools) body.tools = tools;
    if (request.maxTokens) body.max_tokens = request.maxTokens;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let res: Response;
    try {
      res = await withRetry(() =>
        fetch(this.chatUrl, { method: "POST", headers: this.headers(), body: JSON.stringify(body), signal: controller.signal })
      );
    } catch (e: any) {
      clearTimeout(timer);
      if (e?.name === "AbortError") throw providerError("TIMEOUT", `Request timed out after ${this.timeoutMs}ms.`, { retryable: true });
      if (e?.kind) throw e;
      throw providerError("NETWORK_ERROR", `Network error reaching ${this.baseUrl}: ${e?.message || e}`, { retryable: true });
    }
    clearTimeout(timer);

    if (!res.ok) await throwForStatus(res, `${this.label} chat`);
    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const acc: Record<number, { id: string; name: string; args: string }> = {};
    let usage: { promptTokens: number; completionTokens: number; totalTokens: number } | null = null;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || !trimmed.startsWith("data:")) continue;
        const dataStr = trimmed.slice(5).trim();
        if (dataStr === "[DONE]") {
          for (const tc of Object.values(acc)) {
            let parsed: Record<string, any> = {};
            try {
              parsed = tc.args ? JSON.parse(tc.args) : {};
            } catch {
              parsed = { raw: tc.args };
            }
            if (tc.name) yield { type: "tool_call", id: tc.id, name: tc.name, arguments: parsed };
          }
          if (usage) yield { type: "usage", ...usage };
          yield { type: "done" };
          return;
        }
        try {
          const parsed = JSON.parse(dataStr);
          if (parsed.usage) {
            usage = {
              promptTokens: parsed.usage.prompt_tokens ?? 0,
              completionTokens: parsed.usage.completion_tokens ?? 0,
              totalTokens: parsed.usage.total_tokens ?? 0,
            };
          }
          const choice = parsed.choices?.[0];
          const delta = choice?.delta;
          if (delta?.content) yield { type: "delta", content: delta.content };
          if (delta?.tool_calls) {
            for (const tc of delta.tool_calls) {
              const index = tc.index ?? 0;
              if (!acc[index]) acc[index] = { id: tc.id || `call_${Date.now()}_${index}`, name: "", args: "" };
              if (tc.id) acc[index].id = tc.id;
              if (tc.function?.name) acc[index].name += tc.function.name;
              if (tc.function?.arguments) acc[index].args += tc.function.arguments;
            }
          }
        } catch {
          // ignore fragmented chunk parse errors
        }
      }
    }
  }

  /** Non-streaming single-shot chat (used by tests / simple calls). */
  async chatOnce(request: ChatRequest): Promise<{ content: string; usage?: { promptTokens: number; completionTokens: number; totalTokens: number } }> {
    let content = "";
    let usage: any;
    for await (const e of this.chat(request)) {
      if (e.type === "delta") content += e.content;
      if (e.type === "usage") usage = e;
    }
    return { content, usage };
  }

  async models(): Promise<ModelInfo[]> {
    const ids = await this.fetchModelIds();
    return ids.map((id) => ({ id, name: id, provider: "openai_compatible" as any, contextWindow: 0 }));
  }

  /** GET {baseUrl}/models → data[].id. Throws structured errors; returns [] only when endpoint 404s. */
  async fetchModelIds(): Promise<string[]> {
    if (!this.modelsUrl) return [];
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.min(this.timeoutMs, 20000));
    try {
      const res = await fetch(this.modelsUrl, { headers: this.headers(), signal: controller.signal });
      clearTimeout(timer);
      if (res.status === 404) return [];
      if (!res.ok) await throwForStatus(res, `${this.label} models`);
      const json: any = await res.json();
      const data = Array.isArray(json?.data) ? json.data : Array.isArray(json?.models) ? json.models : [];
      return data.map((m: any) => (typeof m === "string" ? m : m.id || m.name)).filter(Boolean);
    } catch (e: any) {
      clearTimeout(timer);
      if (e?.name === "AbortError") throw providerError("TIMEOUT", "Models request timed out.", { retryable: true });
      throw e;
    }
  }

  /** Real connection test: validates URL, auth, latency, model listing. */
  async testConnection(): Promise<TestConnectionOutcome> {
    const started = Date.now();
    // 1) URL already normalized in constructor; re-validate
    try {
      normalizeBaseUrl(this.baseUrl);
    } catch (e: any) {
      return { ok: false, status: "invalid_url", latencyMs: Date.now() - started, message: e.message, models: [] };
    }
    if (!this.modelsUrl) {
      return { ok: false, status: "invalid_url", latencyMs: Date.now() - started, message: "No /models endpoint for this provider.", models: [] };
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.min(this.timeoutMs, 20000));
    try {
      const res = await fetch(this.modelsUrl, { headers: this.headers(), signal: controller.signal });
      const latencyMs = Date.now() - started;
      clearTimeout(timer);
      if (res.ok) {
        let models: string[] = [];
        try {
          const json: any = await res.json();
          const data = Array.isArray(json?.data) ? json.data : [];
          models = data.map((m: any) => (typeof m === "string" ? m : m.id)).filter(Boolean);
        } catch {
          return { ok: false, status: "invalid_response", latencyMs, httpStatus: res.status, message: "Invalid JSON from /models.", models: [] };
        }
        if (models.length === 0) {
          return { ok: true, status: "no_models", latencyMs, httpStatus: res.status, message: "Connected, but provider returned no models. You can enter a Model ID manually.", models };
        }
        return { ok: true, status: "connected", latencyMs, httpStatus: res.status, message: `Connected • ${latencyMs} ms • ${models.length} models`, models };
      }
      if (res.status === 401 || res.status === 403) {
        return { ok: false, status: "auth_failed", latencyMs, httpStatus: res.status, message: "Authentication failed. Check API key.", models: [] };
      }
      if (res.status === 404) {
        return { ok: false, status: "no_models", latencyMs, httpStatus: res.status, message: "No /models endpoint. Enter Model ID manually.", models: [] };
      }
      if (res.status === 429) {
        return { ok: false, status: "rate_limited", latencyMs, httpStatus: res.status, message: "Rate limited (429). Try again shortly.", models: [] };
      }
      return { ok: false, status: "invalid_response", latencyMs, httpStatus: res.status, message: `Unexpected status ${res.status}.`, models: [] };
    } catch (e: any) {
      clearTimeout(timer);
      const latencyMs = Date.now() - started;
      if (e?.name === "AbortError") return { ok: false, status: "timeout", latencyMs, message: "Connection timed out.", models: [] };
      return { ok: false, status: "unreachable", latencyMs, message: `Server unreachable: ${e?.message || e}`, models: [] };
    }
  }
}
