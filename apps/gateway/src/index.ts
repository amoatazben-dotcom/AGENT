import Fastify, { FastifyRequest, FastifyReply } from "fastify";
import cors from "@fastify/cors";
import websocket from "@fastify/websocket";
import * as crypto from "crypto";
import {
  AuthLoginRequestSchema,
  AuthRegisterRequestSchema,
  CreateAgentRequestSchema,
  UpdateAgentRequestSchema,
  SendMessageRequestSchema,
  ResolveApprovalRequestSchema,
  CreateAutomationRequestSchema,
  ComputerActionSchema,
} from "@agentforge/protocol";
import { db } from "@agentforge/database";
import { hashPassword, verifyPassword, generateToken } from "@agentforge/security";
import { AIRouter } from "@agentforge/ai-router";
import { ToolRegistry } from "@agentforge/tool-sdk";
import { AgentCoordinator } from "@agentforge/agent-core";

const app = Fastify({ logger: true });

const aiRouter = new AIRouter();
const toolRegistry = new ToolRegistry();
const coordinator = new AgentCoordinator({
  aiRouter,
  toolRegistry,
  workspaceBaseDir: "/tmp/agentforge-workspace",
});

// Connected WebSocket clients mapping conversationId -> Set of connections
const subscribers = new Map<string, Set<{ socket: { send: (msg: string) => void } }>>();

// ==========================================
// In-memory session store (token -> userId)
// ==========================================
const sessionStore = new Map<string, string>(); // token -> userId

// ==========================================
// Auth Middleware
// ==========================================
async function requireAuth(req: FastifyRequest, reply: FastifyReply) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return reply.status(401).send({ error: "Missing or invalid Authorization header." });
  }
  const token = authHeader.slice(7);
  const userId = sessionStore.get(token);
  if (!userId) {
    return reply.status(401).send({ error: "Invalid or expired session token." });
  }
  // Attach userId to request for downstream handlers
  (req as any).userId = userId;
}

