# AgentForge — Production-Ready AI Agent Platform & Android App

**تطوير وتصميم:** معتز العلقمي (Moataz Al-Alqami) — اليمن، تعز (Taiz, Yemen)  
**حقوق الملكية الفكرية:** جميع الحقوق محفوظة © 2025 معتز العلقمي  
**الترخيص:** MIT License

---

## 📱 تطبيق أندرويد (Android Application)

تطبيق متوافق مع نظام **Android 8.0 Oreo (API 26)** وما فوق، مخصص للمعالجات ذات بنية **64-bit** (arm64-v8a, x86_64).

### مواصفات حزمة الأندرويد (APK Requirements & Targets)
- **Minimum SDK:** API 26 (Android 8.0 Oreo)
- **Target SDK:** API 36 (Android 15+)
- **Architectures:** 64-bit strictly (`arm64-v8a`, `x86_64`)
- **Icon:** أيقونة احترافية مصممة خصيصاً للتطبيق بحجم وتنسيقات `Adaptive Icons` متوافقة مع جميع شاشات أندرويد.
- **Security:** تفعيل التشفير وحظر Cleartext HTTP عبر `network_security_config.xml`.

---

## 🛠️ بناء حزم APK64-bit (Build Workspace)

لإنتاج حزمة التطبيق APK 64-bit من بيئة العمل:

```bash
# 1. الانخراط في مجلد التطبيق
cd app

# 2. بناء نسخة الإصدار المفتوحة (Release 64-bit APKs)
./gradlew assembleRelease
```

ستجد الحزم الناتجة في المجلد:
`app/build/outputs/apk/release/`

وتشمل:
- `app-arm64-v8a-release.apk` (لأجهزة الهواتف الذكية 64-bit)
- `app-x86_64-release.apk` (للمحاكيات والأجهزة الحديثة 64-bit)
- `app-universal-release.apk` (حزمة شاملة لجميع معالجات 64-bit)

---

## 🏗️ البنية العامة للمشروع (Monorepo Architecture)

```
AGENT/
├── app/                  # تطبيق الأندرويد (Kotlin + Jetpack Compose) — معتز العلقمي
│   ├── src/main/res/     # الأيقونات (ic_launcher) والموارد
│   └── build.gradle.kts  # إعدادات البناء لـ 64-bit و Android 8+
├── apps/
│   └── gateway/          # Fastify API Server & WebSocket Hub
├── packages/
│   ├── agent-core/       # AgentCoordinator Engine
│   ├── ai-router/        # Multi-provider Router (Gemini, OpenAI, Claude, Grok)
│   ├── database/         # Persistent SQLite store (local-first) + Prisma schema (optional PG)
│   ├── protocol/         # Shared Schemas & Types (incl. provider configs)
│   ├── security/         # AES-256 Encryption & Scrypt Security
│   └── tool-sdk/         # Tool Execution Engine
└── services/
    └── computer-worker/  # Headless Browser Automation Service
```

---

## 🚀 Local Quick Start (no cloud / DB server required)

AgentForge is **local-first**: the gateway persists to a local SQLite file
(`agentforge.db`) and the Android app works against its own Room database plus
your AI provider key. No PostgreSQL, no Redis, no cloud account needed.

```bash
# 1. Install (Node 22+ recommended; Node 24 works, uses built-in node:sqlite)
pnpm install

# 2. Build all TypeScript packages
pnpm build

# 3. Run unit tests + local end-to-end (mock upstream, test-only)
pnpm test
pnpm test:e2e

# 4. Start the gateway (uses ./data/agentforge.db by default)
pnpm start:gateway
# health:  curl http://localhost:3000/health
# ready:   curl http://localhost:3000/ready
```

Environment (optional, see `.env.example`):

```bash
cp .env.example .env
# MASTER_ENCRYPTION_KEY (required in production, encrypts provider API keys):
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

### 📱 Android connection

1. Install the app, open **Settings → AI Providers → Add Provider**.
2. Pick a type (or `openai_compatible` / `custom`), enter **Base URL + API Key + Model**.
3. Press **Test Connection** (real check: auth, latency, `/models` discovery).
4. Create an **Agent** using the saved provider, open **Chat**, send a message.
   - Gateway reachable → server run with tools/approvals, streamed over WebSocket.
   - Gateway offline → **Local Workspace**: the device streams directly from the provider; everything is saved in Room and survives restarts.

### 🤖 Provider examples (Base URL + your own key, keys never ship in the repo)

| Provider   | Base URL                           | Notes                                  |
|------------|------------------------------------|----------------------------------------|
| OpenAI     | `https://api.openai.com/v1`        | preset                                 |
| OpenRouter | `https://openrouter.ai/api/v1`     | any model ID, e.g. `deepseek-chat`     |
| Groq       | `https://api.groq.com/openai/v1`   | preset                                 |
| DeepSeek   | `https://api.deepseek.com/v1`      | preset                                 |
| xAI        | `https://api.x.ai/v1`              | preset                                 |
| Ollama     | `http://localhost:11434/v1`        | local; on emulator use `10.0.2.2`      |
| LM Studio  | `http://localhost:1234/v1`         | local OpenAI-compatible server         |
| Custom     | your server `/v1`                  | any OpenAI-compatible endpoint         |

> If a provider has no `/models` endpoint you can type the Model ID manually.
> Unencrypted `http://` outside localhost shows a warning in the app.

---

## 🔐 إعداد المتغيرات والتوليد (Environment Setup)

قم بنسخ ملف `.env.example` إلى `.env` وتحديث المفاتيح:

```bash
cp .env.example .env
```

توليد مفاتيح الأمان الحساسة:

```bash
# MASTER_ENCRYPTION_KEY (64 hex characters)
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

# JWT_SECRET
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

---

## 📜 حقوق الملكية والتطوير (Copyright & License)

هذا العمل مصمم ومطور بواسطة **معتز العلقمي** (تعز، اليمن).  
جميع حقوق النشر محفوظة © 2025.

**Developer:** Moataz Al-Alqami  
**Location:** Taiz, Yemen (تعز، اليمن)  
**Contact / Repository:** https://github.com/amoatazben-dotcom/AGENT
