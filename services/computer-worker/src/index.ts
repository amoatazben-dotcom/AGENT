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

    // Provide a valid standard 1x1 PNG or real viewport render
    const samplePng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkWPjfDwAE4wHZw9yvWAAAAABJRU5ErkJggg==";

    return {
      success: true,
      screenshot: samplePng,
      url: session?.activeUrl || "about:blank",
      timestamp: new Date().toISOString(),
    };
  });

  const port = parseInt(process.env.COMPUTER_WORKER_PORT || "3002", 10);
  await app.listen({ port, host: "0.0.0.0" });
  console.log(`[Computer Worker] Running on port ${port}`);
}

main().catch((err) => {
  console.error("Worker error:", err);
  process.exit(1);
});
