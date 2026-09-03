import { describe, it, before } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

process.env.AGENTFORGE_DB_PATH = path.join(
  fs.mkdtempSync(path.join(os.tmpdir(), "af-db-test-")),
  "agentforge.db"
);
process.env.MASTER_ENCRYPTION_KEY = "b".repeat(64);

const { db } = await import("../dist/index.js");

describe("database persistence", () => {
  it("is persistent (SQLite, not ephemeral)", () => {
    assert.equal(db.isPersistent, true);
    assert.ok(fs.existsSync(db.dbFilePath), db.dbFilePath);
  });

  it("roundtrips provider configs + sessions + conversations", async () => {
    db.memoryStore.providerConfigs.set("p1", {
      id: "p1",
      name: "T",
      type: "openai_compatible",
      apiKeyEncrypted: "enc",
      baseUrl: "https://x/v1",
      defaultModel: "m",
      enabled: true,
      isDefault: false,
      supportsStreaming: true,
      supportsTools: true,
      supportsVision: false,
      customHeadersJson: "{}",
      timeoutMs: 60000,
      status: "unknown",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
    db.memoryStore.conversations.set("c1", {
      id: "c1",
      title: "t",
      agentId: "a",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    });
    db.memoryStore.messages.set("m1", {
      id: "m1",
      conversationId: "c1",
      role: "user",
      content: "hi",
      createdAt: new Date().toISOString(),
    });

    // Read straight from SQLite file, bypassing the Maps
    const { DatabaseSync } = await import("node:sqlite");
    const raw = new DatabaseSync(db.dbFilePath);
    const prov = raw.prepare("SELECT data FROM provider_configs WHERE id='p1'").get();
    assert.ok(prov && JSON.parse(prov.data).name === "T");
    const msg = raw.prepare("SELECT content FROM messages WHERE id='m1'").get();
    assert.equal(msg.content, "hi");
    raw.close();
  });

  it("resetScope clears conversations without touching providers", () => {
    const out = db.resetScope("conversations");
    assert.ok(out.cleared.includes("conversations"));
    assert.equal(db.memoryStore.providerConfigs.has("p1"), true);
    assert.equal(db.memoryStore.conversations.size, 0);
  });
});
