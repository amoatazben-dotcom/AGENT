/**
 * Database Client Layer
 * Provides a typed in-memory store for development and testing.
 * In production, replace with Prisma client calls by setting DATABASE_URL
 * and running: pnpm --filter=@agentforge/database run db:migrate
 */

// ==========================================
// Shared Domain Types
// ==========================================

export interface UserRecord {
  id: string;
  email: string;
  name: string;
  passwordHash: string;
  role: "user" | "admin";
  createdAt: string;
  updatedAt: string;
}

export interface AgentRecord {
  id: string;
  name: string;
  description: string;
  icon: string;
  systemPrompt: string;
  primaryProvider: string;
  primaryModel: string;
  fallbackProvider?: string;
  fallbackModel?: string;
  temperature: number;
  maxSteps: number;
  approvalPolicy: "allow" | "deny" | "require_approval";
  permissions: {
    computerUse: boolean;
    shell: boolean;
    filesystem: boolean;
    network: boolean;
    automation: boolean;
    subagents: boolean;
  };
  createdAt: string;
  updatedAt: string;
}

export interface ConversationRecord {
  id: string;
  title: string;
  agentId: string;
  createdAt: string;
  updatedAt: string;
}

export interface MessageRecord {
  id: string;
  conversationId: string;
  role: "user" | "assistant" | "system" | "tool";
  content: string;
  createdAt: string;
}

export interface RunRecord {
  id: string;
  conversationId: string;
  agentId: string;
  status: "queued" | "running" | "completed" | "failed" | "cancelled" | "waiting_for_approval";
  userPrompt: string;
  currentStep: number;
  maxSteps: number;
  tokensUsed: number;
  costEstimate: number;
  error?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
}

export interface ApprovalRecord {
  id: string;
  runId: string;
  conversationId: string;
  toolName: string;
  arguments: Record<string, unknown>;
  reason: string;
  riskLevel: string;
  status: "pending" | "approved" | "rejected";
  decisionAt?: string;
  createdAt: string;
}

export interface FileRecord {
  id: string;
  runId: string;
  name: string;
  path: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface AutomationRecord {
  id: string;
  name: string;
  agentId: string;
  schedule: string;
  lastRunAt: string | null;
  nextRunAt: string;
  lastStatus: "scheduled" | "running" | "success" | "failed";
  createdAt: string;
  updatedAt: string;
}

export interface ComputerSessionRecord {
  id: string;
  runId?: string;
  status: "running" | "stopped";
  activeUrl: string;
  cursorX: number;
  cursorY: number;
  lastAction: string;
  lastFrameBase64?: string;
  updatedAt: string;
  createdAt: string;
}

export interface AuditLogRecord {
  id: string;
  userId?: string;
  action: string;
  resource: string;
  resourceId?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

// ==========================================
// Typed In-Memory Store
// ==========================================

export interface InMemoryStore {
  users: Map<string, UserRecord>;
  agents: Map<string, AgentRecord>;
  conversations: Map<string, ConversationRecord>;
  messages: Map<string, MessageRecord>;
  runs: Map<string, RunRecord>;
  approvals: Map<string, ApprovalRecord>;
  files: Map<string, FileRecord>;
  automations: Map<string, AutomationRecord>;
  computerSessions: Map<string, ComputerSessionRecord>;
  auditLogs: AuditLogRecord[];
}

class DatabaseManager {
  public memoryStore: InMemoryStore = {
    users: new Map(),
    agents: new Map(),
    conversations: new Map(),
    messages: new Map(),
    runs: new Map(),
    approvals: new Map(),
    files: new Map(),
    automations: new Map(),
    computerSessions: new Map(),
    auditLogs: [],
  };

  constructor() {
    this.seedDefaults();
  }

  private seedDefaults() {
    const now = new Date().toISOString();

    const defaultAgent: AgentRecord = {
      id: "agent-default-1",
      name: "General Assistant & Automation Agent",
      description: "Capable of browsing, shell execution, research, and filesystem management.",
      icon: "smart_toy",
      systemPrompt:
        "You are an expert autonomous AI agent equipped with shell, browser, filesystem, and subagent tools. Think methodically, execute tools accurately, and respect human approvals.",
      primaryProvider: "gemini",
      primaryModel: "gemini-1.5-flash",
      temperature: 0.7,
      maxSteps: 25,
      approvalPolicy: "require_approval",
      permissions: {
        computerUse: true,
        shell: true,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: now,
      updatedAt: now,
    };
    this.memoryStore.agents.set(defaultAgent.id, defaultAgent);

    const codingAgent: AgentRecord = {
      id: "agent-coder-2",
      name: "Engineering & Code Agent",
      description: "Specialized in software development, debugging, running tests, and git.",
      icon: "terminal",
      systemPrompt:
        "You are a Principal Software Engineer. You write clean, modular, tested code and run shell commands in the container sandbox.",
      primaryProvider: "openai",
      primaryModel: "gpt-4o",
      temperature: 0.2,
      maxSteps: 30,
      approvalPolicy: "require_approval",
      permissions: {
        computerUse: true,
        shell: true,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: now,
      updatedAt: now,
    };
    this.memoryStore.agents.set(codingAgent.id, codingAgent);

    const researchAgent: AgentRecord = {
      id: "agent-research-3",
      name: "Deep Web Research Agent",
      description: "Specialized in browser navigation, information gathering, and synthesis.",
      icon: "travel_explore",
      systemPrompt:
        "You are a Research Analyst. You navigate web sources using the browser tool, take screenshots, collect data, and write reports.",
      primaryProvider: "anthropic",
      primaryModel: "claude-3-5-sonnet-20241022",
      temperature: 0.5,
      maxSteps: 20,
      approvalPolicy: "allow",
      permissions: {
        computerUse: true,
        shell: false,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: now,
      updatedAt: now,
    };
    this.memoryStore.agents.set(researchAgent.id, researchAgent);
  }
}

export const db = new DatabaseManager();
