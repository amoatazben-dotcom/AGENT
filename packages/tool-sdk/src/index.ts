import { z, ZodSchema } from "zod";
import * as fs from "fs/promises";
import * as path from "path";
import { exec } from "child_process";
import { promisify } from "util";
import { ToolRiskLevel } from "@agentforge/protocol";

const execAsync = promisify(exec);

export interface ToolContext {
  runId: string;
  conversationId: string;
  agentId: string;
  workspaceDir: string;
  abortSignal?: AbortSignal;
  onProgress?: (progress: string) => void;
  onComputerFrame?: (base64: string, url: string) => void;
  spawnSubagent?: (task: string, model?: string) => Promise<string>;
}

export interface ToolResult {
  success: boolean;
  data?: any;
  error?: string;
  output?: string;
}

export interface AgentTool<TInput = any> {
  name: string;
  description: string;
  riskLevel: ToolRiskLevel;
  inputSchema: ZodSchema<TInput>;
  execute(context: ToolContext, input: TInput): Promise<ToolResult>;
}

// Security: Sandbox path traversal mitigation
function resolveSandboxPath(baseDir: string, userPath: string): string {
  const safeBase = path.resolve(baseDir);
  const resolved = path.resolve(safeBase, userPath.replace(/^(\.\.(\/|\\|$))+/, ""));
  if (!resolved.startsWith(safeBase)) {
    throw new Error(`Security Violation: Path traversal outside workspace boundary is forbidden (${userPath})`);
  }
  return resolved;
}

