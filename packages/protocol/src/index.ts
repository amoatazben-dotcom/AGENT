import { z } from "zod";

// ==========================================
// User & Auth Types
// ==========================================
export const UserRoleSchema = z.enum(["admin", "user"]);
export type UserRole = z.infer<typeof UserRoleSchema>;

export const UserSchema = z.object({
  id: z.string().uuid(),
  email: z.string().email(),
  name: z.string(),
  role: UserRoleSchema,
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type User = z.infer<typeof UserSchema>;

export const AuthRegisterRequestSchema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
  name: z.string().min(2),
});
export type AuthRegisterRequest = z.infer<typeof AuthRegisterRequestSchema>;

export const AuthLoginRequestSchema = z.object({
  email: z.string().email(),
  password: z.string(),
});
export type AuthLoginRequest = z.infer<typeof AuthLoginRequestSchema>;

export const AuthResponseSchema = z.object({
  token: z.string(),
  refreshToken: z.string(),
  user: UserSchema,
});
export type AuthResponse = z.infer<typeof AuthResponseSchema>;

// ==========================================
// Agent Types
// ==========================================
export const ModelProviderSchema = z.enum([
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
  "grok",
  "local",
]);
export type ModelProvider = z.infer<typeof ModelProviderSchema>;

export const ApprovalPolicySchema = z.enum(["allow", "deny", "require_approval"]);
export type ApprovalPolicy = z.infer<typeof ApprovalPolicySchema>;

export const AgentPermissionsSchema = z.object({
  computerUse: z.boolean().default(true),
  shell: z.boolean().default(true),
  filesystem: z.boolean().default(true),
  network: z.boolean().default(true),
  automation: z.boolean().default(true),
  subagents: z.boolean().default(true),
});
export type AgentPermissions = z.infer<typeof AgentPermissionsSchema>;

