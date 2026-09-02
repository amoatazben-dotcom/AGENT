import { ModelProvider } from "@agentforge/protocol";

export interface CanonicalMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  name?: string;
  toolCallId?: string;
  toolCalls?: Array<{
    id: string;
    name: string;
    arguments: Record<string, any>;
  }>;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, any>;
}

export interface ChatRequest {
  model: string;
  messages: CanonicalMessage[];
  tools?: ToolDefinition[];
  temperature?: number;
  maxTokens?: number;
  stream?: boolean;
}

export type AIEvent =
  | { type: "delta"; content: string }
  | { type: "tool_call"; id: string; name: string; arguments: Record<string, any> }
  | { type: "usage"; promptTokens: number; completionTokens: number; totalTokens: number }
  | { type: "done" };

export interface ModelInfo {
  id: string;
  name: string;
  provider: ModelProvider;
  contextWindow: number;
}

export interface AIProvider {
  provider: ModelProvider;
  chat(request: ChatRequest): AsyncIterable<AIEvent>;
  models(): Promise<ModelInfo[]>;
}

// ==========================================
// OpenAI Provider
// ==========================================
export class OpenAIProvider implements AIProvider {
  provider: ModelProvider = "openai";
  private apiKey: string;
  private baseUrl: string;

  constructor(apiKey = process.env.OPENAI_API_KEY || "", baseUrl = "https://api.openai.com/v1") {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
  }

  async *chat(request: ChatRequest): AsyncIterable<AIEvent> {
    if (!this.apiKey && !this.baseUrl.includes("localhost")) {
      throw new Error("OpenAI API key is missing. Set OPENAI_API_KEY in environment or Settings.");
    }

    const body: Record<string, any> = {
      model: request.model,
      messages: request.messages.map((m) => {
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
      }),
      temperature: request.temperature ?? 0.7,
      stream: true,
    };

    if (request.tools && request.tools.length > 0) {
      body.tools = request.tools.map((t) => ({
        type: "function",
        function: {
          name: t.name,
          description: t.description,
          parameters: t.parameters,
        },
      }));
    }

    const res = await fetch(`${this.baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const err = await res.text();
      throw new Error(`OpenAI API error [${res.status}]: ${err}`);
    }

    if (!res.body) return;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    const toolCallsAccumulator: Record<number, { id: string; name: string; args: string }> = {};

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
        if (dataStr === "[DONE]") {
          for (const tc of Object.values(toolCallsAccumulator)) {
            let parsedArgs = {};
            try {
              parsedArgs = JSON.parse(tc.args);
            } catch {
              parsedArgs = { raw: tc.args };
            }
            yield { type: "tool_call", id: tc.id, name: tc.name, arguments: parsedArgs };
          }
          yield { type: "done" };
          return;
        }

        try {
          const parsed = JSON.parse(dataStr);
          const choice = parsed.choices?.[0];
          const delta = choice?.delta;

          if (delta?.content) {
            yield { type: "delta", content: delta.content };
          }

          if (delta?.tool_calls) {
            for (const tc of delta.tool_calls) {
              const index = tc.index ?? 0;
              if (!toolCallsAccumulator[index]) {
                toolCallsAccumulator[index] = { id: tc.id || `call_${Date.now()}`, name: "", args: "" };
              }
              if (tc.function?.name) toolCallsAccumulator[index].name += tc.function.name;
              if (tc.function?.arguments) toolCallsAccumulator[index].args += tc.function.arguments;
            }
          }
        } catch {
          // ignore parse errors on fragmented stream chunks
        }
      }
    }
  }

  async models(): Promise<ModelInfo[]> {
    return [
      { id: "gpt-4o", name: "GPT-4o (Omni)", provider: "openai", contextWindow: 128000 },
      { id: "gpt-4o-mini", name: "GPT-4o Mini", provider: "openai", contextWindow: 128000 },
      { id: "o1-mini", name: "o1-mini (Reasoning)", provider: "openai", contextWindow: 128000 },
    ];
  }
}

// ==========================================
// Google Gemini Provider
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
      throw new Error("Gemini API key is missing. Configure in environment or settings.");
    }

    const modelName = request.model.includes("gemini") ? request.model : "gemini-1.5-flash";
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelName}:streamGenerateContent?key=${key}&alt=sse`;

    // Format messages for Gemini
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
          parts: [
            {
              functionResponse: {
                name: msg.name || "tool_result",
                response: { content: msg.content },
              },
            },
          ],
        });
      }
    }

    const body: Record<string, any> = { contents };
    if (systemInstruction) body.systemInstruction = systemInstruction;

    if (request.tools && request.tools.length > 0) {
      body.tools = [
        {
          functionDeclarations: request.tools.map((t) => ({
            name: t.name,
            description: t.description,
            parameters: t.parameters,
          })),
        },
      ];
    }

    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const err = await res.text();
      throw new Error(`Gemini API error [${res.status}]: ${err}`);
    }

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
              if (part.text) {
                yield { type: "delta", content: part.text };
              }
              if (part.functionCall) {
                yield {
                  type: "tool_call",
                  id: `gemini_call_${Date.now()}`,
                  name: part.functionCall.name,
                  arguments: part.functionCall.args || {},
                };
              }
            }
          }
        } catch {
          // ignore stream parse errors
        }
      }
    }

    yield { type: "done" };
  }

  async models(): Promise<ModelInfo[]> {
    return [
      { id: "gemini-1.5-flash", name: "Gemini 1.5 Flash (Fast & Capable)", provider: "gemini", contextWindow: 1000000 },
      { id: "gemini-1.5-pro", name: "Gemini 1.5 Pro (Deep Reasoning)", provider: "gemini", contextWindow: 2000000 },
      { id: "gemini-2.0-flash", name: "Gemini 2.0 Flash (Next-Gen)", provider: "gemini", contextWindow: 1000000 },
    ];
  }
}

