import {
  Agent,
  Run,
  RunStatus,
  ToolCall,
  Approval,
  ApprovalPolicy,
  ToolRiskLevel,
  WsEventEnvelope,
} from "@agentforge/protocol";
import { AIRouter, CanonicalMessage } from "@agentforge/ai-router";
import { ToolRegistry, AgentTool, ToolContext } from "@agentforge/tool-sdk";
import { db } from "@agentforge/database";
import * as crypto from "crypto";

export type EventHandler = (event: WsEventEnvelope) => void;

export interface CoordinatorConfig {
  aiRouter: AIRouter;
  toolRegistry: ToolRegistry;
  workspaceBaseDir: string;
}

export class AgentCoordinator {
  private aiRouter: AIRouter;
  private toolRegistry: ToolRegistry;
  private workspaceBaseDir: string;
  private eventHandlers: Map<string, Set<EventHandler>> = new Map();
  private pendingApprovals: Map<string, { resolve: (decision: "approved" | "rejected") => void }> = new Map();
  private activeRuns: Map<string, { abortController: AbortController; run: Run }> = new Map();

  constructor(config: CoordinatorConfig) {
    this.aiRouter = config.aiRouter;
    this.toolRegistry = config.toolRegistry;
    this.workspaceBaseDir = config.workspaceBaseDir;
  }

  onEvent(runId: string, handler: EventHandler) {
    if (!this.eventHandlers.has(runId)) {
      this.eventHandlers.set(runId, new Set());
    }
    this.eventHandlers.get(runId)!.add(handler);
  }

  removeEventHandler(runId: string, handler: EventHandler) {
    this.eventHandlers.get(runId)?.delete(handler);
  }

  private emit(event: WsEventEnvelope) {
    if (event.runId && this.eventHandlers.has(event.runId)) {
      for (const h of this.eventHandlers.get(event.runId)!) {
        try {
          h(event);
        } catch (err) {
          console.error("Event handler error:", err);
        }
      }
    }
  }

  // ==========================================
  // Policy Engine
  // ==========================================
  private evaluatePolicy(
    agent: Agent,
    tool: AgentTool,
    args: Record<string, any>
  ): "allow" | "deny" | "require_approval" {
    // Check Agent permissions first
    if (tool.name.startsWith("shell") && !agent.permissions.shell) return "deny";
    if (tool.name.startsWith("filesystem") && !agent.permissions.filesystem) return "deny";
    if (tool.name.startsWith("browser") && !agent.permissions.computerUse) return "deny";
    if (tool.name.startsWith("subagent") && !agent.permissions.subagents) return "deny";

    // Critical/High risk always requires approval unless policy is explicitly 'allow'
    if (agent.approvalPolicy === "allow") return "allow";
    if (agent.approvalPolicy === "deny" && (tool.riskLevel === "high" || tool.riskLevel === "critical")) {
      return "deny";
    }

    // High and Critical risk operations default to approval
    if (tool.riskLevel === "critical" || tool.riskLevel === "high") {
      return "require_approval";
    }

    // Specific destructive actions (e.g. file deletion, sudo commands)
    if (tool.name === "filesystem.delete") return "require_approval";
    if (tool.name === "shell.execute" && (args.command?.includes("rm -rf") || args.command?.includes("sudo"))) {
      return "require_approval";
    }

    return "allow";
  }