export const AgentSchema = z.object({
  id: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().default(""),
  icon: z.string().default("smart_toy"),
  systemPrompt: z.string(),
  primaryProvider: ModelProviderSchema.default("gemini"),
  primaryModel: z.string().default("gemini-1.5-flash"),
  fallbackProvider: ModelProviderSchema.optional(),
  fallbackModel: z.string().optional(),
  temperature: z.number().min(0).max(2).default(0.7),
  maxSteps: z.number().int().min(1).max(100).default(25),
  enabledTools: z.array(z.string()).default([]),
  mcpServers: z.array(z.string()).default([]),
  connectors: z.array(z.string()).default([]),
  approvalPolicy: ApprovalPolicySchema.default("require_approval"),
  permissions: AgentPermissionsSchema.default({}),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Agent = z.infer<typeof AgentSchema>;

export const CreateAgentRequestSchema = AgentSchema.omit({
  id: true,
  createdAt: true,
  updatedAt: true,
});
export type CreateAgentRequest = z.infer<typeof CreateAgentRequestSchema>;

export const UpdateAgentRequestSchema = CreateAgentRequestSchema.partial();
export type UpdateAgentRequest = z.infer<typeof UpdateAgentRequestSchema>;

// ==========================================
// Conversations & Messages
// ==========================================
export const ConversationSchema = z.object({
  id: z.string().uuid(),
  title: z.string(),
  agentId: z.string().uuid(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Conversation = z.infer<typeof ConversationSchema>;

export const MessageRoleSchema = z.enum(["user", "assistant", "system", "tool"]);
export type MessageRole = z.infer<typeof MessageRoleSchema>;

export const MessageAttachmentSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  mimeType: z.string(),
  url: z.string(),
  size: z.number(),
});
export type MessageAttachment = z.infer<typeof MessageAttachmentSchema>;

export const MessageSchema = z.object({
  id: z.string().uuid(),
  conversationId: z.string().uuid(),
  role: MessageRoleSchema,
  content: z.string(),
  toolCalls: z.array(z.any()).optional(),
  toolResultId: z.string().optional(),
  attachments: z.array(MessageAttachmentSchema).default([]),
  createdAt: z.string(),
});
export type Message = z.infer<typeof MessageSchema>;

export const SendMessageRequestSchema = z.object({
  conversationId: z.string().uuid(),
  content: z.string(),
  agentId: z.string().uuid().optional(),
  attachments: z.array(z.string()).default([]),
});
export type SendMessageRequest = z.infer<typeof SendMessageRequestSchema>;

// ==========================================
// Agent Runs & State Machine
// ==========================================
export const RunStatusSchema = z.enum([
  "queued",
  "preparing",
  "running",
  "waiting_for_tool",
  "waiting_for_approval",
  "waiting_for_subagent",
  "completed",
  "failed",
  "cancelled",
  "timed_out",
]);
export type RunStatus = z.infer<typeof RunStatusSchema>;

export const RunSchema = z.object({
  id: z.string().uuid(),
  conversationId: z.string().uuid(),
  agentId: z.string().uuid(),
  status: RunStatusSchema,
  userPrompt: z.string(),
  currentStep: z.number().default(0),
  maxSteps: z.number().default(25),
  tokensUsed: z.number().default(0),
  costEstimate: z.number().default(0),
  error: z.string().nullable().optional(),
  startedAt: z.string().nullable().optional(),
  completedAt: z.string().nullable().optional(),
  createdAt: z.string(),
});
export type Run = z.infer<typeof RunSchema>;

// ==========================================
// Tools & Execution
// ==========================================
export const ToolRiskLevelSchema = z.enum(["low", "medium", "high", "critical"]);
export type ToolRiskLevel = z.infer<typeof ToolRiskLevelSchema>;

export const ToolCallSchema = z.object({
  id: z.string(),
  runId: z.string().uuid(),
  toolName: z.string(),
  arguments: z.record(z.any()),
  riskLevel: ToolRiskLevelSchema,
  status: z.enum(["pending", "running", "completed", "failed", "blocked_approval"]),
  result: z.any().optional(),
  error: z.string().optional(),
  executionTimeMs: z.number().optional(),
  createdAt: z.string(),
});
export type ToolCall = z.infer<typeof ToolCallSchema>;

// ==========================================
// Human Approvals
// ==========================================
export const ApprovalStatusSchema = z.enum([
  "pending",
  "approved",
  "rejected",
  "expired",
  "cancelled",
]);
export type ApprovalStatus = z.infer<typeof ApprovalStatusSchema>;

export const ApprovalSchema = z.object({
  id: z.string().uuid(),
  runId: z.string().uuid(),
  conversationId: z.string().uuid(),
  toolName: z.string(),
  arguments: z.record(z.any()),
  reason: z.string(),
  riskLevel: ToolRiskLevelSchema,
  status: ApprovalStatusSchema,
  decisionBy: z.string().optional(),
  decisionAt: z.string().optional(),
  createdAt: z.string(),
});
export type Approval = z.infer<typeof ApprovalSchema>;

export const ResolveApprovalRequestSchema = z.object({
  decision: z.enum(["approved", "rejected"]),
  reason: z.string().optional(),
});
export type ResolveApprovalRequest = z.infer<typeof ResolveApprovalRequestSchema>;

// ==========================================
// Computer Viewer & Remote Pool
// ==========================================
export const ComputerStatusSchema = z.enum([
  "initializing",
  "running",
  "idle",
  "busy",
  "stopped",
  "error",
]);
export type ComputerStatus = z.infer<typeof ComputerStatusSchema>;

export const ComputerSessionSchema = z.object({
  id: z.string().uuid(),
  runId: z.string().uuid().optional(),
  status: ComputerStatusSchema,
  activeUrl: z.string().default("about:blank"),
  cursorX: z.number().default(0),
  cursorY: z.number().default(0),
  lastAction: z.string().default(""),
  latestScreenshotBase64: z.string().optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type ComputerSession = z.infer<typeof ComputerSessionSchema>;

export const ComputerActionSchema = z.object({
  action: z.enum(["open", "navigate", "click", "type", "scroll", "stop", "restart"]),
  url: z.string().optional(),
  selector: z.string().optional(),
  text: z.string().optional(),
  x: z.number().optional(),
  y: z.number().optional(),
  deltaY: z.number().optional(),
});
export type ComputerAction = z.infer<typeof ComputerActionSchema>;

// ==========================================
// Automations
// ==========================================
export const AutomationTypeSchema = z.enum(["one_time", "cron", "recurring", "conditional"]);
export type AutomationType = z.infer<typeof AutomationTypeSchema>;

export const AutomationSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  description: z.string().default(""),
  type: AutomationTypeSchema,
  cronExpression: z.string().optional(),
  agentId: z.string().uuid(),
  prompt: z.string(),
  enabled: z.boolean().default(true),
  lastRunAt: z.string().nullable().optional(),
  nextRunAt: z.string().nullable().optional(),
  lastStatus: z.string().nullable().optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Automation = z.infer<typeof AutomationSchema>;

export const CreateAutomationRequestSchema = AutomationSchema.omit({
  id: true,
  createdAt: true,
  updatedAt: true,
  lastRunAt: true,
  nextRunAt: true,
  lastStatus: true,
});
export type CreateAutomationRequest = z.infer<typeof CreateAutomationRequestSchema>;

// ==========================================
// Files & Storage
// ==========================================
export const FileRecordSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  path: z.string(),
  mimeType: z.string(),
  size: z.number(),
  conversationId: z.string().uuid().optional(),
  runId: z.string().uuid().optional(),
  downloadUrl: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type FileRecord = z.infer<typeof FileRecordSchema>;

// ==========================================
// Connectors
// ==========================================
export const ConnectorTypeSchema = z.enum([
  "http",
  "oauth2",
  "apikey",
  "mcp",
  "gmail",
  "google_drive",
  "google_calendar",
  "github",
  "slack",
  "notion",
]);
export type ConnectorType = z.infer<typeof ConnectorTypeSchema>;

export const ConnectorSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  type: ConnectorTypeSchema,
  description: z.string().default(""),
  status: z.enum(["connected", "disconnected", "error"]),
  config: z.record(z.any()).default({}),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Connector = z.infer<typeof ConnectorSchema>;

// ==========================================
// WebSocket Events Protocol
// ==========================================
export const WsEventTypeSchema = z.enum([
  "run.created",
  "run.started",
  "run.status",
  "assistant.delta",
  "assistant.message",
  "tool.started",
  "tool.progress",
  "tool.completed",
  "tool.failed",
  "computer.started",
  "computer.frame",
  "computer.action",
  "computer.stopped",
  "approval.created",
  "approval.resolved",
  "subagent.started",
  "subagent.completed",
  "file.created",
  "run.completed",
  "run.failed",
  "heartbeat",
  "ping",
  "pong",
]);
export type WsEventType = z.infer<typeof WsEventTypeSchema>;

export const WsEventEnvelopeSchema = z.object({
  eventId: z.string(),
  seq: z.number().default(0),
  type: WsEventTypeSchema,
  timestamp: z.string(),
  runId: z.string().optional(),
  conversationId: z.string().optional(),
  payload: z.any(),
});
export type WsEventEnvelope = z.infer<typeof WsEventEnvelopeSchema>;

// Provider config module (DB-backed providers, unified errors)
export * from "./providers.js";

// Client to Server WebSocket messages
export const ClientWsMessageSchema = z.object({
  action: z.enum(["subscribe", "unsubscribe", "send_message", "resolve_approval", "ping", "stop_run"]),
  conversationId: z.string().optional(),
  runId: z.string().optional(),
  payload: z.any().optional(),
});
export type ClientWsMessage = z.infer<typeof ClientWsMessageSchema>;
