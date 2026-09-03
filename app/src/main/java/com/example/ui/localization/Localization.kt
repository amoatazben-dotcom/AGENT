package com.example.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.LayoutDirection

enum class AppLanguage(val code: String, val displayName: String, val layoutDirection: LayoutDirection) {
    ARABIC("ar", "العربية", LayoutDirection.Rtl),
    ENGLISH("en", "English", LayoutDirection.Ltr)
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.ARABIC }
val LocalStrings = compositionLocalOf<AppStrings> { StringsArabic }

interface AppStrings {
    // Navigation
    val navChat: String
    val navAgents: String
    val navComputer: String
    val navAutomations: String
    val navApprovals: String
    val navFiles: String
    val navSettings: String

    // App Bar & Identity
    val appTitle: String
    val appSubtitle: String
    val offlineLocalBadge: String
    val onlineWsBadge: String
    val connectingBadge: String
    val switchLanguage: String

    // Chat Screen
    val chatPlaceholder: String
    val sendButton: String
    val agentWorking: String
    val quickPrompt1: String
    val quickPrompt2: String
    val quickPrompt3: String
    val quickPrompt4: String
    val viewLogs: String
    val noMessagesTitle: String
    val noMessagesSubtitle: String
    val toolCompleted: String

    // Agents Screen
    val agentsTitle: String
    val agentsSubtitle: String
    val createAgent: String
    val agentStatusActive: String
    val agentStatusIdle: String
    val capabilities: String
    val temperature: String
    val contextWindow: String

    // Computer Screen
    val computerTitle: String
    val computerSubtitle: String
    val sandboxUrl: String
    val navigateAction: String
    val takeControl: String
    val releaseControl: String
    val terminalOutput: String
    val screenResolution: String

    // Automations Screen
    val automationsTitle: String
    val automationsSubtitle: String
    val createAutomation: String
    val cronSchedule: String
    val cronExpression: String
    val lastRun: String
    val runNow: String
    val runAutomationNow: String
    val statusActive: String
    val statusPaused: String

    // Approvals Screen
    val approvalsTitle: String
    val approvalsSubtitle: String
    val pendingAction: String
    val approveButton: String
    val rejectButton: String
    val riskHigh: String
    val riskMedium: String
    val riskLow: String
    val reasonLabel: String
    val noApprovals: String
    val allSystemsOperational: String

    // Files Screen
    val filesTitle: String
    val filesSubtitle: String
    val searchFilesPlaceholder: String
    val filePreviewTitle: String
    val closePreview: String

    // Settings Screen
    val settingsTitle: String
    val settingsSubtitle: String
    val languageSection: String
    val languageSelection: String
    val arabic: String
    val english: String
    val gatewaySection: String
    val gatewayServer: String
    val saveGateway: String
    val apiKeys: String
    val sandboxGovernance: String
    val dockerIsolation: String
    val dockerIsolationDesc: String
    val redactSecrets: String
    val redactSecretsDesc: String
    val localEngineSection: String
    val localEngineDesc: String
    val localEngineActive: String
    val databaseManagement: String
    val databaseDesc: String
    val resetDatabase: String
    val confirmReset: String
    val clearDatabase: String
    val databaseCleared: String

    // Execution Logs
    val logsTitle: String
    val logsEntriesCount: String
    val logsFilterAll: String
    val noLogsTitle: String
    val noLogsDesc: String

    // AI Providers (real configuration)
    val providersTitle: String
    val providersSubtitle: String
    val addProvider: String
    val editProvider: String
    val deleteProvider: String
    val testConnection: String
    val saveProvider: String
    val providerName: String
    val providerType: String
    val baseUrl: String
    val apiKey: String
    val defaultModel: String
    val setDefault: String
    val isDefault: String
    val enabled: String
    val refreshModels: String
    val noProvidersTitle: String
    val noProvidersSubtitle: String
    val configureProvider: String
    val connectionStatus: String
    val httpWarning: String
    val workspaceStatus: String
    val resetConversations: String
    val resetExecution: String
    val resetCache: String
    val factoryReset: String
}

object StringsArabic : AppStrings {
    override val navChat = "المحادثة"
    override val navAgents = "الوكلاء"
    override val navComputer = "الحاسوب"
    override val navAutomations = "الأتمتة"
    override val navApprovals = "الموافقات"
    override val navFiles = "الملفات"
    override val navSettings = "الإعدادات"

    override val appTitle = "AgentForge"
    override val appSubtitle = "منصة الوكلاء الذاتية المستقلة"
    override val offlineLocalBadge = "محلي / نشط"
    override val onlineWsBadge = "متصل بالخادم"
    override val connectingBadge = "جارِ الاتصال..."
    override val switchLanguage = "تغيير اللغة"