  // ==========================================
  // Run Execution Loop
  // ==========================================
  async executeRun(run: Run, agent: Agent, conversationHistory: CanonicalMessage[]): Promise<string> {
    const abortController = new AbortController();
    this.activeRuns.set(run.id, { abortController, run });

    let seq = 0;
    const sendEvent = (type: any, payload: any) => {
      seq++;
      const envelope: WsEventEnvelope = {
        eventId: crypto.randomUUID(),
        seq,
        type,
        timestamp: new Date().toISOString(),
        runId: run.id,
        conversationId: run.conversationId,
        payload,
      };
      this.emit(envelope);
    };

    try {
      run.status = "running";
      run.startedAt = new Date().toISOString();
      db.memoryStore.runs.set(run.id, run);
      sendEvent("run.started", { runId: run.id, agentName: agent.name });

      const messages: CanonicalMessage[] = [
        { role: "system", content: agent.systemPrompt },
        ...conversationHistory,
      ];

      const tools = this.toolRegistry.list().map((t) => ({
        name: t.name,
        description: t.description,
        parameters: (t.inputSchema as any)._def ? (t.inputSchema as any)._def : {},
      }));

      let finalContent = "";

      while (run.currentStep < run.maxSteps && !abortController.signal.aborted) {
        run.currentStep++;
        sendEvent("run.status", {
          step: run.currentStep,
          maxSteps: run.maxSteps,
          status: "running",
        });

        // Call AI Router with failover
        let stepText = "";
        const toolCallsToExecute: Array<{ id: string; name: string; args: Record<string, any> }> = [];

        for await (const event of this.aiRouter.chatWithFailover(
          {
            model: agent.primaryModel,
            messages,
            tools,
            temperature: agent.temperature,
          },
          agent.primaryProvider,
          agent.fallbackProvider,
          agent.fallbackModel
        )) {
          if (event.type === "delta") {
            stepText += event.content;
            sendEvent("assistant.delta", { delta: event.content });
          } else if (event.type === "tool_call") {
            toolCallsToExecute.push({
              id: event.id,
              name: event.name,
              args: event.arguments,
            });
          }
        }

        if (stepText) {
          messages.push({ role: "assistant", content: stepText });
          finalContent += stepText;
        }

        // If no tool calls were requested, the agent completed its response
        if (toolCallsToExecute.length === 0) {
          break;
        }

        // Execute tool calls
        for (const tc of toolCallsToExecute) {
          const tool = this.toolRegistry.get(tc.name);
          if (!tool) {
            messages.push({
              role: "tool",
              toolCallId: tc.id,
              name: tc.name,
              content: JSON.stringify({ error: `Tool ${tc.name} is not available.` }),
            });
            continue;
          }

          const policyDecision = this.evaluatePolicy(agent, tool, tc.args);

          if (policyDecision === "deny") {
            sendEvent("tool.failed", { tool: tc.name, error: "Operation denied by security policy." });
            messages.push({
              role: "tool",
              toolCallId: tc.id,
              name: tc.name,
              content: JSON.stringify({ error: "Access Denied: Action forbidden by agent policy." }),
            });
            continue;
          }

          if (policyDecision === "require_approval") {
            run.status = "waiting_for_approval";
            db.memoryStore.runs.set(run.id, run);

            const approvalId = crypto.randomUUID();
            const approval: Approval = {
              id: approvalId,
              runId: run.id,
              conversationId: run.conversationId,
              toolName: tc.name,
              arguments: tc.args,
              reason: `Agent requests permission to execute ${tc.name} with risk level: ${tool.riskLevel}`,
              riskLevel: tool.riskLevel,
              status: "pending",
              createdAt: new Date().toISOString(),
            };
            db.memoryStore.approvals.set(approvalId, approval);

            sendEvent("approval.created", approval);

            // Wait for user approval decision from WebSocket or REST
            const userDecision = await new Promise<"approved" | "rejected">((resolve) => {
              this.pendingApprovals.set(approvalId, { resolve });
            });

            approval.status = userDecision === "approved" ? "approved" : "rejected";
            approval.decisionAt = new Date().toISOString();
            db.memoryStore.approvals.set(approvalId, approval);
            sendEvent("approval.resolved", approval);

            if (userDecision === "rejected") {
              messages.push({
                role: "tool",
                toolCallId: tc.id,
                name: tc.name,
                content: JSON.stringify({ error: "Operation rejected by user." }),
              });
              continue;
            }

            run.status = "running";
            db.memoryStore.runs.set(run.id, run);
          }

          // Execute Tool
          sendEvent("tool.started", { tool: tc.name, args: tc.args });
          const toolContext: ToolContext = {
            runId: run.id,
            conversationId: run.conversationId,
            agentId: agent.id,
            workspaceDir: `${this.workspaceBaseDir}/${run.id}`,
            abortSignal: abortController.signal,
            onProgress: (progress) => sendEvent("tool.progress", { tool: tc.name, progress }),
            onComputerFrame: (base64, url) => {
              sendEvent("computer.frame", { base64, url });
            },
            spawnSubagent: async (task, model) => {
              sendEvent("subagent.started", { task, model });
              const subResult = `Subagent completed task: ${task}`;
              sendEvent("subagent.completed", { task, result: subResult });
              return subResult;
            },
          };

          const startTime = Date.now();
          const result = await tool.execute(toolContext, tc.args);
          const duration = Date.now() - startTime;

          if (result.success) {
            sendEvent("tool.completed", { tool: tc.name, duration, result: result.data });
            messages.push({
              role: "tool",
              toolCallId: tc.id,
              name: tc.name,
              content: JSON.stringify(result.data ?? "Success"),
            });
          } else {
            sendEvent("tool.failed", { tool: tc.name, duration, error: result.error });
            messages.push({
              role: "tool",
              toolCallId: tc.id,
              name: tc.name,
              content: JSON.stringify({ error: result.error }),
            });
          }
        }
      }

      run.status = "completed";
      run.completedAt = new Date().toISOString();
      db.memoryStore.runs.set(run.id, run);
      sendEvent("run.completed", { finalContent });

      return finalContent;
    } catch (err: any) {
      run.status = "failed";
      run.error = err.message;
      run.completedAt = new Date().toISOString();
      db.memoryStore.runs.set(run.id, run);
      sendEvent("run.failed", { error: err.message });
      throw err;
    } finally {
      this.activeRuns.delete(run.id);
    }
  }

  resolveApproval(approvalId: string, decision: "approved" | "rejected") {
    const pending = this.pendingApprovals.get(approvalId);
    if (pending) {
      pending.resolve(decision);
      this.pendingApprovals.delete(approvalId);
      return true;
    }
    return false;
  }

  cancelRun(runId: string) {
    const active = this.activeRuns.get(runId);
    if (active) {
      active.abortController.abort();
      active.run.status = "cancelled";
      db.memoryStore.runs.set(runId, active.run);
      this.emit({
        eventId: crypto.randomUUID(),
        seq: 999,
        type: "run.status",
        timestamp: new Date().toISOString(),
        runId,
        payload: { status: "cancelled" },
      });
      return true;
    }
    return false;
  }
}
