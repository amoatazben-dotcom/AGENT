import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { normalizeBaseUrl, joinUrl, isLocalHost } from "../dist/base-url.js";

describe("normalizeBaseUrl", () => {
  it("keeps existing /v1 without duplication", () => {
    const n = normalizeBaseUrl("https://api.openai.com/v1");
    assert.equal(n.baseUrl, "https://api.openai.com/v1");
    assert.equal(n.chatCompletionsUrl, "https://api.openai.com/v1/chat/completions");
    assert.equal(n.modelsUrl, "https://api.openai.com/v1/models");
  });

  it("appends /v1 when missing and trims slashes", () => {
    const n = normalizeBaseUrl("https://openrouter.ai/api/v1/");
    assert.equal(n.baseUrl, "https://openrouter.ai/api/v1");
    const m = normalizeBaseUrl("http://192.168.1.10:11434/");
    assert.equal(m.baseUrl, "http://192.168.1.10:11434/v1");
    assert.equal(m.isLocal, true);
    assert.equal(m.usesHttp, true);
  });

  it("never produces /v1/v1 or double slashes", () => {
    for (const raw of [
      "https://api.openai.com/v1/",
      "https://api.openai.com//v1//",
      "http://localhost:11434/v1",
      "http://localhost:11434",
    ]) {
      const n = normalizeBaseUrl(raw);
      assert.ok(!n.baseUrl.includes("/v1/v1"), raw);
      assert.ok(!n.chatCompletionsUrl.includes("//chat"), raw);
    }
  });

  it("rejects non-http URLs", () => {
    assert.throws(() => normalizeBaseUrl("not-a-url"), /must start with http/);
    assert.throws(() => normalizeBaseUrl(""), /required/);
  });

  it("detects local hosts", () => {
    assert.equal(isLocalHost("localhost"), true);
    assert.equal(isLocalHost("10.0.2.2"), true);
    assert.equal(isLocalHost("192.168.1.50"), true);
    assert.equal(isLocalHost("api.openai.com"), false);
  });

  it("joinUrl avoids double slashes", () => {
    assert.equal(joinUrl("https://x.com/v1/", "/chat/completions"), "https://x.com/v1/chat/completions");
  });
});
