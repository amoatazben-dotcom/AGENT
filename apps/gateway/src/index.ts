import Fastify from "fastify";
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

// Connected WebSocket clients mapping runId -> Set of connections
const subscribers = new Map<string, Set<any>>();

async function main() {
  await app.register(cors, { origin: "*" });
  await app.register(websocket);

  // Health Checks
  app.get("/health", async () => ({ status: "ok", timestamp: new Date().toISOString() }));
  app.get("/ready", async () => ({ ready: true, services: ["ai-router", "database", "coordinator"] }));

  // ==========================================
  // Auth Routes
  // ==========================================
  app.post("/api/v1/auth/register", async (req, reply) => {
    const parsed = AuthRegisterRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

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
      role: "user",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.users.set(userId, user);

    const token = generateToken(24);
    const refreshToken = generateToken(32);
    return { token, refreshToken, user: { id: user.id, email: user.email, name: user.name, role: user.role } };
  });

  app.post("/api/v1/auth/login", async (req, reply) => {
    const parsed = AuthLoginRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const { email, password } = parsed.data;
    const user = Array.from(db.memoryStore.users.values()).find((u) => u.email === email);
    if (!user) return reply.status(401).send({ error: "Invalid email or password" });

    const valid = await verifyPassword(password, user.passwordHash);
    if (!valid) return reply.status(401).send({ error: "Invalid email or password" });

    const token = generateToken(24);
    const refreshToken = generateToken(32);
    return { token, refreshToken, user: { id: user.id, email: user.email, name: user.name, role: user.role } };
  });

  // ==========================================
  // Agents Routes
  // ==========================================
  app.get("/api/v1/agents", async () => {
    return Array.from(db.memoryStore.agents.values());
  });

  app.post("/api/v1/agents", async (req, reply) => {
    const parsed = CreateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

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

  app.get("/api/v1/agents/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    const agent = db.memoryStore.agents.get(id);
    if (!agent) return reply.status(404).send({ error: "Agent not found" });
    return agent;
  });

  app.put("/api/v1/agents/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    const existing = db.memoryStore.agents.get(id);
    if (!existing) return reply.status(404).send({ error: "Agent not found" });

    const parsed = UpdateAgentRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const updated = { ...existing, ...parsed.data, updatedAt: new Date().toISOString() };
    db.memoryStore.agents.set(id, updated);
    return updated;
  });

  app.delete("/api/v1/agents/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    db.memoryStore.agents.delete(id);
    return { success: true };
  });

  // ==========================================
  // Conversations & Messages
  // ==========================================
  app.get("/api/v1/conversations", async () => {
    return Array.from(db.memoryStore.conversations.values());
  });

  app.post("/api/v1/conversations", async (req) => {
    const body = req.body as any;
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

  app.get("/api/v1/conversations/:id/messages", async (req) => {
    const { id } = req.params as { id: string };
    return Array.from(db.memoryStore.messages.values()).filter((m) => m.conversationId === id);
  });

  app.post("/api/v1/messages", async (req, reply) => {
    const parsed = SendMessageRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

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
          } catch {}
        }
      }
    });

    // Run coordinator asynchronously in background
    const history = Array.from(db.memoryStore.messages.values())
      .filter((m) => m.conversationId === conversationId)
      .map((m) => ({ role: m.role as any, content: m.content }));

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
        console.error("Run failed:", err);
      });

    return { runId, message: userMessage };
  });

  // ==========================================
  // Approvals Routes
  // ==========================================
  app.get("/api/v1/approvals", async () => {
    return Array.from(db.memoryStore.approvals.values());
  });

  app.post("/api/v1/approvals/:id/resolve", async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ResolveApprovalRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const success = coordinator.resolveApproval(id, parsed.data.decision);
    if (!success) return reply.status(404).send({ error: "Approval not found or already resolved" });

    return { success: true, decision: parsed.data.decision };
  });

  // ==========================================
  // Automations Routes
  // ==========================================
  app.get("/api/v1/automations", async () => {
    return Array.from(db.memoryStore.automations.values());
  });

  app.post("/api/v1/automations", async (req, reply) => {
    const parsed = CreateAutomationRequestSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const id = crypto.randomUUID();
    const automation = {
      ...parsed.data,
      id,
      lastRunAt: null,
      nextRunAt: new Date(Date.now() + 3600000).toISOString(),
      lastStatus: "scheduled",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    db.memoryStore.automations.set(id, automation);
    return automation;
  });

  app.post("/api/v1/automations/:id/run", async (req, reply) => {
    const { id } = req.params as { id: string };
    const auto = db.memoryStore.automations.get(id);
    if (!auto) return reply.status(404).send({ error: "Automation not found" });

    auto.lastRunAt = new Date().toISOString();
    auto.lastStatus = "success";
    db.memoryStore.automations.set(id, auto);
    return { success: true, executedAt: auto.lastRunAt };
  });

  // ==========================================
  // Computer Viewer & Actions
  // ==========================================
  app.get("/api/v1/computers", async () => {
    return Array.from(db.memoryStore.computerSessions.values());
  });

  app.post("/api/v1/computers/:id/action", async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = ComputerActionSchema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const session = db.memoryStore.computerSessions.get(id) || {
      id,
      status: "running",
      activeUrl: parsed.data.url || "https://openai.com",
      cursorX: parsed.data.x || 120,
      cursorY: parsed.data.y || 240,
      lastAction: parsed.data.action,
      updatedAt: new Date().toISOString(),
    };

    if (parsed.data.action === "navigate" && parsed.data.url) {
      session.activeUrl = parsed.data.url;
    }
    session.lastAction = `${parsed.data.action}: ${parsed.data.url || parsed.data.text || ""}`;
    db.memoryStore.computerSessions.set(id, session);

    return { success: true, session };
  });

  // ==========================================
  // WebSocket Server (/ws)
  // ==========================================
  app.get("/ws", { websocket: true }, (connection) => {
    let activeConversationId: string | null = null;

    connection.socket.on("message", async (rawMessage: any) => {
      try {
        const msg = JSON.parse(rawMessage.toString());

        if (msg.action === "subscribe" && typeof msg.conversationId === "string") {
          const convId = msg.conversationId;
          activeConversationId = convId;
          if (!subscribers.has(convId)) {
            subscribers.set(convId, new Set());
          }
          subscribers.get(convId)!.add(connection);
          connection.socket.send(
            JSON.stringify({ type: "subscribed", conversationId: convId })
          );
        } else if (msg.action === "resolve_approval" && msg.approvalId) {
          coordinator.resolveApproval(msg.approvalId, msg.decision);
        } else if (msg.action === "stop_run" && msg.runId) {
          coordinator.cancelRun(msg.runId);
        } else if (msg.action === "ping") {
          connection.socket.send(JSON.stringify({ type: "pong", timestamp: Date.now() }));
        }
      } catch (err) {
        console.error("WS error:", err);
      }
    });

    connection.socket.on("close", () => {
      if (activeConversationId && subscribers.has(activeConversationId)) {
        subscribers.get(activeConversationId)!.delete(connection);
      }
    });
  });

  const port = parseInt(process.env.GATEWAY_PORT || process.env.PORT || "3000", 10);
  await app.listen({ port, host: "0.0.0.0" });
  console.log(`[AgentForge Gateway] Running on http://0.0.0.0:${port}`);
}

main().catch((err) => {
  console.error("Gateway startup error:", err);
  process.exit(1);
});
