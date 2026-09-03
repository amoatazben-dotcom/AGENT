/**
 * AgentForge persistent database layer — SQLite local-first.
 *
 * Default: local SQLite file (agentforge.db) inside the app data directory.
 * No external PostgreSQL server required for local/dev/standalone use.
 * PostgreSQL (via Prisma schema in prisma/) remains an optional production path;
 * this module is the default standalone store.
 *
 * Persistence strategy: write-through Maps. `db.memoryStore.*` keeps the exact
 * same Map API the gateway/agent-core already use, but every set/delete is
 * synchronously persisted to SQLite via better-sqlite3, and all rows are
 * hydrated from SQLite on startup. Restart-safe, no data loss.
 */

import * as fs from "fs";
import * as path from "path";
import { DatabaseSync } from "node:sqlite";

// ==========================================
// Shared Domain Types (backwards compatible)
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

export interface SessionRecord {
  token: string;
  refreshToken: string;
  userId: string;
  expiresAt: string;
  refreshExpiresAt: string;
  createdAt: string;
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
  status: string;
  userPrompt: string;
  currentStep: number;
  maxSteps: number;
  tokensUsed: number;
  costEstimate: number;
  promptTokens?: number;
  completionTokens?: number;
  error?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
}

export interface RunEventRecord {
  id: string;
  runId: string;
  seq: number;
  type: string;
  payloadJson: string;
  createdAt: string;
}

export interface ToolCallRecord {
  id: string;
  runId: string;
  toolName: string;
  argumentsJson: string;
  status: string;
  resultJson?: string;
  error?: string;
  executionTimeMs?: number;
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
  status: string;
  decisionAt?: string;
  decisionBy?: string;
  createdAt: string;
}