async function main() {
  // Determine allowed CORS origins from env (comma-separated list)
  const allowedOrigins = process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(",").map((o) => o.trim())
    : ["http://localhost:3000", "http://localhost:5173", "http://localhost:8080"];

  await app.register(cors, {
    origin: (origin, cb) => {
      // Allow requests with no origin (curl, mobile, etc.) or from the allowed list
      if (!origin || allowedOrigins.includes(origin)) {
        cb(null, true);
      } else if (process.env.NODE_ENV !== "production") {
        // In development, allow all origins but warn
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
  // Health Checks (public)
  // ==========================================
  app.get("/health", async () => ({ status: "ok", timestamp: new Date().toISOString() }));
  app.get("/ready", async () => ({ ready: true, services: ["ai-router", "database", "coordinator"] }));

  // ==========================================
  // Auth Routes (public — no middleware)
  // ==========================================
  app.post("/api/v1/auth/register", async (req, reply) => {
    const parsed = AuthRegisterRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const { email, password, name } = parsed.data;
    const existing = Array.from(db.memoryStore.users.values()).find((u) => u.email === email);
    if (existing) return reply.status(409).send({ error: "User already exists" });

    const passwordHash = await hashPassword(password);
    const userId = crypto.randomUUID();
    const user = {
      id: userId,
      email,
      name,
      passwordHash,
      role: "user" as const,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.users.set(userId, user);

    const token = generateToken(32);
    const refreshToken = generateToken(48);
    sessionStore.set(token, userId);

    return {
      token,
      refreshToken,
      user: { id: user.id, email: user.email, name: user.name, role: user.role },
    };
  });

  app.post("/api/v1/auth/login", async (req, reply) => {
    const parsed = AuthLoginRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const { email, password } = parsed.data;
    const user = Array.from(db.memoryStore.users.values()).find((u) => u.email === email);
    // Return identical error for both cases to avoid user enumeration
    if (!user) return reply.status(401).send({ error: "Invalid email or password" });

    const valid = await verifyPassword(password, user.passwordHash);
    if (!valid) return reply.status(401).send({ error: "Invalid email or password" });

    const token = generateToken(32);
    const refreshToken = generateToken(48);
    sessionStore.set(token, user.id);

    return {
      token,
      refreshToken,
      user: { id: user.id, email: user.email, name: user.name, role: user.role },
    };
  });

  app.post("/api/v1/auth/logout", { preHandler: requireAuth }, async (req) => {
    const authHeader = req.headers.authorization!;
    const token = authHeader.slice(7);
    sessionStore.delete(token);
    return { success: true };
  });

  // ==========================================
  // Agents Routes (protected)
  // ==========================================
  app.get("/api/v1/agents", { preHandler: requireAuth }, async () => {
    return Array.from(db.memoryStore.agents.values());
  });

  app.post("/api/v1/agents", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = CreateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const id = crypto.randomUUID();
    const agent = {
      ...parsed.data,
      id,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.agents.set(id, agent);
    return agent;
  });

  app.get("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const agent = db.memoryStore.agents.get(id);
    if (!agent) return reply.status(404).send({ error: "Agent not found" });
    return agent;
  });

  app.put("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const existing = db.memoryStore.agents.get(id);
    if (!existing) return reply.status(404).send({ error: "Agent not found" });

    const parsed = UpdateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const updated = { ...existing, ...parsed.data, updatedAt: new Date().toISOString() };
    db.memoryStore.agents.set(id, updated);
    return updated;
  });

  app.delete("/api/v1/agents/:id", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!db.memoryStore.agents.has(id)) return reply.status(404).send({ error: "Agent not found" });
    db.memoryStore.agents.delete(id);
    return { success: true };
  });

  // ==========================================
  // Conversations & Messages (protected)
  // ==========================================
  app.get("/api/v1/conversations", { preHandler: requireAuth }, async () => {
    return Array.from(db.memoryStore.conversations.values());
  });

  app.post("/api/v1/conversations", { preHandler: requireAuth }, async (req) => {
    const body = req.body as { title?: string; agentId?: string } | null;
    const id = crypto.randomUUID();
    const conversation = {
      id,
      title: body?.title || "New Conversation",
      agentId: body?.agentId || "agent-default-1",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.conversations.set(id, conversation);
    return conversation;
  });

  app.get("/api/v1/conversations/:id/messages", { preHandler: requireAuth }, async (req) => {
    const { id } = req.params as { id: string };
    return Array.from(db.memoryStore.messages.values()).filter((m) => m.conversationId === id);
  });

  app.post("/api/v1/messages", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = SendMessageRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const { conversationId, content, agentId } = parsed.data;

    // Save user message
    const userMsgId = crypto.randomUUID();
    const userMessage = {
      id: userMsgId,
      conversationId,
      role: "user",
      content,
      createdAt: new Date().toISOString(),
    };
    db.memoryStore.messages.set(userMsgId, userMessage);

    // Fetch conversation & agent
    const conversation = db.memoryStore.conversations.get(conversationId);
    const targetAgentId = agentId || conversation?.agentId || "agent-default-1";
    const agent = db.memoryStore.agents.get(targetAgentId) || Array.from(db.memoryStore.agents.values())[0];

    if (!agent) {
      return reply.status(500).send({ error: "No agent available to process the message." });
    }

    // Create a Run
    const runId = crypto.randomUUID();
    const run = {
      id: runId,
      conversationId,
      agentId: agent.id,
      status: "queued",
      userPrompt: content,
      currentStep: 0,
      maxSteps: agent.maxSteps || 25,
      tokensUsed: 0,
      costEstimate: 0,
      createdAt: new Date().toISOString(),
    };
    db.memoryStore.runs.set(runId, run);

    // Forward run events to any subscribed WebSockets
    coordinator.onEvent(runId, (event) => {
      const subs = subscribers.get(conversationId);
      if (subs) {
        const msg = JSON.stringify(event);
        for (const conn of subs) {
          try {
            conn.socket.send(msg);
          } catch {
            // connection may be closed
          }
        }
      }
    });

    // Build conversation history
    const history = Array.from(db.memoryStore.messages.values())
      .filter((m) => m.conversationId === conversationId)
      .sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime())
      .map((m) => ({ role: m.role as "user" | "assistant", content: m.content }));

    // Run coordinator asynchronously in background
    coordinator
      .executeRun(run as any, agent, history)
      .then((finalAnswer) => {
        const assistantMsgId = crypto.randomUUID();
        db.memoryStore.messages.set(assistantMsgId, {
          id: assistantMsgId,
          conversationId,
          role: "assistant",
          content: finalAnswer,
          createdAt: new Date().toISOString(),
        });
      })
      .catch((err) => {
        app.log.error({ err }, "Run failed");
      });

    return { runId, message: userMessage };
  });

  // ==========================================
  // Runs (protected)
  // ==========================================
  app.get("/api/v1/runs/:runId", { preHandler: requireAuth }, async (req, reply) => {
    const { runId } = req.params as { runId: string };
    const run = db.memoryStore.runs.get(runId);
    if (!run) return reply.status(404).send({ error: "Run not found" });
    return run;
  });

  app.post("/api/v1/runs/:runId/cancel", { preHandler: requireAuth }, async (req, reply) => {
    const { runId } = req.params as { runId: string };
    const cancelled = coordinator.cancelRun(runId);
    if (!cancelled) return reply.status(404).send({ error: "Run not found or already finished" });
    return { success: true };
  });

  // ==========================================
  // Approvals Routes (protected)
  // ==========================================
  app.get("/api/v1/approvals", { preHandler: requireAuth }, async () => {
    return Array.from(db.memoryStore.approvals.values());
  });

  app.post("/api/v1/approvals/:id/resolve", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ResolveApprovalRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const success = coordinator.resolveApproval(id, parsed.data.decision);
    if (!success) return reply.status(404).send({ error: "Approval not found or already resolved" });

    return { success: true, decision: parsed.data.decision };
  });

  // ==========================================
  // Automations Routes (protected)
  // ==========================================
  app.get("/api/v1/automations", { preHandler: requireAuth }, async () => {
    return Array.from(db.memoryStore.automations.values());
  });

  app.post("/api/v1/automations", { preHandler: requireAuth }, async (req, reply) => {
    const parsed = CreateAutomationRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const id = crypto.randomUUID();
    const automation = {
      ...parsed.data,
      id,
      lastRunAt: null as string | null,
      nextRunAt: new Date(Date.now() + 3600000).toISOString(),
      lastStatus: "scheduled",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.automations.set(id, automation);
    return automation;
  });

  app.post("/api/v1/automations/:id/run", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const auto = db.memoryStore.automations.get(id);
    if (!auto) return reply.status(404).send({ error: "Automation not found" });

    auto.lastRunAt = new Date().toISOString();
    auto.lastStatus = "success";
    db.memoryStore.automations.set(id, auto);
    return { success: true, executedAt: auto.lastRunAt };
  });

  // ==========================================
  // Computer Viewer & Actions (protected)
  // ==========================================
  app.get("/api/v1/computers", { preHandler: requireAuth }, async () => {
    return Array.from(db.memoryStore.computerSessions.values());
  });

  app.post("/api/v1/computers/:id/action", { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ComputerActionSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ error: "Validation failed", details: parsed.error.errors });

    const session = db.memoryStore.computerSessions.get(id) || {
      id,
      status: "running",
      activeUrl: parsed.data.url || "about:blank",
      cursorX: parsed.data.x || 120,
      cursorY: parsed.data.y || 240,
      lastAction: parsed.data.action,
      updatedAt: new Date().toISOString(),
    };

    if (parsed.data.action === "navigate" && parsed.data.url) {
      session.activeUrl = parsed.data.url;
    }
    session.lastAction = `${parsed.data.action}: ${parsed.data.url || parsed.data.text || ""}`;
    session.updatedAt = new Date().toISOString();
    db.memoryStore.computerSessions.set(id, session);

    return { success: true, session };
  });

  // ==========================================
  // Models list (protected)
  // ==========================================
  app.get("/api/v1/models", { preHandler: requireAuth }, async () => {
    return aiRouter.listAllModels();
  });

  // ==========================================
  // WebSocket Server (/ws) — authenticated via query param token
  // ==========================================
  app.get("/ws", { websocket: true }, (connection, req) => {
    // Authenticate via ?token=... query param (headers not supported in WS)
    const token = (req.query as Record<string, string>)?.token;
    const userId = token ? sessionStore.get(token) : null;
    if (!userId) {
      connection.socket.close(4001, "Unauthorized: provide a valid ?token= query parameter");
      return;
    }

    let activeConversationId: string | null = null;

    connection.socket.on("message", async (rawMessage: Buffer) => {
      try {
        const msg = JSON.parse(rawMessage.toString());

        if (msg.action === "subscribe" && typeof msg.conversationId === "string") {
          const convId = msg.conversationId;
          activeConversationId = convId;
          if (!subscribers.has(convId)) {
            subscribers.set(convId, new Set());
          }
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
        // Clean up empty sets
        if (subscribers.get(activeConversationId)!.size === 0) {
          subscribers.delete(activeConversationId);
        }
      }
    });
  });

  const port = parseInt(process.env.GATEWAY_PORT || process.env.PORT || "3000", 10);
  await app.listen({ port, host: "0.0.0.0" });
  app.log.info(`[AgentForge Gateway] Running on http://0.0.0.0:${port}`);
}

main().catch((err) => {
  console.error("Gateway startup error:", err);
  process.exit(1);
});