    override val chatPlaceholder = "اكتب تعليمات للوكيل (تصفح، بحث، أتمتة، كود)..."
    override val sendButton = "إرسال"
    override val agentWorking = "الوكيل يحلل المهمة وينفذ الأدوات..."
    override val quickPrompt1 = "🌐 تصفح موقع واجمع معلومات"
    override val quickPrompt2 = "📊 مقارنة مكتبات الذكاء الاصطناعي"
    override val quickPrompt3 = "🗑️ تجربة طلب موافقة لحذف ملف"
    override val quickPrompt4 = "💻 فحص بيئة الحاوية والحاسوب"
    override val viewLogs = "سجلات التنفيذ"
    override val noMessagesTitle = "جاهز لتنفيذ المهام الذاتية"
    override val noMessagesSubtitle = "اطلب من الوكيل التصفح، تشغيل الحاويات، فحص الأكواد، أو أتمتة سير العمل محلياً."
    override val toolCompleted = "اكتمل بنجاح"

    override val agentsTitle = "سجل الوكلاء"
    override val agentsSubtitle = "إدارة وتكوين الوكلاء الأذكياء المتخصصين"
    override val createAgent = "إنشاء وكيل جديد"
    override val agentStatusActive = "نشط"
    override val agentStatusIdle = "في الانتظار"
    override val capabilities = "القدرات"
    override val temperature = "درجة الإبداع"
    override val contextWindow = "نافذة السياق"

    override val computerTitle = "حاوية الحاسوب"
    override val computerSubtitle = "بيئة معزولة لتشغيل متصفح Chromium والطرفية"
    override val sandboxUrl = "عنوان URL للحاوية"
    override val navigateAction = "انتقال"
    override val takeControl = "التحكم اليدوي"
    override val releaseControl = "إرجاع التحكم للوكيل"
    override val terminalOutput = "مخرجات الطرفية والأوامر"
    override val screenResolution = "دقة العرض"

    override val automationsTitle = "الأتمتة والمهام"
    override val automationsSubtitle = "جدولة المهام الدورية وسير العمل التلقائي"
    override val createAutomation = "إنشاء مهمة مؤتمتة"
    override val cronSchedule = "جدول Cron"
    override val cronExpression = "تعبير Cron"
    override val lastRun = "آخر تشغيل"
    override val runNow = "تشغيل الآن"
    override val runAutomationNow = "تشغيل المهمة الآن"
    override val statusActive = "نشط"
    override val statusPaused = "متوقف مؤقتاً"

    override val approvalsTitle = "صندوق الموافقات"
    override val approvalsSubtitle = "التحكم البشري بالإجراءات الحساسة"
    override val pendingAction = "في انتظار موافقتك"
    override val approveButton = "موافقة واعتماد"
    override val rejectButton = "رفض الإجراء"
    override val riskHigh = "خطورة عالية"
    override val riskMedium = "متوسط"
    override val riskLow = "منخفض"
    override val reasonLabel = "السبب والمبرر"
    override val noApprovals = "لا توجد موافقات معلقة"
    override val allSystemsOperational = "جميع الإجراءات معتمدة ومنفذة بنجاح"

    override val filesTitle = "ملفات مساحة العمل"
    override val filesSubtitle = "التقارير المولدة والملفات والأكواد البرمجية"
    override val searchFilesPlaceholder = "ابحث بالاسم أو المسار..."
    override val filePreviewTitle = "معاينة الملف"
    override val closePreview = "إغلاق"

    override val settingsTitle = "إعدادات المنصة"
    override val settingsSubtitle = "اللغة، المحرك المحلي، مفاتيح الذكاء الاصطناعي والأمان"
    override val languageSection = "لغة الواجهة (Interface Language)"
    override val languageSelection = "اختيار لغة النظام"
    override val arabic = "العربية (RTL)"
    override val english = "English (LTR)"
    override val gatewaySection = "خادم البوابة والشبكة"
    override val gatewayServer = "اتصال خادم البوابة (Gateway)"
    override val saveGateway = "حفظ وإعادة الاتصال"
    override val apiKeys = "مفاتيح نماذج الذكاء الاصطناعي"
    override val sandboxGovernance = "أمان وحوكمة الحاويات"
    override val dockerIsolation = "فرض العزل الصارم في الحاويات"
    override val dockerIsolationDesc = "تشغيل الأدوات داخل بيئة معزولة تماماً"
    override val redactSecrets = "حجب المفاتيح والأسرار من السجلات"
    override val redactSecretsDesc = "إخفاء الرموز السرية وكلمات المرور من الخط الزمني"
    override val localEngineSection = "المحرك المحلي المستقل (Local Engine)"
    override val localEngineDesc = "يعمل النظام محلياً بنسبة 100% باستخدام قاعدة بيانات Room ومحاكي الوكلاء الذاتي بدون الحاجة لخادم خارجي."
    override val localEngineActive = "المحرك المحلي نشط ومفعل"
    override val databaseManagement = "إدارة قاعدة البيانات المحلية (Room SQLite)"
    override val databaseDesc = "يتم حفظ المحادثات، الوكلاء، سجلات التنفيذ والملفات محلياً على الجهاز بالكامل."
    override val resetDatabase = "إعادة ضبط قاعدة البيانات المحلية"
    override val confirmReset = "هل تريد مسح البيانات وإعادة تهيئة الوكلاء الافتراضيين؟"
    override val clearDatabase = "إعادة ضبط السجلات المحلية"
    override val databaseCleared = "تم تفريغ السجلات وإعادة التهيئة"

