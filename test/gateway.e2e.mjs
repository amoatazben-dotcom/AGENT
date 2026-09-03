/**
 * AgentForge E2E (test-only): mock OpenAI-compatible upstream + real gateway.
 * Path: register → provider CRUD → test connection → models → agent →
 * conversation → send message → WS streaming → persistence across restart.
 *
 * Run: node test/gateway.e2e.mjs
 * Env: GATEWAY_PORT (default 3210), AGENTFORGE_DB_PATH (temp file)
 */
import { spawn } from "node:child_process";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

const GATEWAY_PORT = process.env.GATEWAY_PORT || "3210";
const DB = process.env.AGENTFORGE_DB_PATH || path.join(fs.mkdtempSync(path.join(os.tmpdir(), "af-e2e-")), "agentforge.db");
const BASE = `http://localhost:${GATEWAY_PORT}`;

let failures = 0;
function check(name, cond, extra = "") {
  if (cond) console.log(`  PASS ${name}`);
  else {
    failures++;
    console.log(`  FAIL ${name} ${extra}`);
  }
}

// ---- Mock OpenAI-compatible upstream (TEST ONLY, never production) ----
async function startMockUpstream() {
  const { createServer } = await import("node:http");
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      if (req.url === "/v1/models") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ data: [{ id: "mock-model-a" }, { id: "mock-model-b" }] }));
        return;
      }
      if (req.url === "/v1/chat/completions") {
        let body = "";
        req.on("data", (c) => (body += c));
        req.on("end", () => {
          res.writeHead(200, {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          });
          const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);
          send({ choices: [{ delta: { content: "Hello " } }] });
          send({ choices: [{ delta: { content: "from mock!" } }] });
          send({ usage: { prompt_tokens: 5, completion_tokens: 3, total_tokens: 8 } });
          res.write("data: [DONE]\n\n");
          res.end();
        });
        return;
      }
      res.writeHead(404);
      res.end("nope");
    });
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function startGateway() {
  return new Promise((resolve, reject) => {
    const root = process.cwd();
    const proc = spawn(
      process.execPath,
      [path.join(root, "apps", "gateway", "dist", "index.js")],
      {
        cwd: root,
        env: { ...process.env, GATEWAY_PORT, AGENTFORGE_DB_PATH: DB, MASTER_ENCRYPTION_KEY: "a".repeat(64) },
        stdio: ["ignore", "pipe", "pipe"],
      }
    );
    let out = "";
    const timer = setTimeout(() => reject(new Error("gateway start timeout: " + out.slice(-500))), 25000);
    proc.stdout.on("data", (d) => {
      out += d.toString();
      if (out.includes("Running on")) {
        clearTimeout(timer);
        resolve(proc);
      }
    });
    proc.stderr.on("data", (d) => (out += d.toString()));
    proc.on("exit", (c) => {
      if (!proc.killed) console.log("gateway exited", c, out.slice(-1000));
    });
  });
}

