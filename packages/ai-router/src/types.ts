import type { ModelProvider } from "@agentforge/protocol";

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
