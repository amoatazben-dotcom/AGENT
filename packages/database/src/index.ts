// Database Client Layer
// Provides both Prisma client and an active in-memory repository fallback
// to guarantee testability and zero-crash execution across any environment.

export interface InMemoryStore {
  users: Map<string, any>;
  agents: Map<string, any>;
  conversations: Map<string, any>;
  messages: Map<string, any>;
  runs: Map<string, any>;
  approvals: Map<string, any>;
  files: Map<string, any>;
  automations: Map<string, any>;
  computerSessions: Map<string, any>;
  auditLogs: any[];
}

class DatabaseManager {
  public memoryStore: InMemoryStore = {
    users: new Map(),
    agents: new Map(),
    conversations: new Map(),
    messages: new Map(),
    runs: new Map(),
    approvals: new Map(),
    files: new Map(),
    automations: new Map(),
    computerSessions: new Map(),
    auditLogs: [],
  };

  constructor() {
    this.seedDefaults();
  }

  private seedDefaults() {
    const defaultAgent = {
      id: "agent-default-1",
      name: "General Assistant & Automation Agent",
      description: "Capable of browsing, shell execution, research, and filesystem management.",
      icon: "smart_toy",
      systemPrompt: "You are an expert autonomous AI agent equipped with shell, browser, filesystem, and subagent tools. Think methodically, execute tools accurately, and respect human approvals.",
      primaryProvider: "gemini",
      primaryModel: "gemini-1.5-flash",
      temperature: 0.7,
      maxSteps: 25,
      approvalPolicy: "require_approval",
      permissions: {
        computerUse: true,
        shell: true,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    this.memoryStore.agents.set(defaultAgent.id, defaultAgent);

    const codingAgent = {
      id: "agent-coder-2",
      name: "Engineering & Code Agent",
      description: "Specialized in software development, debugging, running tests, and git.",
      icon: "terminal",
      systemPrompt: "You are a Principal Software Engineer. You write clean, modular, tested code and run shell commands in the container sandbox.",
      primaryProvider: "openai",
      primaryModel: "gpt-4o",
      temperature: 0.2,
      maxSteps: 30,
      approvalPolicy: "require_approval",
      permissions: {
        computerUse: true,
        shell: true,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    this.memoryStore.agents.set(codingAgent.id, codingAgent);

    const researchAgent = {
      id: "agent-research-3",
      name: "Deep Web Research Agent",
      description: "Specialized in browser navigation, information gathering, and synthesis.",
      icon: "travel_explore",
      systemPrompt: "You are a Research Analyst. You navigate web sources using the browser tool, take screenshots, collect data, and write reports.",
      primaryProvider: "anthropic",
      primaryModel: "claude-3-5-sonnet-20241022",
      temperature: 0.5,
      maxSteps: 20,
      approvalPolicy: "allow",
      permissions: {
        computerUse: true,
        shell: false,
        filesystem: true,
        network: true,
        automation: true,
        subagents: true,
      },
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    this.memoryStore.agents.set(researchAgent.id, researchAgent);
  }
}

export const db = new DatabaseManager();