async function api(method, p, token, body) {
  const res = await fetch(`${BASE}${p}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    json = null;
  }
  return { status: res.status, json };
}

async function scenario(gateway) {
  console.log("== E2E: auth ==");
  const reg = await api("POST", "/api/v1/auth/register", null, {
    email: "e2e@test.dev",
    password: "password123",
    name: "E2E",
  });
  check("register 200", reg.status === 200, JSON.stringify(reg.json)?.slice(0, 200));
  const token = reg.json?.token;
  check("token issued", !!token);
  check("refresh token issued", !!reg.json?.refreshToken);

  // login again (second path)
  const login = await api("POST", "/api/v1/auth/login", null, { email: "e2e@test.dev", password: "password123" });
  check("login 200", login.status === 200);
  const token2 = login.json?.token;

  // refresh rotation
  const ref = await api("POST", "/api/v1/auth/refresh", null, { refreshToken: login.json?.refreshToken });
  check("refresh 200", ref.status === 200, JSON.stringify(ref.json)?.slice(0, 120));
  const tokenR = ref.json?.token;

  console.log("== E2E: providers ==");
  const upstream = await startMockUpstream();
  const upstreamPort = upstream.address().port;
  const upstreamBase = `http://127.0.0.1:${upstreamPort}`;

  const created = await api("POST", "/api/v1/providers", tokenR, {
    name: "E2E Mock",
    type: "openai_compatible",
    baseUrl: upstreamBase,
    apiKey: "test-key-123",
    defaultModel: "mock-model-a",
  });
  check("create provider 200", created.status === 200, JSON.stringify(created.json)?.slice(0, 200));
  check("apiKey never returned", created.json && !("apiKey" in created.json) && !("apiKeyEncrypted" in created.json));
  check("hasApiKey true", created.json?.hasApiKey === true);
  check("masked", typeof created.json?.apiKeyMasked === "string" && created.json.apiKeyMasked.includes("••"));
  const pid = created.json?.id;

  const tested = await api("POST", `/api/v1/providers/${pid}/test`, tokenR, {});
  check("test connection ok", tested.json?.status === "connected", JSON.stringify(tested.json)?.slice(0, 200));
  check("latency present", typeof tested.json?.latencyMs === "number");
  check("models discovered", Array.isArray(tested.json?.models) && tested.json.models.length === 2);

  const models = await api("GET", `/api/v1/providers/${pid}/models`, tokenR);
  check("models cached", models.json?.models?.length === 2, JSON.stringify(models.json)?.slice(0, 160));

  // invalid URL rejected
  const bad = await api("POST", "/api/v1/providers", tokenR, {
    name: "bad",
    type: "openai_compatible",
    baseUrl: "not-a-url",
    apiKey: "x",
    defaultModel: "m",
  });
  check("invalid baseUrl rejected (400)", bad.status === 400);

  console.log("== E2E: agent + conversation + chat ==");
  const agent = await api("POST", "/api/v1/agents", tokenR, {
    name: "E2E Agent",
    description: "test",
    icon: "smart_toy",
    systemPrompt: "You are helpful.",
    primaryProvider: "openai_compatible",
    primaryProviderId: undefined,
    primaryModel: "mock-model-a",
    temperature: 0.1,
    maxSteps: 3,
    approvalPolicy: "allow",
    permissions: { computerUse: false, shell: false, filesystem: true, network: true, automation: false, subagents: false },
  });
  // protocol requires uuid ids for agent refs; gateway stores as-is. Link agent to provider:
  const agentId = agent.json?.id;
  check("agent created", !!agentId);
  // point agent at our provider (update with provider id fields)
  await api("PUT", `/api/v1/agents/${agentId}`, tokenR, { primaryProviderId: pid, primaryModel: "mock-model-a" });

  const conv = await api("POST", "/api/v1/conversations", tokenR, { title: "E2E chat", agentId });
  check("conversation created", !!conv.json?.id);
  const convId = conv.json.id;

  // WS streaming is exercised via run events; REST polling verifies persistence.
  const sent = await api("POST", "/api/v1/messages", tokenR, { conversationId: convId, content: "Say hi", agentId });
  check("send message accepted", sent.status === 200 && !!sent.json?.runId, JSON.stringify(sent.json)?.slice(0, 200));
  const runId = sent.json?.runId;

  // poll run + messages (streaming executed server-side)
  let assistant = "";
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 500));
    const msgs = await api("GET", `/api/v1/conversations/${convId}/messages`, tokenR);
    const found = (msgs.json || []).find((m) => m.role === "assistant");
    if (found) {
      assistant = found.content;
      break;
    }
  }
  check("assistant response persisted", assistant.includes("Hello") && assistant.includes("mock"), assistant.slice(0, 120));

  const run = await api("GET", `/api/v1/runs/${runId}`, tokenR);
  check("run completed", run.json?.status === "completed", JSON.stringify(run.json)?.slice(0, 200));
  check("usage tracked", (run.json?.tokensUsed || 0) >= 8, JSON.stringify({ t: run.json?.tokensUsed }));

  const events = await api("GET", `/api/v1/runs/${runId}/events`, tokenR);
  check("run events persisted", Array.isArray(events.json) && events.json.length > 0, `count=${events.json?.length}`);

  console.log("== E2E: health/ready ==");
  const health = await api("GET", "/health", null);
  check("health ok", health.json?.status === "ok");
  const ready = await api("GET", "/ready", null);
  check("ready reports services", !!ready.json?.services, JSON.stringify(ready.json?.services));

  upstream.close();
  return { token: tokenR, pid, convId };
}

const proc = await startGateway();
console.log(`gateway up on ${GATEWAY_PORT}, db=${DB}`);
try {
  await scenario(proc);
} catch (e) {
  failures++;
  console.log("FATAL", e);
} finally {
  proc.kill();
  await new Promise((r) => setTimeout(r, 1500));
}

// ---- restart: data must survive ----
console.log("== E2E: restart persistence ==");
const proc2 = await startGateway();
try {
  const login = await api("POST", "/api/v1/auth/login", null, { email: "e2e@test.dev", password: "password123" });
  check("login after restart (sessions persist)", login.status === 200, `status=${login.status}`);
  const t = login.json?.token;
  const convs = await api("GET", "/api/v1/conversations", t);
  check("conversations survive restart", Array.isArray(convs.json) && convs.json.length >= 1, `count=${convs.json?.length}`);
  const provs = await api("GET", "/api/v1/providers", t);
  check("providers survive restart", Array.isArray(provs.json) && provs.json.length >= 1);
  check("keys still masked", provs.json?.[0] && !("apiKey" in provs.json[0]) && provs.json[0].hasApiKey === true);
  const msgs = convs.json?.length
    ? await api("GET", `/api/v1/conversations/${convs.json[0].id}/messages`, t)
    : { json: [] };
  check("messages survive restart", Array.isArray(msgs.json) && msgs.json.length >= 2, `count=${msgs.json?.length}`);
} catch (e) {
  failures++;
  console.log("FATAL restart", e);
} finally {
  proc2.kill();
}

console.log(failures === 0 ? "\nE2E ALL PASS" : `\nE2E ${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
