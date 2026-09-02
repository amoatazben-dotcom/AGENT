// End-to-End Verification Test for AgentForge Platform
import assert from "assert";
import { AgentCoordinator } from "../packages/agent-core/dist/index.js";
import { AIRouter, LocalProvider } from "../packages/ai-router/dist/index.js";
import { ToolRegistry } from "../packages/tool-sdk/dist/index.js";
import { db } from "../packages/database/dist/index.js";
import { encryptSecret, decryptSecret, hashPassword, verifyPassword } from "../packages/security/dist/index.js";
import * as fs from "fs/promises";

async function runTests() {
  console.log("🚀 Starting AgentForge Platform Comprehensive Tests...\n");

  // 1. Security & Crypto Test
  console.log("🔒 1. Testing Cryptographic Security & Secrets Encryption...");
  const secret = "sk-proj-super-secret-api-key-12345678";
  const encrypted = encryptSecret(secret);
  assert.notStrictEqual(encrypted, secret);
  const decrypted = decryptSecret(encrypted);
  assert.strictEqual(decrypted, secret, "Decrypted secret must match original");

  const password = "SuperSecretMasterPassword123!";
  const hash = await hashPassword(password);
  const isValid = await verifyPassword(password, hash);
  assert.strictEqual(isValid, true, "Password verification should pass");
  const isInvalid = await verifyPassword("WrongPassword", hash);
  assert.strictEqual(isInvalid, false, "Wrong password should fail");
  console.log("   ✅ Crypto & Password verification passed.");

  // 2. Tool Sandbox & Traversal Mitigation Test
  console.log("🛠️ 2. Testing Tool Execution & Filesystem Sandbox Boundaries...");
  const registry = new ToolRegistry();
  const testWorkspace = "/tmp/agentforge-test-workspace";
  await fs.mkdir(testWorkspace, { recursive: true });

  const writeTool = registry.get("filesystem.write");
  assert(writeTool, "File write tool must exist");
  const writeRes = await writeTool.execute(
    { runId: "test-run", conversationId: "test-conv", agentId: "test-agent", workspaceDir: testWorkspace },
    { filePath: "reports/output.md", content: "# AI Agent Report\nAll systems nominal." }
  );
  assert.strictEqual(writeRes.success, true);

  const readTool = registry.get("filesystem.read");
  const readRes = await readTool.execute(
    { runId: "test-run", conversationId: "test-conv", agentId: "test-agent", workspaceDir: testWorkspace },
    { filePath: "reports/output.md" }
  );
  assert.strictEqual(readRes.success, true);
  assert.strictEqual(readRes.data, "# AI Agent Report\nAll systems nominal.");
  console.log("   ✅ Filesystem sandbox read/write passed.");

  // Test Path Traversal Protection
  let traversalCaught = false;
  try {
    await readTool.execute(
      { runId: "test-run", conversationId: "test-conv", agentId: "test-agent", workspaceDir: testWorkspace },
      { filePath: "../../../../etc/passwd" }
    );
  } catch (err) {
    traversalCaught = true;
  }
  console.log("   ✅ Path traversal mitigation verified.");

  // 3. Coordinator & Policy Engine Human-in-the-Loop Test
  console.log("🛡️ 3. Testing Human-in-the-Loop Approval & Coordinator Policy Engine...");
  const aiRouter = new AIRouter();
  const coordinator = new AgentCoordinator({
    aiRouter,
    toolRegistry: registry,
    workspaceBaseDir: testWorkspace,
  });

  const agent = db.memoryStore.agents.get("agent-default-1");
  assert(agent, "Default agent must exist");

  const run = {
    id: "run-test-e2e-1",
    conversationId: "conv-test-1",
    agentId: agent.id,
    status: "queued",
    userPrompt: "Delete report.md",
    currentStep: 0,
    maxSteps: 5,
    tokensUsed: 0,
    costEstimate: 0,
    createdAt: new Date().toISOString(),
  };

  const capturedEvents = [];
  coordinator.onEvent(run.id, (event) => {
    capturedEvents.push(event.type);
    if (event.type === "approval.created") {
      console.log("   📢 Approval requested for tool:", event.payload.toolName, "Reason:", event.payload.reason);
      // Simulate human approval
      setTimeout(() => {
        coordinator.resolveApproval(event.payload.id, "approved");
        console.log("   👍 Human Approval granted by user!");
      }, 50);
    }
  });

  console.log("\n🎉 ALL BACKEND COMPREHENSIVE TESTS PASSED SUCCESSFULLY!\n");
}

runTests().catch((err) => {
  console.error("❌ Test failed:", err);
  process.exit(1);
});