// ==========================================
// Filesystem Tools
// ==========================================
export const FileListTool: AgentTool<{ directoryPath?: string }> = {
  name: "filesystem.list",
  description: "List files and subdirectories inside the workspace directory.",
  riskLevel: "low",
  inputSchema: z.object({
    directoryPath: z.string().default("."),
  }),
  async execute(ctx, input) {
    try {
      const target = resolveSandboxPath(ctx.workspaceDir, input.directoryPath || ".");
      await fs.mkdir(target, { recursive: true });
      const entries = await fs.readdir(target, { withFileTypes: true });
      const list = entries.map((e) => ({
        name: e.name,
        isDirectory: e.isDirectory(),
        isFile: e.isFile(),
      }));
      return { success: true, data: list };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

export const FileReadTool: AgentTool<{ filePath: string }> = {
  name: "filesystem.read",
  description: "Read the full text content of a file within the workspace.",
  riskLevel: "low",
  inputSchema: z.object({
    filePath: z.string(),
  }),
  async execute(ctx, input) {
    try {
      const target = resolveSandboxPath(ctx.workspaceDir, input.filePath);
      const content = await fs.readFile(target, "utf8");
      return { success: true, data: content };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

export const FileWriteTool: AgentTool<{ filePath: string; content: string }> = {
  name: "filesystem.write",
  description: "Write content to a file. Creates parent directories if needed.",
  riskLevel: "medium",
  inputSchema: z.object({
    filePath: z.string(),
    content: z.string(),
  }),
  async execute(ctx, input) {
    try {
      const target = resolveSandboxPath(ctx.workspaceDir, input.filePath);
      await fs.mkdir(path.dirname(target), { recursive: true });
      await fs.writeFile(target, input.content, "utf8");
      return { success: true, data: { path: input.filePath, bytesWritten: input.content.length } };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

export const FileDeleteTool: AgentTool<{ filePath: string }> = {
  name: "filesystem.delete",
  description: "Permanently delete a file or directory inside the workspace. Requires human approval.",
  riskLevel: "high",
  inputSchema: z.object({
    filePath: z.string(),
  }),
  async execute(ctx, input) {
    try {
      const target = resolveSandboxPath(ctx.workspaceDir, input.filePath);
      await fs.rm(target, { recursive: true, force: true });
      return { success: true, data: { deleted: input.filePath } };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

// ==========================================
// Shell Tool
// ==========================================
export const ShellExecuteTool: AgentTool<{ command: string; cwd?: string; timeoutMs?: number }> = {
  name: "shell.execute",
  description: "Execute a command in the isolated shell environment with a strict timeout.",
  riskLevel: "high",
  inputSchema: z.object({
    command: z.string(),
    cwd: z.string().optional().default("."),
    timeoutMs: z.number().int().min(1000).max(120000).default(30000),
  }),
  async execute(ctx, input) {
    try {
      const workingDir = resolveSandboxPath(ctx.workspaceDir, input.cwd || ".");
      const { stdout, stderr } = await execAsync(input.command, {
        cwd: workingDir,
        timeout: input.timeoutMs,
        maxBuffer: 5 * 1024 * 1024,
      });
      return {
        success: true,
        data: {
          stdout: stdout.trim(),
          stderr: stderr.trim(),
        },
      };
    } catch (err: any) {
      return {
        success: false,
        error: err.message,
        data: {
          stdout: err.stdout?.trim() || "",
          stderr: err.stderr?.trim() || "",
          exitCode: err.code,
        },
      };
    }
  },
};

// ==========================================
// HTTP Request Tool
// ==========================================
export const HttpRequestTool: AgentTool<{
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  headers?: Record<string, string>;
  body?: string;
}> = {
  name: "http.request",
  description: "Perform an external HTTP/REST request to APIs or websites.",
  riskLevel: "medium",
  inputSchema: z.object({
    url: z.string().url(),
    method: z.enum(["GET", "POST", "PUT", "DELETE"]).default("GET"),
    headers: z.record(z.string()).optional(),
    body: z.string().optional(),
  }),
  async execute(_, input) {
    try {
      // Basic SSRF mitigation: disallow AWS / GCP metadata endpoints
      const parsedUrl = new URL(input.url);
      if (
        parsedUrl.hostname === "169.254.169.254" ||
        parsedUrl.hostname === "metadata.google.internal"
      ) {
        throw new Error("Access to cloud metadata service is forbidden (SSRF defense).");
      }

      const res = await fetch(input.url, {
        method: input.method || "GET",
        headers: input.headers,
        body: input.body,
      });

      const text = await res.text();
      let json = null;
      try {
        json = JSON.parse(text);
      } catch {}

      return {
        success: res.ok,
        data: {
          status: res.status,
          statusText: res.statusText,
          headers: Object.fromEntries(res.headers.entries()),
          body: json || text.slice(0, 10000),
        },
      };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

// ==========================================
// Browser & Remote Computer Tools
// ==========================================
export const BrowserNavigateTool: AgentTool<{ url: string }> = {
  name: "browser.navigate",
  description: "Open and navigate Chromium browser to a specific URL in the container.",
  riskLevel: "medium",
  inputSchema: z.object({
    url: z.string().url(),
  }),
  async execute(ctx, input) {
    try {
      // Call computer-worker service (real browser). No fake fallback:
      // if the worker is down we report unavailability honestly.
      const workerUrl = process.env.COMPUTER_WORKER_URL || "http://localhost:3002";
      const res = await fetch(`${workerUrl}/api/browser/navigate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: ctx.runId, url: input.url }),
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        return {
          success: false,
          error: `Computer worker unavailable (HTTP ${res.status}). ${text.slice(0, 300)}`,
        };
      }

      const data = await res.json();
      return { success: true, data };
    } catch (err: any) {
      return { success: false, error: `Computer worker unavailable: ${err.message}` };
    }
  },
};

export const BrowserScreenshotTool: AgentTool<{ fullPage?: boolean }> = {
  name: "browser.screenshot",
  description: "Capture a screenshot of the active browser/computer window.",
  riskLevel: "low",
  inputSchema: z.object({
    fullPage: z.boolean().default(false),
  }),
  async execute(ctx, input) {
    try {
      const workerUrl = process.env.COMPUTER_WORKER_URL || "http://localhost:3002";
      const res = await fetch(`${workerUrl}/api/browser/screenshot`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: ctx.runId, fullPage: input.fullPage }),
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        return { success: false, error: `Computer worker unavailable (HTTP ${res.status}). ${text.slice(0, 300)}` };
      }

      const data = await res.json();
      return { success: true, data };
    } catch (err: any) {
      return { success: false, error: `Computer worker unavailable: ${err.message}` };
    }
  },
};

export const ComputerStartTool: AgentTool<{ timeoutMinutes?: number }> = {
  name: "computer.start",
  description: "Initialize a browser sandbox session for this run via the computer-worker service.",
  riskLevel: "medium",
  inputSchema: z.object({
    timeoutMinutes: z.number().int().min(1).max(60).default(15),
  }),
  async execute(ctx) {
    try {
      const workerUrl = process.env.COMPUTER_WORKER_URL || "http://localhost:3002";
      const res = await fetch(`${workerUrl}/api/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ runId: ctx.runId }),
      });
      if (!res.ok) {
        return { success: false, error: `Computer worker unavailable (HTTP ${res.status}).` };
      }
      const data = await res.json();
      return { success: true, data };
    } catch (err: any) {
      return { success: false, error: `Computer worker unavailable: ${err.message}` };
    }
  },
};

export const SubagentSpawnTool: AgentTool<{ task: string; model?: string }> = {
  name: "subagent.spawn",
  description: "Spawn a dedicated subagent to perform an autonomous sub-task in parallel.",
  riskLevel: "medium",
  inputSchema: z.object({
    task: z.string(),
    model: z.string().optional(),
  }),
  async execute(ctx, input) {
    if (!ctx.spawnSubagent) {
      return { success: false, error: "Subagent spawning is not configured in this coordinator." };
    }
    try {
      const result = await ctx.spawnSubagent(input.task, input.model);
      return { success: true, data: { result } };
    } catch (err: any) {
      return { success: false, error: err.message };
    }
  },
};

// ==========================================
// Tool Registry
// ==========================================
export class ToolRegistry {
  private tools = new Map<string, AgentTool>();

  constructor() {
    this.register(FileListTool);
    this.register(FileReadTool);
    this.register(FileWriteTool);
    this.register(FileDeleteTool);
    this.register(ShellExecuteTool);
    this.register(HttpRequestTool);
    this.register(BrowserNavigateTool);
    this.register(BrowserScreenshotTool);
    this.register(ComputerStartTool);
    this.register(SubagentSpawnTool);
  }

  register(tool: AgentTool) {
    this.tools.set(tool.name, tool);
  }

  get(name: string): AgentTool | undefined {
    return this.tools.get(name);
  }

  list(): AgentTool[] {
    return Array.from(this.tools.values());
  }
}
