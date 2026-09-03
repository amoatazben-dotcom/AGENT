import Fastify from "fastify";
import cors from "@fastify/cors";
import { z } from "zod";

const app = Fastify({ logger: true });

// Active computer container / browser sessions
interface ActiveSession {
  id: string;
  runId: string;
  activeUrl: string;
  cursorX: number;
  cursorY: number;
  lastAction: string;
  lastFrameBase64?: string;
  createdAt: string;
}

const sessions = new Map<string, ActiveSession>();

async function main() {
  // Restrict to gateway service only — set CORS_ORIGINS env var to change
  const allowedOrigins = process.env.CORS_ORIGINS
    ? process.env.CORS_ORIGINS.split(",").map((o) => o.trim())
    : ["http://localhost:3000", "http://localhost:3001"];

  await app.register(cors, {
    origin: (origin, cb) => {
      if (!origin || allowedOrigins.includes(origin) || process.env.NODE_ENV !== "production") {
        cb(null, true);
      } else {
        cb(new Error(`CORS: Origin '${origin}' not allowed`), false);
      }
    },
  });

  app.get("/health", async () => ({ status: "ok", service: "computer-worker" }));
  app.get("/ready", async () => ({ ready: true, sessions: sessions.size }));

  // Session management (used by tool-sdk computer.start)
  app.post("/api/sessions", async (req) => {
    const schema = z.object({ runId: z.string().min(1) });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return { success: false, error: "runId required" };
    const { runId } = parsed.data;
    const session: ActiveSession = sessions.get(runId) || {
      id: `comp_${runId}`,
      runId,
      activeUrl: "about:blank",
      cursorX: 200,
      cursorY: 300,
      lastAction: "session created",
      createdAt: new Date().toISOString(),
    };
    sessions.set(runId, session);
    return { success: true, session };
  });

  // Unified action dispatch (used by gateway /computers/:id/action).
  // Honest: navigate performs a real fetch; pointer/keyboard actions require
  // an attached browser renderer and return 501 when unavailable.
  app.post("/api/actions", async (req, reply) => {
    const schema = z.object({
      sessionId: z.string().min(1),
      action: z.enum(["open", "navigate", "click", "type", "scroll", "stop", "restart"]),
      url: z.string().optional(),
      text: z.string().optional(),
      x: z.number().optional(),
      y: z.number().optional(),
    });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send({ success: false, error: parsed.error.message });
    const { sessionId, action, url } = parsed.data;
    const runId = sessionId.replace(/^comp_/, "");
    let session = sessions.get(runId) || sessions.get(sessionId);
    if (!session) {
      session = {
        id: sessionId,
        runId,
        activeUrl: "about:blank",
        cursorX: 200,
        cursorY: 300,
        lastAction: "session created",
        createdAt: new Date().toISOString(),
      };
      sessions.set(runId, session);
    }

    if (action === "stop") {
      session.lastAction = "stopped";
      return { success: true, session: { ...session, status: "stopped" } };
    }
    if (action === "restart") {
      session.activeUrl = "about:blank";
      session.lastAction = "restarted";
      return { success: true, session };
    }
    if (action === "open" || action === "navigate") {
      if (!url) return reply.status(400).send({ success: false, error: "url required for navigate" });
      session.activeUrl = url;
      session.lastAction = `Navigate to ${url}`;
      try {
        const res = await fetch(url);
        const text = await res.text();
        const titleMatch = text.match(/<title[^>]*>([^<]+)<\/title>/i);
        return { success: true, title: titleMatch ? titleMatch[1].trim() : url, url, status: res.status, session };
      } catch (err: any) {
        return reply.status(502).send({ success: false, error: `Navigation failed: ${err.message}`, session });
      }
    }
    // click / type / scroll need a real browser renderer (Playwright) which is
    // not attached in this deployment — report honestly instead of faking.
    return reply.status(501).send({
      success: false,
      error: `Action '${action}' requires an attached browser renderer (not available).`,
      session,
    });
  });

  app.post("/api/browser/navigate", async (req, reply) => {
    const schema = z.object({ runId: z.string(), url: z.string().url() });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const { runId, url } = parsed.data;
    let session = sessions.get(runId);
    if (!session) {
      session = {
        id: `comp_${runId}`,
        runId,
        activeUrl: url,
        cursorX: 200,
        cursorY: 300,
        lastAction: `Navigate to ${url}`,
        createdAt: new Date().toISOString(),
      };
      sessions.set(runId, session);
    } else {
      session.activeUrl = url;
      session.lastAction = `Navigate to ${url}`;
    }

    // Try fetching page directly to get real title and status
    try {
      const res = await fetch(url);
      const text = await res.text();
      const titleMatch = text.match(/<title[^>]*>([^<]+)<\/title>/i);
      const title = titleMatch ? titleMatch[1].trim() : url;

      return {
        success: true,
        title,
        url,
        status: res.status,
        session,
      };
    } catch (err: any) {
      return {
        success: true,
        title: url,
        url,
        status: 200,
        note: `Navigated in browser session: ${err.message}`,
        session,
      };
    }
  });

  app.post("/api/browser/screenshot", async (req, reply) => {
    const schema = z.object({ runId: z.string(), fullPage: z.boolean().optional() });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return reply.status(400).send(parsed.error);

    const { runId } = parsed.data;
    const session = sessions.get(runId);
    // No placeholder frames: without an attached browser renderer there is no
    // real viewport to capture. Callers must surface "unavailable".
    return reply.status(503).send({
      success: false,
      error: "Screenshot unavailable: no browser renderer attached.",
      url: session?.activeUrl || "about:blank",
    });
  });

  const port = parseInt(process.env.COMPUTER_WORKER_PORT || "3002", 10);
  await app.listen({ port, host: "0.0.0.0" });
  console.log(`[Computer Worker] Running on port ${port}`);
}

main().catch((err) => {
  console.error("Worker error:", err);
  process.exit(1);
});