    override val logsTitle = "سجلات التنفيذ الفورية"
    override val logsEntriesCount = "سجل"
    override val logsFilterAll = "الكل"
    override val noLogsTitle = "لا توجد سجلات بعد"
    override val noLogsDesc = "ستظهر أحداث انتقال الوكيل واستدعاء الأدوات هنا فورياً."

    override val providersTitle = "مزودو الذكاء الاصطناعي"
    override val providersSubtitle = "أضف Base URL + API Key + Model ثم اختبر الاتصال"
    override val addProvider = "إضافة مزود"
    override val editProvider = "تعديل المزود"
    override val deleteProvider = "حذف المزود"
    override val testConnection = "اختبار الاتصال"
    override val saveProvider = "حفظ المزود"
    override val providerName = "اسم المزود"
    override val providerType = "نوع المزود"
    override val baseUrl = "Base URL"
    override val apiKey = "API Key"
    override val defaultModel = "النموذج الافتراضي"
    override val setDefault = "تعيين افتراضي"
    override val isDefault = "افتراضي"
    override val enabled = "مفعّل"
    override val refreshModels = "تحديث النماذج"
    override val noProvidersTitle = "لا يوجد مزود ذكاء اصطناعي"
    override val noProvidersSubtitle = "أضف مزودك (OpenAI-Compatible / Ollama / LM Studio) لبدء المحادثة."
    override val configureProvider = "إعداد المزود"
    override val connectionStatus = "الحالة"
    override val httpWarning = "تحذير: اتصال HTTP غير مشفر. استخدم HTTPS خارج الشبكة المحلية."
    override val workspaceStatus = "حالة مساحة العمل"
    override val resetConversations = "مسح المحادثات فقط"
    override val resetExecution = "مسح بيانات التنفيذ"
    override val resetCache = "مسح الكاش"
    override val factoryReset = "إعادة ضبط المصنع (تحذير: يحذف المفاتيح)"
}

object StringsEnglish : AppStrings {
    override val navChat = "Chat"
    override val navAgents = "Agents"
    override val navComputer = "Computer"
    override val navAutomations = "Automations"
    override val navApprovals = "Approvals"
    override val navFiles = "Files"
    override val navSettings = "Settings"

    override val appTitle = "AgentForge"
    override val appSubtitle = "Autonomous AI Agent Platform"
    override val offlineLocalBadge = "LOCAL / ACTIVE"
    override val onlineWsBadge = "WS CONNECTED"
    override val connectingBadge = "CONNECTING..."
    override val switchLanguage = "Switch Language"

    override val chatPlaceholder = "Assign task to agent (browse, research, code, automate)..."
    override val sendButton = "Send"
    override val agentWorking = "Agent is analyzing task and invoking tools..."
    override val quickPrompt1 = "🌐 Browse website and capture screenshot"
    override val quickPrompt2 = "📊 Compare AI agent frameworks"
    override val quickPrompt3 = "🗑️ Test file deletion approval workflow"
    override val quickPrompt4 = "💻 Inspect container environment & shell"
    override val viewLogs = "Execution Logs"
    override val noMessagesTitle = "Ready for Autonomous Execution"
    override val noMessagesSubtitle = "Command the agent to use browser sandbox, analyze files, or run scheduled automations."
    override val toolCompleted = "Completed successfully"

    override val agentsTitle = "Agent Registry"
    override val agentsSubtitle = "Configure specialized autonomous AI agents"
    override val createAgent = "Create Agent"
    override val agentStatusActive = "Active"
    override val agentStatusIdle = "Idle"
    override val capabilities = "Capabilities"
    override val temperature = "Temperature"
    override val contextWindow = "Context Window"

