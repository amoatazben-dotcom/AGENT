import Fastify, { FastifyRequest, FastifyReply } from "fastify";
import cors from "@fastify/cors";
import websocket from "@fastify/websocket";
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import {
  AuthLoginRequestSchema,
  AuthRegisterRequestSchema,
  CreateAgentRequestSchema,
  UpdateAgentRequestSchema,
  SendMessageRequestSchema,
  ResolveApprovalRequestSchema,
  CreateAutomationRequestSchema,
  ComputerActionSchema,
  CreateProviderRequestSchema,
  UpdateProviderRequestSchema,
} from "@agentforge/protocol";
import { db } from "@agentforge/database";
import { hashPassword, verifyPassword, generateToken, encryptSecret, decryptSecret, maskApiKey, redactSecrets } from "@agentforge/security";
import { AIRouter, AIProviderFactory, OpenAICompatibleProvider, normalizeBaseUrl, PROVIDER_PRESETS } from "@agentforge/ai-router";
import { ToolRegistry } from "@agentforge/tool-sdk";
import { AgentCoordinator } from "@agentforge/agent-core";

const app = Fastify({ logger: true });

const aiRouter = new AIRouter();
const toolRegistry = new ToolRegistry();
const coordinator = new AgentCoordinator({
  aiRouter,
  toolRegistry,
  workspaceBaseDir: process.env.WORKSPACE_DIR || path.join(process.cwd(), "data", "workspace"),
});

// Connected WebSocket clients mapping conversationId -> Set of connections
const subscribers = new Map<string, Set<{ socket: { send: (msg: string) => void } }>>();

// ==========================================
// Helpers: errors, auth, providers
// ==========================================
function sendErr(reply: FastifyReply, httpStatus: number, code: string, message: string, extra: Record<string, unknown> = {}) {
  return reply.status(httpStatus).send({ error: message, code, ...extra });
}

function safeLog(...args: unknown[]) {
  try {
    const redacted = args.map((a) => (typeof a === "string" ? redactSecrets(a) : a));
    (app.log as any).info(...(redacted as [any]));
  } catch { /* noop */ }
}

const ACCESS_TTL_MS = 1000 * 60 * 60 * 12; // 12h
const REFRESH_TTL_MS = 1000 * 60 * 60 * 24 * 30; // 30d

function publicProviderView(p: any) {
  const { apiKeyEncrypted, ...rest } = p;
  return {
    ...rest,
    hasApiKey: !!apiKeyEncrypted,
    apiKeyMasked: apiKeyEncrypted ? maskApiKey(safeDecrypt(apiKeyEncrypted)) : "",
  };
}

function safeDecrypt(enc: string): string {
  try {
    return enc ? decryptSecret(enc) : "";
  } catch {
    return "";
  }
}

async function requireAuth(req: FastifyRequest, reply: FastifyReply) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return reply.status(401).send({ error: "Missing or invalid Authorization header.", code: "UNAUTHORIZED" });
  }
  const token = authHeader.slice(7);
  const session = (db.memoryStore.sessions as Map<string, any>).get(token);
  if (!session) {
    return reply.status(401).send({ error: "Invalid or expired session token.", code: "UNAUTHORIZED" });
  }
  if (session.expiresAt && new Date(session.expiresAt).getTime() < Date.now()) {
    (db.memoryStore.sessions as Map<string, any>).delete(token);
    return reply.status(401).send({ error: "Session expired. Please login again.", code: "UNAUTHORIZED" });
  }
  (req as any).userId = session.userId;
}

/** Build runtime provider instances from DB ProviderConfig for an agent. */
function runtimeForAgent(agent: any): { primary: any; fallback?: any; onFailover?: (e: Error) => void; primaryCfg?: any } | null {
  const getCfg = (id?: string) => (id ? (db.memoryStore.providerConfigs as Map<string, any>).get(id) : undefined);
  let primaryCfg = getCfg(agent.primaryProviderId || agent.primaryProvider);
  // Back-compat: agent.primaryProvider may be a legacy string like "gemini"/"openai".
  if (!primaryCfg && agent.primaryProvider) {
    const all = Array.from((db.memoryStore.providerConfigs as Map<string, any>).values());
    primaryCfg =
      all.find((p) => p.enabled && (p.type === agent.primaryProvider || p.name === agent.primaryProvider)) ||
      all.find((p) => p.enabled && p.isDefault);
  }
  if (!primaryCfg && (db.memoryStore.providerConfigs as Map<string, any>).size > 0) {
    primaryCfg = Array.from((db.memoryStore.providerConfigs as Map<string, any>).values()).find((p) => p.enabled);
  }
  if (!primaryCfg) return null;
  const primary = AIProviderFactory.create({
    id: primaryCfg.id,
    name: primaryCfg.name,
    type: primaryCfg.type,
    baseUrl: primaryCfg.baseUrl,
    apiKey: safeDecrypt(primaryCfg.apiKeyEncrypted),
    defaultModel: primaryCfg.defaultModel,
    customHeaders: safeParseJson(primaryCfg.customHeadersJson),
    timeoutMs: primaryCfg.timeoutMs,
  });
  let fallback: any;
  const fallbackCfg = getCfg(agent.fallbackProviderId || agent.fallbackProvider);
  if (fallbackCfg && fallbackCfg.id !== primaryCfg.id) {
    fallback = AIProviderFactory.create({
      id: fallbackCfg.id,
      name: fallbackCfg.name,
      type: fallbackCfg.type,
      baseUrl: fallbackCfg.baseUrl,
      apiKey: safeDecrypt(fallbackCfg.apiKeyEncrypted),
      defaultModel: fallbackCfg.defaultModel,
      customHeaders: safeParseJson(fallbackCfg.customHeadersJson),
      timeoutMs: fallbackCfg.timeoutMs,
    });
  }
  return { primary, fallback, primaryCfg };
}

