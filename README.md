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
│   ├── database/         # Typed In-Memory & Prisma DB Layer
│   ├── protocol/         # Shared Schemas & Types
│   ├── security/         # AES-256 Encryption & Scrypt Security
│   └── tool-sdk/         # Tool Execution Engine
└── services/
    └── computer-worker/  # Headless Browser Automation Service
```

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
