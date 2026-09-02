# ADR-001: Monorepo Architecture & Workspace Boundaries

## Status
Accepted

## Context
The AgentForge platform requires deep coordination between an Android native mobile application, a high-throughput API gateway, an autonomous agent execution runtime, an isolated computer container pool, and shared protocol definitions.

## Decision
Organize the platform into a unified Monorepo:
- `app/` (or `apps/android/`): Native Android Kotlin + Jetpack Compose application.
- `apps/gateway/`: Fastify TypeScript API Gateway providing REST & WebSocket endpoints.
- `apps/runtime/`: Autonomous Agent execution host and coordinator.
- `packages/protocol/`: Shared Zod schemas, TypeScript contracts, and WebSocket event types.
- `packages/agent-core/`: Agent loop, state machine, memory, and subagents.
- `packages/ai-router/`: Multi-model LLM abstraction (OpenAI, Gemini, Claude, Grok, Local).
- `packages/tool-sdk/`: Unified tool execution and security policy validation.
- `packages/database/`: PostgreSQL Prisma data schemas and migrations.
- `packages/security/`: Cryptographic hashing, AES-256-GCM secret encryption, and log redaction.
- `services/computer-worker/`: Docker-isolated browser and shell worker service.
- `infra/`: Docker Compose and Nginx configuration.

## Consequences
- Guaranteed contract synchronization between server and mobile client.
- Strict isolation of untrusted tool execution inside Docker sandboxes.
- Seamless developer setup with unified environment configuration.