export interface FileRecord {
  id: string;
  runId: string;
  conversationId?: string;
  name: string;
  path: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface AutomationRecord {
  id: string;
  name: string;
  description?: string;
  type?: string;
  cronExpression?: string;
  agentId: string;
  prompt?: string;
  schedule: string;
  enabled?: boolean;
  lastRunAt: string | null;
  nextRunAt: string;
  lastStatus: string;
  lastResult?: string;
  lastError?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AutomationRunRecord {
  id: string;
  automationId: string;
  runId?: string;
  status: string;
  result?: string;
  error?: string;
  startedAt: string;
  completedAt?: string;
}

export interface ProviderConfigRecord {
  id: string;
  name: string;
  type: string;
  apiKeyEncrypted: string;
  baseUrl: string;
  organizationId?: string;
  projectId?: string;
  defaultModel: string;
  enabled: boolean;
  isDefault: boolean;
  supportsStreaming: boolean;
  supportsTools: boolean;
  supportsVision: boolean;
  customHeadersJson: string;
  timeoutMs: number;
  status: string;
  lastTestedAt?: string;
  lastError?: string;
  latencyMs?: number;
  createdAt: string;
  updatedAt: string;
}

export interface ModelRecord {
  providerId: string;
  modelId: string;
  name: string;
  fetchedAt: string;
}

export interface MemoryRecord {
  id: string;
  agentId: string;
  conversationId?: string;
  kind: string;
  content: string;
  createdAt: string;
  updatedAt: string;
}

export interface ComputerSessionRecord {
  id: string;
  runId?: string;
  status: string;
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

export interface AppSettingRecord {
  key: string;
  valueJson: string;
  updatedAt: string;
}

// ==========================================
// Typed store shape (same as before + new)
// ==========================================

export interface InMemoryStore {
  users: Map<string, UserRecord>;
  sessions: Map<string, SessionRecord>;
  agents: Map<string, AgentRecord>;
  conversations: Map<string, ConversationRecord>;
  messages: Map<string, MessageRecord>;
  runs: Map<string, RunRecord>;
  runEvents: Map<string, RunEventRecord>;
  toolCalls: Map<string, ToolCallRecord>;
  approvals: Map<string, ApprovalRecord>;
  files: Map<string, FileRecord>;
  automations: Map<string, AutomationRecord>;
  automationRuns: Map<string, AutomationRunRecord>;
  providerConfigs: Map<string, ProviderConfigRecord>;
  models: Map<string, ModelRecord>;
  memories: Map<string, MemoryRecord>;
  computerSessions: Map<string, ComputerSessionRecord>;
  auditLogs: AuditLogRecord[];
  appSettings: Map<string, AppSettingRecord>;
}

const SCHEMA_V1 = `
CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT, passwordHash TEXT, role TEXT, createdAt TEXT, updatedAt TEXT);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, refreshToken TEXT, userId TEXT, expiresAt TEXT, refreshExpiresAt TEXT, createdAt TEXT);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(userId);
CREATE TABLE IF NOT EXISTS agents (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, title TEXT, agentId TEXT, createdAt TEXT, updatedAt TEXT);
CREATE INDEX IF NOT EXISTS idx_conv_agent ON conversations(agentId);
CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, conversationId TEXT, role TEXT, content TEXT, createdAt TEXT);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversationId);
CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS idx_runs_conv ON runs(json_extract(data,'$.conversationId'));
CREATE TABLE IF NOT EXISTS run_events (id TEXT PRIMARY KEY, runId TEXT, seq INTEGER, type TEXT, payloadJson TEXT, createdAt TEXT);
CREATE INDEX IF NOT EXISTS idx_events_run ON run_events(runId, seq);
CREATE TABLE IF NOT EXISTS tool_calls (id TEXT PRIMARY KEY, runId TEXT, toolName TEXT, argumentsJson TEXT, status TEXT, resultJson TEXT, error TEXT, executionTimeMs INTEGER, createdAt TEXT);
CREATE INDEX IF NOT EXISTS idx_toolcalls_run ON tool_calls(runId);
CREATE TABLE IF NOT EXISTS approvals (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS automations (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS automation_runs (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS provider_configs (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS models (providerId TEXT, modelId TEXT, name TEXT, fetchedAt TEXT, PRIMARY KEY (providerId, modelId));
CREATE TABLE IF NOT EXISTS memories (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS computer_sessions (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS audit_logs (id TEXT PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY, valueJson TEXT, updatedAt TEXT);
`;

function dataDir(): string {
  const dir =
    process.env.AGENTFORGE_DATA_DIR ||
    path.join(process.cwd(), "data");
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function dbPath(): string {
  if (process.env.AGENTFORGE_DB_PATH) return process.env.AGENTFORGE_DB_PATH;
  return path.join(dataDir(), "agentforge.db");
}

class DatabaseManager {
  public memoryStore: InMemoryStore = {
    users: new Map(),
    sessions: new Map(),
    agents: new Map(),
    conversations: new Map(),
    messages: new Map(),
    runs: new Map(),
    runEvents: new Map(),
    toolCalls: new Map(),
    approvals: new Map(),
    files: new Map(),
    automations: new Map(),
    automationRuns: new Map(),
    providerConfigs: new Map(),
    models: new Map(),
    memories: new Map(),
    computerSessions: new Map(),
    auditLogs: [],
    appSettings: new Map(),
  };

  private sqlite: any = null;
  private persistEnabled = false;

  constructor() {
    try {
      this.open();
    } catch (err) {
      console.warn("[database] SQLite unavailable, falling back to ephemeral memory:", (err as Error).message);
      this.seedDefaults();
    }
  }

  get dbFilePath(): string {
    return dbPath();
  }

  get isPersistent(): boolean {
    return this.persistEnabled;
  }

  private open(): void {
    const file = dbPath();
    fs.mkdirSync(path.dirname(file), { recursive: true });
    // node:sqlite (built-in, no native build step, WAL for concurrency)
    this.sqlite = new DatabaseSync(file);
    this.sqlite.exec("PRAGMA journal_mode = WAL;");
    this.sqlite.exec(SCHEMA_V1);
    this.persistEnabled = true;
    this.wrapMaps();
    this.hydrate();
    if (this.memoryStore.agents.size === 0) this.seedDefaults();
  }

  // Wrap every Map.set/delete with a synchronous SQLite write-through.
  private wrapMaps(): void {
    const sqlite = () => this.sqlite;
    const persistEnabled = () => this.persistEnabled;
    const run = (sql: string, params: any[]) => {
      if (!persistEnabled()) return;
      try {
        sqlite().prepare(sql).run(...params);
      } catch (e) {
        console.warn("[database] persist failed:", (e as Error).message);
      }
    };

    const hook = <K, V>(map: Map<K, V>, onSet: (k: K, v: V) => void, onDelete: (k: K) => void): Map<K, V> => {
      const origSet = map.set.bind(map);
      const origDelete = map.delete.bind(map);
      const origClear = map.clear.bind(map);
      (map as any).set = (k: K, v: V) => {
        const r = origSet(k, v);
        try { onSet(k, v); } catch { /* noop */ }
        return r;
      };
      (map as any).delete = (k: K) => {
        const r = origDelete(k);
        try { onDelete(k); } catch { /* noop */ }
        return r;
      };
      (map as any).clear = () => {
        // individual deletes handled by caller truncation where needed
        return origClear();
      };
      return map;
    };

    const s = this.memoryStore;
    hook(s.users, (k, v: any) => run(
      "INSERT INTO users(id,email,name,passwordHash,role,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET email=excluded.email,name=excluded.name,passwordHash=excluded.passwordHash,role=excluded.role,createdAt=excluded.createdAt,updatedAt=excluded.updatedAt",
      [v.id, v.email, v.name, v.passwordHash, v.role, v.createdAt, v.updatedAt]),
      (k) => run("DELETE FROM users WHERE id=?", [k]));
    hook(s.sessions, (k, v: any) => run(
      "INSERT INTO sessions(token,refreshToken,userId,expiresAt,refreshExpiresAt,createdAt) VALUES(?,?,?,?,?,?) ON CONFLICT(token) DO UPDATE SET refreshToken=excluded.refreshToken,userId=excluded.userId,expiresAt=excluded.expiresAt,refreshExpiresAt=excluded.refreshExpiresAt",
      [v.token, v.refreshToken, v.userId, v.expiresAt, v.refreshExpiresAt, v.createdAt]),
      (k) => run("DELETE FROM sessions WHERE token=?", [k]));
    const jsonTable = (table: string) =>
      ({
        set: (k: any, v: any) => run(`INSERT INTO ${table}(id,data) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data`, [v.id ?? k, JSON.stringify(v)]),
        del: (k: any) => run(`DELETE FROM ${table} WHERE id=?`, [k]),
      });
    const agents = jsonTable("agents");
    hook(s.agents, (k, v) => agents.set(k, v), (k) => agents.del(k));
    hook(s.conversations, (k, v: any) => run(
      "INSERT INTO conversations(id,title,agentId,createdAt,updatedAt) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,agentId=excluded.agentId,createdAt=excluded.createdAt,updatedAt=excluded.updatedAt",
      [v.id, v.title, v.agentId, v.createdAt, v.updatedAt]),
      (k) => run("DELETE FROM conversations WHERE id=?", [k]));
    hook(s.messages, (k, v: any) => run(
      "INSERT INTO messages(id,conversationId,role,content,createdAt) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET conversationId=excluded.conversationId,role=excluded.role,content=excluded.content,createdAt=excluded.createdAt",
      [v.id, v.conversationId, v.role, v.content, v.createdAt]),
      (k) => run("DELETE FROM messages WHERE id=?", [k]));
    const runs = jsonTable("runs");
    hook(s.runs, (k, v) => runs.set(k, v), (k) => runs.del(k));
    hook(s.runEvents, (k, v: any) => run(
      "INSERT INTO run_events(id,runId,seq,type,payloadJson,createdAt) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
      [v.id, v.runId, v.seq, v.type, v.payloadJson, v.createdAt]),
      (k) => run("DELETE FROM run_events WHERE id=?", [k]));
    hook(s.toolCalls, (k, v: any) => run(
      "INSERT INTO tool_calls(id,runId,toolName,argumentsJson,status,resultJson,error,executionTimeMs,createdAt) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,resultJson=excluded.resultJson,error=excluded.error,executionTimeMs=excluded.executionTimeMs",
      [v.id, v.runId, v.toolName, v.argumentsJson, v.status, v.resultJson ?? null, v.error ?? null, v.executionTimeMs ?? null, v.createdAt]),
      (k) => run("DELETE FROM tool_calls WHERE id=?", [k]));
    const approvals = jsonTable("approvals");
    hook(s.approvals, (k, v) => approvals.set(k, v), (k) => approvals.del(k));
    const files = jsonTable("files");
    hook(s.files, (k, v) => files.set(k, v), (k) => files.del(k));
    const automations = jsonTable("automations");
    hook(s.automations, (k, v) => automations.set(k, v), (k) => automations.del(k));
    const automationRuns = jsonTable("automation_runs");
    hook(s.automationRuns, (k, v) => automationRuns.set(k, v), (k) => automationRuns.del(k));
    const providers = jsonTable("provider_configs");
    hook(s.providerConfigs, (k, v) => providers.set(k, v), (k) => providers.del(k));
    hook(s.models, (k, v: any) => run(
      "INSERT INTO models(providerId,modelId,name,fetchedAt) VALUES(?,?,?,?) ON CONFLICT(providerId,modelId) DO UPDATE SET name=excluded.name,fetchedAt=excluded.fetchedAt",
      [v.providerId, v.modelId, v.name, v.fetchedAt]),
      () => undefined);
    const memories = jsonTable("memories");
    hook(s.memories, (k, v) => memories.set(k, v), (k) => memories.del(k));
    const computers = jsonTable("computer_sessions");
    hook(s.computerSessions, (k, v) => computers.set(k, v), (k) => computers.del(k));
    hook(s.appSettings, (k, v: any) => run(
      "INSERT INTO app_settings(key,valueJson,updatedAt) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET valueJson=excluded.valueJson,updatedAt=excluded.updatedAt",
      [v.key, v.valueJson, v.updatedAt]),
      (k) => run("DELETE FROM app_settings WHERE key=?", [k]));
    // auditLogs appended via helper (not a Map)
  }

  private hydrate(): void {
    const q = (sql: string, params: any[] = []): any[] => {
      try { return this.sqlite.prepare(sql).all(...params); } catch { return []; }
    };
    for (const r of q("SELECT * FROM users")) {
      this.memoryStore.users.set(r.id, { id: r.id, email: r.email, name: r.name, passwordHash: r.passwordHash, role: r.role, createdAt: r.createdAt, updatedAt: r.updatedAt });
    }
    for (const r of q("SELECT * FROM sessions")) {
      this.memoryStore.sessions.set(r.token, { token: r.token, refreshToken: r.refreshToken, userId: r.userId, expiresAt: r.expiresAt, refreshExpiresAt: r.refreshExpiresAt, createdAt: r.createdAt });
    }
    // NOTE: hydrate uses raw inserts to avoid re-persist loops (writes are idempotent anyway)
    const rawSet = <K, V>(m: Map<K, V>, k: K, v: V) => Map.prototype.set.call(m, k, v);
    for (const r of q("SELECT data FROM agents")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.agents, v.id, v);
    }
    for (const r of q("SELECT * FROM conversations")) {
      rawSet(this.memoryStore.conversations, r.id, { id: r.id, title: r.title, agentId: r.agentId, createdAt: r.createdAt, updatedAt: r.updatedAt });
    }
    for (const r of q("SELECT * FROM messages ORDER BY createdAt ASC")) {
      rawSet(this.memoryStore.messages, r.id, { id: r.id, conversationId: r.conversationId, role: r.role, content: r.content, createdAt: r.createdAt });
    }
    for (const r of q("SELECT data FROM runs")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.runs, v.id, v);
    }
    for (const r of q("SELECT * FROM run_events ORDER BY seq ASC")) {
      rawSet(this.memoryStore.runEvents, r.id, { id: r.id, runId: r.runId, seq: r.seq, type: r.type, payloadJson: r.payloadJson, createdAt: r.createdAt });
    }
    for (const r of q("SELECT * FROM tool_calls")) {
      rawSet(this.memoryStore.toolCalls, r.id, { id: r.id, runId: r.runId, toolName: r.toolName, argumentsJson: r.argumentsJson, status: r.status, resultJson: r.resultJson, error: r.error, executionTimeMs: r.executionTimeMs, createdAt: r.createdAt });
    }
    for (const r of q("SELECT data FROM approvals")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.approvals, v.id, v);
    }
    for (const r of q("SELECT data FROM files")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.files, v.id, v);
    }
    for (const r of q("SELECT data FROM automations")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.automations, v.id, v);
    }
    for (const r of q("SELECT data FROM automation_runs")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.automationRuns, v.id, v);
    }
    for (const r of q("SELECT data FROM provider_configs")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.providerConfigs, v.id, v);
    }
    for (const r of q("SELECT * FROM models")) {
      rawSet(this.memoryStore.models, `${r.providerId}:${r.modelId}`, { providerId: r.providerId, modelId: r.modelId, name: r.name, fetchedAt: r.fetchedAt });
    }
    for (const r of q("SELECT data FROM memories")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.memories, v.id, v);
    }
    for (const r of q("SELECT data FROM computer_sessions")) {
      const v = JSON.parse(r.data); rawSet(this.memoryStore.computerSessions, v.id, v);
    }
    for (const r of q("SELECT data FROM audit_logs")) {
      this.memoryStore.auditLogs.push(JSON.parse(r.data));
    }
    for (const r of q("SELECT * FROM app_settings")) {
      rawSet(this.memoryStore.appSettings, r.key, { key: r.key, valueJson: r.valueJson, updatedAt: r.updatedAt });
    }
  }

  /** Append an audit log (persisted). */
  audit(entry: Omit<AuditLogRecord, "id" | "createdAt"> & { id?: string }): AuditLogRecord {
    const full: AuditLogRecord = {
      id: entry.id || `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      createdAt: new Date().toISOString(),
      ...entry,
    } as AuditLogRecord;
    this.memoryStore.auditLogs.push(full);
    if (this.persistEnabled) {
      try {
        this.sqlite.prepare("INSERT INTO audit_logs(id,data) VALUES(?,?) ON CONFLICT(id) DO NOTHING").run(full.id, JSON.stringify(full));
      } catch { /* noop */ }
    }
    return full;
  }

  /** Dangerous: wipe selected scopes. Used by Settings reset with explicit confirmation. */
  resetScope(scope: "conversations" | "execution" | "cache" | "factory"): { cleared: string[] } {
    const cleared: string[] = [];
    const del = (table: string) => {
      if (this.persistEnabled) {
        try { this.sqlite.prepare(`DELETE FROM ${table}`).run(); } catch { /* noop */ }
      }
    };
    if (scope === "conversations" || scope === "factory") {
      for (const t of ["conversations", "messages"] as const) del(t);
      this.memoryStore.conversations.clear();
      this.memoryStore.messages.clear();
      cleared.push("conversations", "messages");
    }
    if (scope === "execution" || scope === "factory") {
      for (const t of ["runs", "run_events", "tool_calls", "approvals", "automation_runs", "audit_logs", "computer_sessions", "files"] as const) del(t);
      this.memoryStore.runs.clear();
      this.memoryStore.runEvents.clear();
      this.memoryStore.toolCalls.clear();
      this.memoryStore.approvals.clear();
      this.memoryStore.automationRuns.clear();
      this.memoryStore.auditLogs.length = 0;
      this.memoryStore.computerSessions.clear();
      this.memoryStore.files.clear();
      cleared.push("runs", "run_events", "tool_calls", "approvals", "automation_runs", "files", "computer_sessions");
    }
    if (scope === "cache" || scope === "factory") {
      del("models");
      this.memoryStore.models.clear();
      cleared.push("models-cache");
    }
    if (scope === "factory") {
      for (const t of ["agents", "automations", "memories", "provider_configs", "sessions", "users", "app_settings"] as const) del(t);
      this.memoryStore.agents.clear();
      this.memoryStore.automations.clear();
      this.memoryStore.memories.clear();
      this.memoryStore.providerConfigs.clear();
      this.memoryStore.sessions.clear();
      this.memoryStore.users.clear();
      this.memoryStore.appSettings.clear();
      cleared.push("agents", "automations", "memories", "provider_configs", "sessions", "users", "settings");
      this.seedDefaults();
    }
    return { cleared };
  }

  health(): { persistent: boolean; path: string; counts: Record<string, number> } {
    return {
      persistent: this.persistEnabled,
      path: this.dbFilePath,
      counts: {
        users: this.memoryStore.users.size,
        agents: this.memoryStore.agents.size,
        conversations: this.memoryStore.conversations.size,
        messages: this.memoryStore.messages.size,
        providers: this.memoryStore.providerConfigs.size,
      },
    };
  }

  private seedDefaults() {
    const now = new Date().toISOString();
    // Only seed when no agents exist (never create fake history)
    if (this.memoryStore.agents.size > 0) return;
    const defaults: AgentRecord[] = [
      {
        id: "agent-default-1",
        name: "General Assistant",
        description: "General-purpose agent. Configure an AI provider to start chatting.",
        icon: "smart_toy",
        systemPrompt: "You are a helpful AI assistant.",
        primaryProvider: "openai_compatible",
        primaryModel: "",
        temperature: 0.7,
        maxSteps: 25,
        approvalPolicy: "require_approval",
        permissions: { computerUse: false, shell: false, filesystem: true, network: true, automation: false, subagents: false },
        createdAt: now,
        updatedAt: now,
      },
    ];
    for (const a of defaults) this.memoryStore.agents.set(a.id, a);
  }
}

export const db = new DatabaseManager();