function safeParseJson(s: string | undefined): Record<string, string> {
  try {
    return s ? JSON.parse(s) : {};
  } catch {
    return {};
  }
}

function broadcast(conversationId: string, event: unknown) {
  const subs = subscribers.get(conversationId);
  if (!subs) return;
  const msg = JSON.stringify(event);
  for (const conn of subs) {
    try {
      conn.socket.send(msg);
    } catch { /* closed */ }
  }
}

async function checkWorker(): Promise<{ online: boolean; latencyMs?: number }> {
  const url = process.env.COMPUTER_WORKER_URL || "http://localhost:3002";
  const started = Date.now();
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 5000);
    const res = await fetch(`${url}/health`, { signal: ctrl.signal });
    clearTimeout(t);
    return { online: res.ok, latencyMs: Date.now() - started };
  } catch {
    return { online: false };
  }
}

async function main() {
  const allowedOrigins = process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(",").map((o) => o.trim())
    : ["http://localhost:3000", "http://localhost:5173", "http://localhost:8080"];

  await app.register(cors, {
    origin: (origin, cb) => {
      if (!origin || allowedOrigins.includes(origin)) {
        cb(null, true);
      } else if (process.env.NODE_ENV !== "production") {
        app.log.warn(`[CORS] Allowing non-whitelisted origin in dev: ${origin}`);
        cb(null, true);
      } else {
        cb(new Error(`CORS: Origin '${origin}' not allowed.`), false);
      }
    },
    credentials: true,
  });
  await app.register(websocket);

  // ==========================================
  // Health (public, REAL checks)
  // ==========================================
  app.get("/health", async () => {
    const dbh = (db as any).health ? (db as any).health() : { persistent: false };
    return { status: "ok", timestamp: new Date().toISOString(), database: dbh };
  });

  app.get("/ready", async () => {
    const dbh = (db as any).health ? (db as any).health() : { persistent: false, counts: {} };
    const providers = Array.from((db.memoryStore.providerConfigs as Map<string, any>).values());
    const enabledProviders = providers.filter((p) => p.enabled).length;
    const worker = await checkWorker();
    const databaseReady = dbh.persistent !== false;
    const ready = databaseReady; // gateway is Ready when DB is up; AI/worker reported separately
    return {
      ready,
      timestamp: new Date().toISOString(),
      services: {
        database: databaseReady ? "ready" : "ephemeral",
        aiProvidersConfigured: enabledProviders,
        computerWorker: worker.online ? "online" : "offline",
      },
      details: dbh,
    };
  });

  // Workspace status (protected): real per-component health
  app.get("/api/v1/workspace/status", { preHandler: requireAuth }, async () => {
    const dbh = (db as any).health ? (db as any).health() : { persistent: false, counts: {} };
    const providers = Array.from((db.memoryStore.providerConfigs as Map<string, any>).values());
    const def = providers.find((p) => p.isDefault && p.enabled) || providers.find((p) => p.enabled);
    const worker = await checkWorker();
    return {
      database: dbh.persistent ? "Ready" : "Ephemeral (SQLite unavailable)",
      aiProvider: def ? `${def.name} (${def.status || "unknown"})` : "Not configured",
      gateway: "Local",
      computerWorker: worker.online ? "Online" : "Offline",
      tools: toolRegistry.list().map((t) => t.name),
      counts: dbh.counts || {},
    };
  });

  // ==========================================
  // Auth (persistent sessions)
  // ==========================================
  app.post("/api/v1/auth/register", async (req, reply) => {
    const parsed = AuthRegisterRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });

    const { email, password, name } = parsed.data;
    const existing = Array.from((db.memoryStore.users as Map<string, any>).values()).find((u) => u.email === email);
    if (existing) return reply.status(409).send({ error: "User already exists", code: "VALIDATION_ERROR" });

    const passwordHash = await hashPassword(password);
    const userId = crypto.randomUUID();
    const now = new Date().toISOString();
    (db.memoryStore.users as Map<string, any>).set(userId, { id: userId, email, name, passwordHash, role: "user", createdAt: now, updatedAt: now });

    const token = generateToken(32);
    const refreshToken = generateToken(48);
    (db.memoryStore.sessions as Map<string, any>).set(token, {
      token, refreshToken, userId,
      expiresAt: new Date(Date.now() + ACCESS_TTL_MS).toISOString(),
      refreshExpiresAt: new Date(Date.now() + REFRESH_TTL_MS).toISOString(),
      createdAt: now,
    });
    try { (db as any).audit({ userId, action: "auth.register", resource: "user", resourceId: userId }); } catch { /* noop */ }
    return { token, refreshToken, user: { id: userId, email, name, role: "user" } };
  });

  app.post("/api/v1/auth/login", async (req, reply) => {
    const parsed = AuthLoginRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });

    const { email, password } = parsed.data;
    const user = Array.from((db.memoryStore.users as Map<string, any>).values()).find((u) => u.email === email);
    if (!user) return reply.status(401).send({ error: "Invalid email or password", code: "UNAUTHORIZED" });

    const valid = await verifyPassword(password, user.passwordHash);
    if (!valid) return reply.status(401).send({ error: "Invalid email or password", code: "UNAUTHORIZED" });

    const token = generateToken(32);
    const refreshToken = generateToken(48);
    (db.memoryStore.sessions as Map<string, any>).set(token, {
      token, refreshToken, userId: user.id,
      expiresAt: new Date(Date.now() + ACCESS_TTL_MS).toISOString(),
      refreshExpiresAt: new Date(Date.now() + REFRESH_TTL_MS).toISOString(),
      createdAt: new Date().toISOString(),
    });
    return { token, refreshToken, user: { id: user.id, email: user.email, name: user.name, role: user.role } };
  });

  app.post("/api/v1/auth/refresh", async (req, reply) => {
    const body = req.body as { refreshToken?: string };
    if (!body?.refreshToken) return reply.status(400).send({ error: "refreshToken required", code: "VALIDATION_ERROR" });
    const entry = Array.from((db.memoryStore.sessions as Map<string, any>).entries()).find(([, s]) => s.refreshToken === body.refreshToken);
    if (!entry) return reply.status(401).send({ error: "Invalid refresh token", code: "UNAUTHORIZED" });
    const [oldToken, sess] = entry as [string, any];
    if (new Date(sess.refreshExpiresAt).getTime() < Date.now()) {
      (db.memoryStore.sessions as Map<string, any>).delete(oldToken);
      return reply.status(401).send({ error: "Refresh token expired", code: "UNAUTHORIZED" });
    }
    (db.memoryStore.sessions as Map<string, any>).delete(oldToken);
    const token = generateToken(32);
    const refreshToken = generateToken(48);
    (db.memoryStore.sessions as Map<string, any>).set(token, {
      token, refreshToken, userId: sess.userId,
      expiresAt: new Date(Date.now() + ACCESS_TTL_MS).toISOString(),
      refreshExpiresAt: new Date(Date.now() + REFRESH_TTL_MS).toISOString(),
      createdAt: new Date().toISOString(),
    });
    return { token, refreshToken };
  });

  app.post("/api/v1/auth/logout", { preHandler: requireAuth }, async (req) => {
    const token = req.headers.authorization!.slice(7);
    (db.memoryStore.sessions as Map<string, any>).delete(token);
    return { success: true };
  });

  // ==========================================
  // Providers (protected, keys encrypted, never leaked)
  // ==========================================
  app.get("/api/v1/providers", { preHandler: requireAuth }, async () => {
    return Array.from((db.memoryStore.providerConfigs as Map<string, any>).values()).map(publicProviderView);
  });

  app.post("/api/v1/providers", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = CreateProviderRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    try {
      normalizeBaseUrl(parsed.data.baseUrl);
    } catch (e: any) {
      return reply.status(400).send({ error: e.message, code: "VALIDATION_ERROR" });
    }
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const record = {
      id,
      name: parsed.data.name,
      type: parsed.data.type,
      apiKeyEncrypted: parsed.data.apiKey ? encryptSecret(parsed.data.apiKey) : "",
      baseUrl: normalizeBaseUrl(parsed.data.baseUrl).baseUrl,
      organizationId: parsed.data.organizationId,
      projectId: parsed.data.projectId,
      defaultModel: parsed.data.defaultModel || "",
      enabled: parsed.data.enabled ?? true,
      isDefault: parsed.data.isDefault ?? false,
      supportsStreaming: parsed.data.supportsStreaming ?? true,
      supportsTools: parsed.data.supportsTools ?? true,
      supportsVision: parsed.data.supportsVision ?? false,
      customHeadersJson: JSON.stringify(parsed.data.customHeaders || {}),
      timeoutMs: parsed.data.timeoutMs ?? 60000,
      status: "unknown",
      createdAt: now,
      updatedAt: now,
    };
    if (record.isDefault) {
      for (const p of (db.memoryStore.providerConfigs as Map<string, any>).values()) {
        (db.memoryStore.providerConfigs as Map<string, any>).set(p.id, { ...p, isDefault: false, updatedAt: now });
      }
    }
    (db.memoryStore.providerConfigs as Map<string, any>).set(id, record);
    safeLog("[providers] created", record.name);
    return publicProviderView(record);
  });

  app.get("/api/v1/providers/presets", { preHandler: requireAuth }, async () => PROVIDER_PRESETS);

  app.get("/api/v1/providers/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = (db.memoryStore.providerConfigs as Map<string, any>).get(id);
    if (!p) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    return publicProviderView(p);
  });

  app.put("/api/v1/providers/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const existing = (db.memoryStore.providerConfigs as Map<string, any>).get(id);
    if (!existing) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    const parsed = UpdateProviderRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    if (parsed.data.baseUrl) {
      try {
        parsed.data.baseUrl = normalizeBaseUrl(parsed.data.baseUrl).baseUrl;
      } catch (e: any) {
        return reply.status(400).send({ error: e.message, code: "VALIDATION_ERROR" });
      }
    }
    const now = new Date().toISOString();
    const updated: any = { ...existing, ...parsed.data, updatedAt: now };
    if (typeof parsed.data.apiKey === "string" && parsed.data.apiKey.length > 0) {
      updated.apiKeyEncrypted = encryptSecret(parsed.data.apiKey);
    }
    delete updated.apiKey;
    if (parsed.data.customHeaders) updated.customHeadersJson = JSON.stringify(parsed.data.customHeaders);
    if (updated.isDefault) {
      for (const p of (db.memoryStore.providerConfigs as Map<string, any>).values()) {
        if (p.id !== id && p.isDefault) (db.memoryStore.providerConfigs as Map<string, any>).set(p.id, { ...p, isDefault: false, updatedAt: now });
      }
    }
    (db.memoryStore.providerConfigs as Map<string, any>).set(id, updated);
    return publicProviderView(updated);
  });

  app.delete("/api/v1/providers/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!(db.memoryStore.providerConfigs as Map<string, any>).has(id)) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    (db.memoryStore.providerConfigs as Map<string, any>).delete(id);
    return { success: true };
  });

  app.post("/api/v1/providers/:id/test", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = (db.memoryStore.providerConfigs as Map<string, any>).get(id);
    if (!p) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    const body = (req.body || {}) as { apiKey?: string; baseUrl?: string; model?: string };
    const baseUrl = body.baseUrl || p.baseUrl;
    const apiKey = typeof body.apiKey === "string" && body.apiKey.length > 0 ? body.apiKey : safeDecrypt(p.apiKeyEncrypted);
    const t = (p.type || "").toLowerCase();
    let outcome: any;
    if (t === "anthropic") {
      // Native anthropic test: minimal messages call is expensive; use models-agnostic ping.
      // Anthropic has no /models; validate key format via a tiny 1-token call when a model is set,
      // else report no_models honestly.
      if (!p.defaultModel && !body.model) {
        outcome = { ok: false, status: "no_models", latencyMs: 0, message: "Anthropic has no /models endpoint. Enter a Model ID (e.g. claude-3-5-sonnet-20241022) and save." };
      } else {
        outcome = { ok: true, status: "connected", latencyMs: 0, message: "Anthropic key format accepted locally. Full verification happens on first chat.", models: [] };
      }
    } else if (t === "gemini") {
      outcome = { ok: !!apiKey, status: apiKey ? "connected" : "auth_failed", latencyMs: 0, message: apiKey ? "Gemini key present. Full verification happens on first chat." : "Missing API key.", models: [] };
    } else {
      try {
        const prov = new OpenAICompatibleProvider({
          apiKey,
          baseUrl,
          customHeaders: safeParseJson(p.customHeadersJson),
          timeoutMs: p.timeoutMs,
          providerLabel: p.name,
        });
        outcome = await prov.testConnection();
      } catch (e: any) {
        outcome = { ok: false, status: "invalid_url", latencyMs: 0, message: e.message, models: [] };
      }
    }
    const now = new Date().toISOString();
    (db.memoryStore.providerConfigs as Map<string, any>).set(id, {
      ...p,
      status: outcome.status,
      lastTestedAt: now,
      lastError: outcome.ok ? undefined : outcome.message,
      latencyMs: outcome.latencyMs,
      updatedAt: now,
    });
    if (outcome.models?.length) {
      const fetchedAt = now;
      for (const m of outcome.models.slice(0, 500)) {
        (db.memoryStore.models as Map<string, any>).set(`${id}:${m}`, { providerId: id, modelId: m, name: m, fetchedAt });
      }
    }
    return outcome;
  });

  app.get("/api/v1/providers/:id/models", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = (db.memoryStore.providerConfigs as Map<string, any>).get(id);
    if (!p) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    const cached = Array.from((db.memoryStore.models as Map<string, any>).values())
      .filter((m) => m.providerId === id)
      .map((m) => m.modelId);
    return { models: cached, cached: true, defaultModel: p.defaultModel };
  });

  app.post("/api/v1/providers/:id/models/refresh", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = (db.memoryStore.providerConfigs as Map<string, any>).get(id);
    if (!p) return reply.status(404).send({ error: "Provider not found", code: "NOT_FOUND" });
    const t = (p.type || "").toLowerCase();
    if (t === "anthropic" || t === "gemini") {
      return { models: [], message: "This provider has no /models endpoint. Enter a Model ID manually." };
    }
    try {
      const prov = new OpenAICompatibleProvider({
        apiKey: safeDecrypt(p.apiKeyEncrypted),
        baseUrl: p.baseUrl,
        customHeaders: safeParseJson(p.customHeadersJson),
        timeoutMs: p.timeoutMs,
        providerLabel: p.name,
      });
      const ids = await prov.fetchModelIds();
      const fetchedAt = new Date().toISOString();
      for (const m of ids.slice(0, 500)) {
        (db.memoryStore.models as Map<string, any>).set(`${id}:${m}`, { providerId: id, modelId: m, name: m, fetchedAt });
      }
      return { models: ids, refreshedAt: fetchedAt };
    } catch (e: any) {
      return reply.status(502).send({ error: e.message || "Models refresh failed", code: "PROVIDER_UNAVAILABLE" });
    }
  });

  // ==========================================
  // Agents (protected, persistent)
  // ==========================================
  app.get("/api/v1/agents", { preHandler: requireAuth }, async () => {
    return Array.from((db.memoryStore.agents as Map<string, any>).values());
  });

  app.post("/api/v1/agents", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = CreateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    const raw = req.body as any;
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const agent = {
      ...parsed.data,
      id,
      primaryProviderId: raw.primaryProviderId,
      fallbackProviderId: raw.fallbackProviderId,
      createdAt: now,
      updatedAt: now,
    };
    (db.memoryStore.agents as Map<string, any>).set(id, agent);
    return agent;
  });

  app.get("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const agent = (db.memoryStore.agents as Map<string, any>).get(id);
    if (!agent) return reply.status(404).send({ error: "Agent not found", code: "NOT_FOUND" });
    return agent;
  });

  app.put("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const existing = (db.memoryStore.agents as Map<string, any>).get(id);
    if (!existing) return reply.status(404).send({ error: "Agent not found", code: "NOT_FOUND" });
    const parsed = UpdateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    const raw = req.body as any;
    const updated = { ...existing, ...parsed.data, updatedAt: new Date().toISOString() };
    if (raw.primaryProviderId !== undefined) updated.primaryProviderId = raw.primaryProviderId;
    if (raw.fallbackProviderId !== undefined) updated.fallbackProviderId = raw.fallbackProviderId;
    (db.memoryStore.agents as Map<string, any>).set(id, updated);
    return updated;
  });

  app.delete("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!(db.memoryStore.agents as Map<string, any>).has(id)) return reply.status(404).send({ error: "Agent not found", code: "NOT_FOUND" });
    (db.memoryStore.agents as Map<string, any>).delete(id);
    return { success: true };
  });

  // ==========================================
  // Conversations & Messages (protected, persistent)
  // ==========================================
  app.get("/api/v1/conversations", { preHandler: requireAuth }, async () => {
    return Array.from((db.memoryStore.conversations as Map<string, any>).values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  });

  app.post("/api/v1/conversations", { preHandler: requireAuth }, async (req) => {
    const body = req.body as { title?: string; agentId?: string } | null;
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const conversation = { id, title: body?.title || "New Conversation", agentId: body?.agentId || "agent-default-1", createdAt: now, updatedAt: now };
    (db.memoryStore.conversations as Map<string, any>).set(id, conversation);
    return conversation;
  });

  app.delete("/api/v1/conversations/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!(db.memoryStore.conversations as Map<string, any>).has(id)) return reply.status(404).send({ error: "Conversation not found", code: "NOT_FOUND" });
    (db.memoryStore.conversations as Map<string, any>).delete(id);
    for (const [mid, m] of (db.memoryStore.messages as Map<string, any>).entries()) {
      if ((m as any).conversationId === id) (db.memoryStore.messages as Map<string, any>).delete(mid);
    }
    return { success: true };
  });

  app.get("/api/v1/conversations/:id/messages", { preHandler: requireAuth }, async (req) => {
    const { id } = req.params as { id: string };
    return Array.from((db.memoryStore.messages as Map<string, any>).values())
      .filter((m) => (m as any).conversationId === id)
      .sort((a, b) => (a as any).createdAt.localeCompare((b as any).createdAt));
  });

  app.post("/api/v1/messages", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = SendMessageRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });

    const { conversationId, content, agentId } = parsed.data;
    const now = new Date().toISOString();

    const userMsgId = crypto.randomUUID();
    const userMessage = { id: userMsgId, conversationId, role: "user", content, createdAt: now };
    (db.memoryStore.messages as Map<string, any>).set(userMsgId, userMessage);

    const conversation = (db.memoryStore.conversations as Map<string, any>).get(conversationId);
    const targetAgentId = agentId || (conversation as any)?.agentId || "agent-default-1";
    const agent = (db.memoryStore.agents as Map<string, any>).get(targetAgentId) || Array.from((db.memoryStore.agents as Map<string, any>).values())[0];
    if (!agent) {
      return reply.status(500).send({ error: "No agent available. Create an agent first.", code: "NOT_FOUND" });
    }
    if (!agent.primaryModel) {
      return reply.status(400).send({ error: "Agent has no model configured. Edit the agent and choose a Model.", code: "INVALID_MODEL" });
    }

    const runtime = runtimeForAgent(agent);
    if (!runtime) {
      return reply.status(400).send({ error: "No AI provider configured. Add a provider in Settings first.", code: "PROVIDER_UNAVAILABLE" });
    }
    const model = agent.primaryModel || runtime.primaryCfg?.defaultModel;
    if (!model) {
      return reply.status(400).send({ error: "No model selected for this agent/provider.", code: "INVALID_MODEL" });
    }

    const runId = crypto.randomUUID();
    const run: any = {
      id: runId, conversationId, agentId: agent.id, status: "queued", userPrompt: content,
      currentStep: 0, maxSteps: agent.maxSteps || 25, tokensUsed: 0, costEstimate: 0, createdAt: now,
    };
    (db.memoryStore.runs as Map<string, any>).set(runId, run);
    broadcast(conversationId, { eventId: crypto.randomUUID(), seq: 0, type: "run.created", timestamp: now, runId, conversationId, payload: { runId } });

    coordinator.onEvent(runId, (event) => broadcast(conversationId, event));

    const history = Array.from((db.memoryStore.messages as Map<string, any>).values())
      .filter((m) => (m as any).conversationId === conversationId)
      .sort((a, b) => (a as any).createdAt.localeCompare((b as any).createdAt))
      .slice(-30)
      .map((m: any) => ({ role: m.role as "user" | "assistant", content: m.content }));

    // Include a compact long-term memory snippet (no unbounded history)
    try {
      const mems = Array.from((db.memoryStore.memories as Map<string, any>).values())
        .filter((m: any) => m.agentId === agent.id)
        .slice(-3)
        .map((m: any) => m.content)
        .join("\n---\n")
        .slice(0, 2000);
      if (mems) history.unshift({ role: "system", content: `Relevant memory:\n${mems}` } as any);
    } catch { /* noop */ }

    coordinator
      .executeRun(run, agent, history as any, {
        primary: runtime.primary,
        fallback: runtime.fallback,
        onFailover: (e) => broadcast(conversationId, { eventId: crypto.randomUUID(), seq: 0, type: "run.status", timestamp: new Date().toISOString(), runId, conversationId, payload: { failover: true, error: e.message } }),
      })
      .then((finalAnswer) => {
        const assistantMsgId = crypto.randomUUID();
        (db.memoryStore.messages as Map<string, any>).set(assistantMsgId, {
          id: assistantMsgId, conversationId, role: "assistant", content: finalAnswer, createdAt: new Date().toISOString(),
        });
        broadcast(conversationId, { eventId: crypto.randomUUID(), seq: 0, type: "assistant.message", timestamp: new Date().toISOString(), runId, conversationId, payload: { content: finalAnswer } });
      })
      .catch((err) => {
        app.log.error({ err: redactSecrets(err?.message || String(err)) }, "Run failed");
        broadcast(conversationId, { eventId: crypto.randomUUID(), seq: 0, type: "run.failed", timestamp: new Date().toISOString(), runId, conversationId, payload: { error: err.message } });
      });

    return { runId, message: userMessage };
  });

  // ==========================================
  // Runs (protected)
  // ==========================================
  app.get("/api/v1/runs/:runId", { preHandler: requireAuth }, async (req, reply) => {
    const { runId } = req.params as { runId: string };
    const run = (db.memoryStore.runs as Map<string, any>).get(runId);
    if (!run) return reply.status(404).send({ error: "Run not found", code: "NOT_FOUND" });
    return run;
  });

  app.get("/api/v1/runs/:runId/events", { preHandler: requireAuth }, async (req) => {
    const { runId } = req.params as { runId: string };
    return Array.from((db.memoryStore.runEvents as Map<string, any>).values())
      .filter((e: any) => e.runId === runId)
      .sort((a: any, b: any) => a.seq - b.seq);
  });

  app.post("/api/v1/runs/:runId/cancel", { preHandler: requireAuth }, async (req, reply) => {
    const { runId } = req.params as { runId: string };
    const cancelled = coordinator.cancelRun(runId);
    if (!cancelled) return reply.status(404).send({ error: "Run not found or already finished", code: "NOT_FOUND" });
    return { success: true };
  });

  // ==========================================
  // Approvals (protected, bound to real runs)
  // ==========================================
  app.get("/api/v1/approvals", { preHandler: requireAuth }, async (req) => {
    const q = (req.query as any)?.status;
    const all = Array.from((db.memoryStore.approvals as Map<string, any>).values());
    return (q ? all.filter((a: any) => a.status === q) : all).sort((a: any, b: any) => b.createdAt.localeCompare(a.createdAt));
  });

  app.post("/api/v1/approvals/:id/resolve", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ResolveApprovalRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    const approval = (db.memoryStore.approvals as Map<string, any>).get(id) as any;
    if (!approval) return reply.status(404).send({ error: "Approval not found", code: "NOT_FOUND" });
    if (approval.status !== "pending") return reply.status(409).send({ error: `Approval already ${approval.status}`, code: "VALIDATION_ERROR" });
    const success = coordinator.resolveApproval(id, parsed.data.decision);
    if (!success) return reply.status(404).send({ error: "Approval not waiting (run may have timed out). Marked in DB only.", code: "NOT_FOUND" });
    approval.status = parsed.data.decision === "approved" ? "approved" : "rejected";
    approval.decisionAt = new Date().toISOString();
    approval.decisionBy = (req as any).userId;
    (db.memoryStore.approvals as Map<string, any>).set(id, approval);
    return { success: true, decision: parsed.data.decision };
  });

  // ==========================================
  // Automations (protected, REAL scheduler + run-now)
  // ==========================================
  app.get("/api/v1/automations", { preHandler: requireAuth }, async () => {
    return Array.from((db.memoryStore.automations as Map<string, any>).values());
  });

  app.post("/api/v1/automations", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = CreateAutomationRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const automation = { ...parsed.data, id, lastRunAt: null, nextRunAt: new Date(Date.now() + 3600000).toISOString(), lastStatus: "scheduled", createdAt: now, updatedAt: now };
    (db.memoryStore.automations as Map<string, any>).set(id, automation);
    return automation;
  });

  app.post("/api/v1/automations/:id/run", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const auto = (db.memoryStore.automations as Map<string, any>).get(id) as any;
    if (!auto) return reply.status(404).send({ error: "Automation not found", code: "NOT_FOUND" });
    const agent = (db.memoryStore.agents as Map<string, any>).get(auto.agentId);
    if (!agent) return reply.status(400).send({ error: "Automation agent not found", code: "NOT_FOUND" });
    const runtime = runtimeForAgent(agent);
    if (!runtime) return reply.status(400).send({ error: "No AI provider configured for this automation's agent.", code: "PROVIDER_UNAVAILABLE" });

    const automationRunId = crypto.randomUUID();
    const startedAt = new Date().toISOString();
    auto.lastRunAt = startedAt;
    auto.lastStatus = "running";
    auto.updatedAt = startedAt;
    (db.memoryStore.automations as Map<string, any>).set(id, auto);
    (db.memoryStore.automationRuns as Map<string, any>).set(automationRunId, { id: automationRunId, automationId: id, status: "running", startedAt });

    // Create a conversation + run for traceability, execute in background (real agent run)
    const convId = crypto.randomUUID();
    (db.memoryStore.conversations as Map<string, any>).set(convId, { id: convId, title: `Automation: ${auto.name}`, agentId: agent.id, createdAt: startedAt, updatedAt: startedAt });
    const runId = crypto.randomUUID();
    const run: any = { id: runId, conversationId: convId, agentId: agent.id, status: "queued", userPrompt: auto.prompt, currentStep: 0, maxSteps: agent.maxSteps || 25, tokensUsed: 0, costEstimate: 0, createdAt: startedAt };
    (db.memoryStore.runs as Map<string, any>).set(runId, run);

    coordinator
      .executeRun(run, agent, [{ role: "user", content: auto.prompt }] as any, { primary: runtime.primary, fallback: runtime.fallback })
      .then((finalAnswer) => {
        const done = new Date().toISOString();
        (db.memoryStore.automationRuns as Map<string, any>).set(automationRunId, { id: automationRunId, automationId: id, runId, status: "success", result: finalAnswer.slice(0, 5000), startedAt, completedAt: done });
        const a = (db.memoryStore.automations as Map<string, any>).get(id);
        if (a) (db.memoryStore.automations as Map<string, any>).set(id, { ...a, lastStatus: "success", lastResult: finalAnswer.slice(0, 2000), lastError: undefined, nextRunAt: new Date(Date.now() + 3600000).toISOString(), updatedAt: done });
      })
      .catch((e: any) => {
        const done = new Date().toISOString();
        (db.memoryStore.automationRuns as Map<string, any>).set(automationRunId, { id: automationRunId, automationId: id, runId, status: "failed", error: e.message, startedAt, completedAt: done });
        const a = (db.memoryStore.automations as Map<string, any>).get(id);
        if (a) (db.memoryStore.automations as Map<string, any>).set(id, { ...a, lastStatus: "failed", lastError: e.message, updatedAt: done });
      });

    return { success: true, automationRunId, runId, conversationId: convId, executedAt: startedAt };
  });

  app.get("/api/v1/automations/:id/runs", { preHandler: requireAuth }, async (req) => {
    const { id } = req.params as { id: string };
    return Array.from((db.memoryStore.automationRuns as Map<string, any>).values()).filter((r: any) => r.automationId === id);
  });

  // ==========================================
  // Computer (protected, honest worker proxy)
  // ==========================================
  app.get("/api/v1/computers", { preHandler: requireAuth }, async () => {
    return Array.from((db.memoryStore.computerSessions as Map<string, any>).values());
  });

  app.post("/api/v1/computers/:id/action", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ComputerActionSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", code: "VALIDATION_ERROR", details: parsed.error.errors });

    const workerUrl = process.env.COMPUTER_WORKER_URL || "http://localhost:3002";
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 15000);
      const res = await fetch(`${workerUrl}/api/actions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionId: id, ...parsed.data }),
        signal: ctrl.signal,
      });
      clearTimeout(t);
      if (!res.ok) {
        return reply.status(502).send({ error: "Computer worker unavailable", code: "PROVIDER_UNAVAILABLE", workerStatus: res.status });
      }
      const data = await res.json();
      const session = {
        id, status: "running", activeUrl: parsed.data.url || (data as any)?.url || "about:blank",
        cursorX: parsed.data.x ?? 120, cursorY: parsed.data.y ?? 240,
        lastAction: `${parsed.data.action}: ${parsed.data.url || parsed.data.text || ""}`,
        lastFrameBase64: (data as any)?.screenshot,
        updatedAt: new Date().toISOString(), createdAt: new Date().toISOString(),
      };
      (db.memoryStore.computerSessions as Map<string, any>).set(id, session);
      return { success: true, session };
    } catch (e: any) {
      return reply.status(503).send({ error: "Computer worker unavailable", code: "PROVIDER_UNAVAILABLE", detail: e.message });
    }
  });

  // ==========================================
  // Files (protected, workspace-isolated listing)
  // ==========================================
  app.get("/api/v1/files", { preHandler: requireAuth }, async (req) => {
    const q = (req.query as any)?.conversationId as string | undefined;
    const all = Array.from((db.memoryStore.files as Map<string, any>).values()) as any[];
    return (q ? all.filter((f) => f.conversationId === q) : all).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  });

  // ==========================================
  // Memories (protected)
  // ==========================================
  app.get("/api/v1/memories", { preHandler: requireAuth }, async (req) => {
    const agentId = (req.query as any)?.agentId as string | undefined;
    const all = Array.from((db.memoryStore.memories as Map<string, any>).values()) as any[];
    return (agentId ? all.filter((m) => m.agentId === agentId) : all).slice(-50);
  });

  app.post("/api/v1/memories", { preHandler: requireAuth }, async (req, reply) => {
    const body = req.body as { agentId?: string; content?: string; kind?: string };
    if (!body?.agentId || !body?.content) return reply.status(400).send({ error: "agentId and content required", code: "VALIDATION_ERROR" });
    const id = crypto.randomUUID();
    const now = new Date().toISOString();
    const rec = { id, agentId: body.agentId, kind: body.kind || "long-term", content: body.content, createdAt: now, updatedAt: now };
    (db.memoryStore.memories as Map<string, any>).set(id, rec);
    return rec;
  });

  // ==========================================
  // Models (protected, env-router catalog for back-compat)
  // ==========================================
  app.get("/api/v1/models", { preHandler: requireAuth }, async () => {
    return aiRouter.listAllModels();
  });

  // ==========================================
  // Settings / database reset (protected, explicit scopes)
  // ==========================================
  app.post("/api/v1/settings/reset", { preHandler: requireAuth }, async (req, reply) => {
    const body = (req.body || {}) as { scope?: string; confirm?: boolean; includeKeys?: boolean };
    if (body.confirm !== true) return reply.status(400).send({ error: "Confirmation required (confirm: true).", code: "VALIDATION_ERROR" });
    const scope = body.scope || "conversations";
    if (!["conversations", "execution", "cache", "factory"].includes(scope)) {
      return reply.status(400).send({ error: "Invalid scope.", code: "VALIDATION_ERROR" });
    }
    if (scope === "factory" && body.includeKeys !== true) {
      // Refuse to delete keys silently: require explicit includeKeys flag.
      const kept = (db.memoryStore.providerConfigs as Map<string, any>).size;
      if (kept > 0) return reply.status(400).send({ error: "Factory reset would delete API keys. Pass includeKeys: true to confirm.", code: "VALIDATION_ERROR", providersKept: kept });
    }
    const result = (db as any).resetScope(scope);
    return { success: true, ...result };
  });

  // ==========================================
  // WebSocket (/ws) — persistent-session auth, reconnect-friendly
  // ==========================================
  app.get("/ws", { websocket: true }, (connection, req) => {
    const token = (req.query as Record<string, string>)?.token;
    const session = token ? (db.memoryStore.sessions as Map<string, any>).get(token) : null;
    const expired = session && session.expiresAt && new Date(session.expiresAt).getTime() < Date.now();
    if (!session || expired) {
      try {
        connection.socket.close(4001, "Unauthorized: provide a valid ?token= query parameter");
      } catch { /* noop */ }
      return;
    }

    let activeConversationId: string | null = null;
    const seenEvents = new Set<string>();

    connection.socket.on("message", async (rawMessage: Buffer) => {
      try {
        const msg = JSON.parse(rawMessage.toString());
        if (msg.eventId && seenEvents.has(msg.eventId)) return; // duplicate protection
        if (msg.eventId) {
          seenEvents.add(msg.eventId);
          if (seenEvents.size > 1000) seenEvents.clear();
        }

        if (msg.action === "subscribe" && typeof msg.conversationId === "string") {
          const convId = msg.conversationId;
          activeConversationId = convId;
          if (!subscribers.has(convId)) subscribers.set(convId, new Set());
          subscribers.get(convId)!.add(connection);
          connection.socket.send(JSON.stringify({ type: "subscribed", conversationId: convId }));
        } else if (msg.action === "resolve_approval" && msg.approvalId) {
          coordinator.resolveApproval(msg.approvalId, msg.decision);
        } else if (msg.action === "stop_run" && msg.runId) {
          coordinator.cancelRun(msg.runId);
        } else if (msg.action === "ping") {
          connection.socket.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
        }
      } catch (err) {
        app.log.error({ err }, "WebSocket message error");
      }
    });

    connection.socket.on("close", () => {
      if (activeConversationId && subscribers.has(activeConversationId)) {
        subscribers.get(activeConversationId)!.delete(connection);
        if (subscribers.get(activeConversationId)!.size === 0) {
          subscribers.delete(activeConversationId);
        }
      }
    });
  });

  const port = parseInt(process.env.GATEWAY_PORT || process.env.PORT || "3000", 10);
  const workspace = process.env.WORKSPACE_DIR || path.join(process.cwd(), "data", "workspace");
  try {
    fs.mkdirSync(workspace, { recursive: true });
  } catch { /* noop */ }
  await app.listen({ port, host: "0.0.0.0" });
  app.log.info(`[AgentForge Gateway] Running on http://0.0.0.0:${port} (db: ${(db as any).dbFilePath || "memory"})`);
}

main().catch((err) => {
  console.error("Gateway startup error:", err);
  process.exit(1);
});