// ==========================================
// Anthropic Claude Provider
// ==========================================
export class ClaudeProvider implements AIProvider {
  provider: ModelProvider = "anthropic";
  private apiKey: string;

  constructor(apiKey = process.env.ANTHROPIC_API_KEY || "") {
    this.apiKey = apiKey;
  }

  async *chat(request: ChatRequest): AsyncIterable<AIEvent> {
    if (!this.apiKey) {
      throw new Error("Anthropic API key is missing. Set ANTHROPIC_API_KEY in environment or Settings.");
    }

    const systemMessages = request.messages.filter((m) => m.role === "system").map((m) => m.content).join("\n\n");
    const chatMessages = request.messages
      .filter((m) => m.role !== "system")
      .map((m) => ({
        role: m.role === "tool" ? "user" : m.role,
        content: m.content,
      }));

    const body: Record<string, any> = {
      model: request.model.includes("claude") ? request.model : "claude-3-5-sonnet-20241022",
      max_tokens: request.maxTokens ?? 4096,
      messages: chatMessages,
      stream: true,
    };
    if (systemMessages) body.system = systemMessages;

    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": this.apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const err = await res.text();
      throw new Error(`Anthropic Claude API error [${res.status}]: ${err}`);
    }

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
          if (parsed.type === "content_block_delta" && parsed.delta?.text) {
            yield { type: "delta", content: parsed.delta.text };
          }
          if (parsed.type === "message_stop") {
            yield { type: "done" };
            return;
          }
        } catch {
          // ignore stream parse errors
        }
      }
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
// xAI Grok Provider
// ==========================================
export class GrokProvider extends OpenAIProvider {
  override provider: ModelProvider = "grok";
  constructor(apiKey = process.env.XAI_API_KEY || "") {
    super(apiKey, "https://api.x.ai/v1");
  }

  override async models(): Promise<ModelInfo[]> {
    return [
      { id: "grok-2-latest", name: "Grok 2 (xAI)", provider: "grok", contextWindow: 128000 },
      { id: "grok-beta", name: "Grok Beta", provider: "grok", contextWindow: 128000 },
    ];
  }
}

// ==========================================
// Local / OpenAI-Compatible Provider
// ==========================================
export class LocalProvider extends OpenAIProvider {
  override provider: ModelProvider = "local";
  constructor(baseUrl = process.env.LOCAL_AI_BASE_URL || "http://localhost:11434/v1") {
    super("local", baseUrl);
  }

  override async models(): Promise<ModelInfo[]> {
    return [
      { id: "llama3.2:latest", name: "Llama 3.2 (Local)", provider: "local", contextWindow: 128000 },
      { id: "qwen2.5-coder:latest", name: "Qwen 2.5 Coder (Local)", provider: "local", contextWindow: 128000 },
    ];
  }
}

// ==========================================
// AI Router with Failover & Fallback
// ==========================================
export class AIRouter {
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

  getProvider(provider: ModelProvider): AIProvider {
    const p = this.providers.get(provider);
    if (!p) throw new Error(`Provider '${provider}' not registered.`);
    return p;
  }

  async *chatWithFailover(
    request: ChatRequest,
    primaryProvider: ModelProvider,
    fallbackProvider?: ModelProvider,
    fallbackModel?: string
  ): AsyncIterable<AIEvent> {
    try {
      const provider = this.getProvider(primaryProvider);
      for await (const event of provider.chat(request)) {
        yield event;
      }
    } catch (err: any) {
      if (fallbackProvider && fallbackProvider !== primaryProvider) {
        console.warn(`[AIRouter] Primary provider ${primaryProvider} failed: ${err.message}. Failing over to ${fallbackProvider}`);
        const fallback = this.getProvider(fallbackProvider);
        const fbRequest = { ...request, model: fallbackModel || request.model };
        for await (const event of fallback.chat(fbRequest)) {
          yield event;
        }
      } else {
        throw err;
      }
    }
  }

  async listAllModels(): Promise<ModelInfo[]> {
    const list: ModelInfo[] = [];
    for (const p of this.providers.values()) {
      try {
        const models = await p.models();
        list.push(...models);
      } catch {
        // ignore provider model listing failures
      }
    }
    return list;
  }
}
