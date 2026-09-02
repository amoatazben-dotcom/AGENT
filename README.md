# AgentForge — Production-Ready AI Agent Platform

A full-stack monorepo for running autonomous AI agents with browser control, shell execution, filesystem access, and human-in-the-loop approvals.

## Architecture

```
AGENT/
├── apps/
│   └── gateway/          # Fastify API server & WebSocket hub
├── packages/
│   ├── agent-core/       # AgentCoordinator — run loop & policy engine
│   ├── ai-router/        # Multi-provider AI router (Gemini, OpenAI, Claude, Grok, Local)
│   ├── database/         # Typed in-memory store (swap for Prisma/PostgreSQL)
│   ├── protocol/         # Shared Zod schemas & TypeScript types
│   ├── security/         # Encryption, password hashing, token generation
│   └── tool-sdk/         # Tool registry & execution framework
├── services/
│   └── computer-worker/  # Browser/computer action microservice
└── app/                  # Android (Kotlin + Compose) client
```

## Quick Start

### Prerequisites
- Node.js ≥ 20
- pnpm ≥ 9
- (Optional) PostgreSQL + Redis for production persistence

### Setup

```bash
# 1. Clone the repository
git clone https://github.com/amoatazben-dotcom/AGENT.git
cd AGENT

# 2. Install dependencies
pnpm install

# 3. Configure environment
cp .env.example .env
# Edit .env and fill in your API keys
```

### Generate required secrets

```bash
# MASTER_ENCRYPTION_KEY (64 hex chars = 32 bytes)
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

# JWT_SECRET
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"

# JWT_REFRESH_SECRET
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

### Run in development

```bash
# Start all services in parallel
pnpm dev

# Or start just the gateway
pnpm --filter=@agentforge/gateway dev
```

The API will be available at `http://localhost:3000`.

## API Overview

All endpoints except `/health`, `/ready`, and `/api/v1/auth/*` require an `Authorization: Bearer <token>` header.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/register` | Register a new user |
| POST | `/api/v1/auth/login` | Login and receive a session token |
| POST | `/api/v1/auth/logout` | Invalidate the current token |
| GET | `/api/v1/agents` | List all agents |
| POST | `/api/v1/agents` | Create a new agent |
| POST | `/api/v1/messages` | Send a message and start a run |
| GET | `/api/v1/approvals` | List pending approvals |
| POST | `/api/v1/approvals/:id/resolve` | Approve or reject a tool call |
| GET | `/ws?token=<token>` | WebSocket for real-time run events |

## Environment Variables

See [`.env.example`](.env.example) for a full list with generation instructions.

| Variable | Required | Description |
|----------|----------|-------------|
| `GEMINI_API_KEY` | Yes | Google AI Studio key |
| `OPENAI_API_KEY` | No | OpenAI key (for GPT-4o agents) |
| `ANTHROPIC_API_KEY` | No | Anthropic key (for Claude agents) |
| `MASTER_ENCRYPTION_KEY` | **Yes in production** | 32-byte hex key for secret encryption |
| `CORS_ORIGINS` | No | Comma-separated list of allowed frontend origins |
| `DATABASE_URL` | No | PostgreSQL URL (in-memory store used if unset) |

## Building

```bash
# Build all TypeScript packages (except Android)
pnpm build

# Build Android app
cd app && ./gradlew assembleRelease
```

## License

MIT