    override val computerTitle = "Computer Viewer"
    override val computerSubtitle = "Isolated Docker & Chromium Sandbox Environment"
    override val sandboxUrl = "Sandbox URL"
    override val navigateAction = "Navigate"
    override val takeControl = "Take Control"
    override val releaseControl = "Release to Agent"
    override val terminalOutput = "Terminal & Action Logs"
    override val screenResolution = "Resolution"

    override val automationsTitle = "Automations"
    override val automationsSubtitle = "Scheduled agent tasks & cron workflows"
    override val createAutomation = "Create Automation"
    override val cronSchedule = "Cron Schedule"
    override val cronExpression = "Cron Expression"
    override val lastRun = "Last Run"
    override val runNow = "Run Now"
    override val runAutomationNow = "Run Automation Now"
    override val statusActive = "ACTIVE"
    override val statusPaused = "PAUSED"

    override val approvalsTitle = "Approval Inbox"
    override val approvalsSubtitle = "Human-in-the-loop authorization queue"
    override val pendingAction = "Pending Authorization"
    override val approveButton = "Approve"
    override val rejectButton = "Reject"
    override val riskHigh = "HIGH RISK"
    override val riskMedium = "MEDIUM"
    override val riskLow = "LOW"
    override val reasonLabel = "Reason & Context"
    override val noApprovals = "No pending approvals"
    override val allSystemsOperational = "All actions authorized and executed"

    override val filesTitle = "Workspace Files"
    override val filesSubtitle = "Artifacts, research markdown, and generated code"
    override val searchFilesPlaceholder = "Search files by name or path..."
    override val filePreviewTitle = "File Preview"
    override val closePreview = "Close"

    override val settingsTitle = "Platform Settings"
    override val settingsSubtitle = "Language, local engine, API keys & security"
    override val languageSection = "Interface Language"
    override val languageSelection = "System Language"
    override val arabic = "العربية (RTL)"
    override val english = "English (LTR)"
    override val gatewaySection = "Gateway Server Connection"
    override val gatewayServer = "Gateway Server Connection"
    override val saveGateway = "Save & Reconnect Gateway"
    override val apiKeys = "Multi-Model API Keys"
    override val sandboxGovernance = "Security & Sandbox Governance"
    override val dockerIsolation = "Enforce Docker Isolation"
    override val dockerIsolationDesc = "Tools run strictly in ephemeral containers"
    override val redactSecrets = "Redact Secrets in Logs"
    override val redactSecretsDesc = "Mask keys, tokens, and passwords in timeline"
    override val localEngineSection = "Autonomous Local Engine"
    override val localEngineDesc = "The system runs 100% locally using Room Database and autonomous agent simulation without external server dependency."
    override val localEngineActive = "Local Engine Active"
    override val databaseManagement = "Local Database Management (Room SQLite)"
    override val databaseDesc = "Conversations, agent configs, execution logs and workspace files are persisted locally on device."
    override val resetDatabase = "Reset Local Database"
    override val confirmReset = "Are you sure you want to reset the database and restore default agents?"
    override val clearDatabase = "Reset Local Database"
    override val databaseCleared = "Database reset successfully"

    override val logsTitle = "Execution Logs"
    override val logsEntriesCount = "entries"
    override val logsFilterAll = "ALL"
    override val noLogsTitle = "No execution logs recorded yet"
    override val noLogsDesc = "Agent lifecycle and tool execution events will appear here in real-time."

    override val providersTitle = "AI Providers"
    override val providersSubtitle = "Add Base URL + API Key + Model, then test connection"
    override val addProvider = "Add Provider"
    override val editProvider = "Edit Provider"
    override val deleteProvider = "Delete Provider"
    override val testConnection = "Test Connection"
    override val saveProvider = "Save Provider"
    override val providerName = "Provider Name"
    override val providerType = "Provider Type"
    override val baseUrl = "Base URL"
    override val apiKey = "API Key"
    override val defaultModel = "Default Model"
    override val setDefault = "Set Default"
    override val isDefault = "Default"
    override val enabled = "Enabled"
    override val refreshModels = "Refresh Models"
    override val noProvidersTitle = "No AI provider configured"
    override val noProvidersSubtitle = "Add your provider (OpenAI-Compatible / Ollama / LM Studio) to start chatting."
    override val configureProvider = "Configure Provider"
    override val connectionStatus = "Status"
    override val httpWarning = "Warning: unencrypted HTTP. Use HTTPS outside the local network."
    override val workspaceStatus = "Workspace Status"
    override val resetConversations = "Clear conversations only"
    override val resetExecution = "Clear execution data"
    override val resetCache = "Clear cache"
    override val factoryReset = "Factory reset (warning: deletes keys)"
}
