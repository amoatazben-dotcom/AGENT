import { describe, it, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { OpenAICompatibleProvider } from "../dist/openai-compatible.js";

function sseResponse(chunks, { status = 200 } = {}) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c));
      controller.close();
    },
  });
  return new Response(stream, { status, headers: { "Content-Type": "text/event-stream" } });
}

const sse = (obj) => `data: ${JSON.stringify(obj)}\n\n`;

describe("OpenAICompatibleProvider", () => {
  let lastUrl = "";
  beforeEach(() => {
    lastUrl = "";
    globalThis.fetch = async (url) => {
      lastUrl = String(url);
      return sseResponse([
        sse({ choices: [{ delta: { content: "Hello" } }] }),
        // fragmented tool call: name + first half of args in one chunk...
        sse({ choices: [{ delta: { tool_calls: [{ index: 0, id: "call_1", function: { name: "shell", arguments: '{"cm' } }] } }] }),
        // ...remainder in the next chunk
        sse({ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: 'd":"ls"}' } }] } }] }),
        sse({ usage: { prompt_tokens: 5, completion_tokens: 3, total_tokens: 8 } }),
        "data: [DONE]\n\n",
      ]);
    };
  });

  it("accumulates fragmented tool_call arguments across chunks", async () => {
    const p = new OpenAICompatibleProvider({ baseUrl: "https://example.com/v1", apiKey: "k" });
    const events = [];
    for await (const e of p.chat({ model: "m", messages: [{ role: "user", content: "hi" }] })) {
      events.push(e);
    }
    const deltas = events.filter((e) => e.type === "delta").map((e) => e.content).join("");
    assert.equal(deltas, "Hello");
    const tool = events.find((e) => e.type === "tool_call");
    assert.ok(tool, "tool_call emitted");
    assert.equal(tool.name, "shell");
    assert.deepEqual(tool.arguments, { cmd: "ls" });
    const usage = events.find((e) => e.type === "usage");
    assert.deepEqual(usage, { type: "usage", promptTokens: 5, completionTokens: 3, totalTokens: 8 });
    assert.equal(events[events.length - 1].type, "done");
  });

  it("hits normalized chat URL without /v1/v1", async () => {
    const p = new OpenAICompatibleProvider({ baseUrl: "https://example.com/v1/", apiKey: "k" });
    for await (const _ of p.chat({ model: "m", messages: [{ role: "user", content: "hi" }] })) break;
    assert.equal(lastUrl, "https://example.com/v1/chat/completions");
  });

  it("maps 401 to auth error (non-retryable)", async () => {
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ error: { message: "bad key" } }), { status: 401 });
    const p = new OpenAICompatibleProvider({ baseUrl: "https://example.com/v1", apiKey: "bad" });
    await assert.rejects(
      async () => {
        for await (const _ of p.chat({ model: "m", messages: [{ role: "user", content: "hi" }] })) {
          /* drain */
        }
      },
      (e) => {
        assert.equal(e.kind, "PROVIDER_AUTH");
        assert.equal(e.retryable, false);
        return true;
      }
    );
  });

  it("testConnection reports auth_failed honestly", async () => {
    globalThis.fetch = async () => new Response("{}", { status: 401 });
    const p = new OpenAICompatibleProvider({ baseUrl: "https://example.com/v1", apiKey: "bad" });
    const out = await p.testConnection();
    assert.equal(out.status, "auth_failed");
    assert.equal(out.ok, false);
  });

  it("fetchModelIds reads data[].id", async () => {
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ data: [{ id: "a" }, { id: "b" }] }), { status: 200 });
    const p = new OpenAICompatibleProvider({ baseUrl: "https://example.com", apiKey: "k" });
    assert.deepEqual(await p.fetchModelIds(), ["a", "b"]);
  });
});
