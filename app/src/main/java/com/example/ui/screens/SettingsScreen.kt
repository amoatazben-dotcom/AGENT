package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.BaseUrlNormalizer
import com.example.data.local.AIProviderEntity
import com.example.data.local.AppSettingEntity
import com.example.data.repository.PROVIDER_PRESETS
import com.example.ui.AgentViewModel
import com.example.ui.components.ScreenHeader
import com.example.ui.localization.AppLanguage
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AgentViewModel) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentUrl by viewModel.gatewayUrl.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val workspace by viewModel.workspaceStatus.collectAsState()

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<AIProviderEntity?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetScope by remember { mutableStateOf("conversations") }

    // Security switches — persisted in Room app_settings (real effect: redactLogs gates log viewers)
    var isolateContainers by remember { mutableStateOf(true) }
    var redactLogs by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            isolateContainers = viewModel.database.appSettingDao().get("isolate_containers")?.value?.toBoolean() ?: true
            redactLogs = viewModel.database.appSettingDao().get("redact_logs")?.value?.toBoolean() ?: true
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(title = strings.settingsTitle, subtitle = strings.settingsSubtitle)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Workspace status (REAL health)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = StatusSuccess)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.workspaceStatus, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.refreshWorkspaceStatus() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow("Database", workspace.database)
                    StatusRow("AI Provider", workspace.aiProvider)
                    StatusRow("Gateway", workspace.gateway)
                    StatusRow("Computer Worker", workspace.computerWorker)
                }
            }

            // Language Selection Card (kept)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ElectricCyan.copy(alpha = 0.35f))
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, contentDescription = null, tint = ElectricCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.languageSelection, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = currentLanguage == AppLanguage.ARABIC,
                            onClick = { viewModel.setLanguage(AppLanguage.ARABIC) },
                            label = { Text(strings.arabic, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = currentLanguage == AppLanguage.ENGLISH,
                            onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                            label = { Text(strings.english, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // AI Providers (REAL — Room + Keystore)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.providersTitle, fontWeight = FontWeight.Bold)
                            Text(strings.providersSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (providers.isEmpty()) {
                        Text(strings.noProvidersTitle, fontWeight = FontWeight.Medium)
                        Text(strings.noProvidersSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    providers.forEach { p ->
                        ProviderCard(
                            provider = p,
                            maskedKey = viewModel.secureStore().maskedApiKey(p.id),
                            onEdit = { editingProvider = p; showProviderDialog = true },
                            onDelete = { scope.launch { viewModel.providerRepository().deleteProvider(p.id) } },
                            onSetDefault = { scope.launch { viewModel.providerRepository().setDefault(p.id) } },
                            onToggle = { scope.launch { viewModel.providerRepository().setEnabled(p.id, !p.enabled) } },
                            onTest = { scope.launch { viewModel.providerRepository().testConnection(p.id) } }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { editingProvider = null; showProviderDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.addProvider)
                    }
                }
            }

            // Gateway Connection Card (persisted)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.gatewayServer, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Base URL (REST + WS)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { viewModel.updateGatewayUrl(urlInput.trim()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.saveGateway)
                    }
                }
            }

            // Security switches (persisted; redactLogs has real effect on log display)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.sandboxGovernance, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingSwitch(
                        title = strings.dockerIsolation,
                        desc = strings.dockerIsolationDesc,
                        checked = isolateContainers,
                        onChange = {
                            isolateContainers = it
                            scope.launch {
                                try {
                                    viewModel.database.appSettingDao().put(AppSettingEntity("isolate_containers", it.toString()))
                                } catch (_: Exception) {}
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingSwitch(
                        title = strings.redactSecrets,
                        desc = strings.redactSecretsDesc,
                        checked = redactLogs,
                        onChange = {
                            redactLogs = it
                            scope.launch {
                                try {
                                    viewModel.database.appSettingDao().put(AppSettingEntity("redact_logs", it.toString()))
                                } catch (_: Exception) {}
                            }
                        }
                    )
                }
            }

            // Database Management (REAL scoped reset with confirmation)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = StatusSuccess)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.databaseManagement, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.databaseDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { resetScope = "conversations"; showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(strings.resetConversations) }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { resetScope = "execution"; showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(strings.resetExecution) }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { resetScope = "cache"; showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(strings.resetCache) }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { resetScope = "factory"; showResetDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.factoryReset)
                    }
                }
            }

            // Architecture Info (kept)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AgentForge Core Engine", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Local-First Room SQLite + MVVM\n• OpenAI-Compatible providers (any Base URL)\n• Keys in Android Keystore, never in logs\n• Gateway optional; local chat otherwise",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        if (showProviderDialog) {
            ProviderDialog(
                strings = strings,
                existing = editingProvider,
                viewModel = viewModel,
                onDismiss = { showProviderDialog = false }
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(strings.resetDatabase, fontWeight = FontWeight.Bold) },
                text = { Text(if (resetScope == "factory") strings.factoryReset else strings.confirmReset) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetDatabase(resetScope)
                            showResetDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) { Text(strings.resetDatabase) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) { Text(strings.closePreview) }
                }
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderCard(
    provider: AIProviderEntity,
    maskedKey: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit
) {
    val strings = LocalStrings.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, fontWeight = FontWeight.Bold)
                        if (provider.isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            AssistChip(onClick = {}, label = { Text(strings.isDefault, fontSize = 10.sp) })
                        }
                    }
                    Text("${provider.type} • ${provider.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (provider.defaultModel.isNotBlank()) {
                        Text("Model: ${provider.defaultModel}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (maskedKey.isNotBlank()) {
                        Text(maskedKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    val statusColor = when (provider.status) {
                        "connected" -> StatusSuccess
                        "unknown" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> StatusError
                    }
                    val latency = provider.latencyMs?.let { " • $it ms" } ?: ""
                    Text("${strings.connectionStatus}: ${provider.status}$latency", color = statusColor, style = MaterialTheme.typography.bodySmall)
                    provider.lastError?.let {
                        Text(it, color = StatusError, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(checked = provider.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) { Text(strings.testConnection, fontSize = 11.sp) }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(strings.editProvider, fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (!provider.isDefault) {
                    OutlinedButton(onClick = onSetDefault, modifier = Modifier.weight(1f)) { Text(strings.setDefault, fontSize = 11.sp) }
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                    modifier = Modifier.weight(1f)
                ) { Text(strings.deleteProvider, fontSize = 11.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(
    strings: com.example.ui.localization.AppStrings,
    existing: AIProviderEntity?,
    viewModel: AgentViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(existing?.type ?: "openai_compatible") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(existing?.defaultModel ?: "") }
    var headers by remember { mutableStateOf(existing?.customHeadersJson ?: "{}") }
    var timeout by remember { mutableStateOf((existing?.timeoutMs ?: 60000).toString()) }
    var enableStreaming by remember { mutableStateOf(existing?.supportsStreaming ?: true) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    val types = listOf("openai_compatible", "openai", "openrouter", "groq", "deepseek", "xai", "mistral", "anthropic", "gemini", "ollama", "lmstudio", "custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) strings.addProvider else strings.editProvider, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Type dropdown with presets filling Base URL
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type, onValueChange = {}, readOnly = true, label = { Text(strings.providerType) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        types.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = {
                                    type = t
                                    typeExpanded = false
                                    if (baseUrl.isBlank()) {
                                        PROVIDER_PRESETS[t]?.let { baseUrl = it }
                                        BaseUrlNormalizer.defaultBaseUrlFor(t).takeIf { it.isNotBlank() }?.let { baseUrl = it }
                                    }
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings.providerName) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text(strings.baseUrl) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (baseUrl.trim().startsWith("http://", true)) {
                    Text(strings.httpWarning, color = StatusError, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (existing == null) strings.apiKey else "${strings.apiKey} (${if (existing.hasApiKey) "replace" else "add"})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    }
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("${strings.defaultModel} (ID)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text("Custom headers (JSON)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = timeout, onValueChange = { timeout = it.filter { c -> c.isDigit() } }, label = { Text("Timeout (ms)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enableStreaming, onCheckedChange = { enableStreaming = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Streaming")
                }
                error?.let { Text(it, color = StatusError, style = MaterialTheme.typography.bodySmall) }
                testMsg?.let { Text(it, color = if (testOk) StatusSuccess else StatusError, style = MaterialTheme.typography.bodySmall) }
                if (testing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testing = true
                            testMsg = null
                            error = null
                            try {
                                val norm = BaseUrlNormalizer.normalize(baseUrl).baseUrl
                                val tmp = AIProviderEntity(
                                    id = existing?.id ?: "tmp",
                                    name = name.ifBlank { type },
                                    type = type,
                                    baseUrl = norm,
                                    defaultModel = model,
                                    enabled = true,
                                    isDefault = false,
                                    supportsStreaming = enableStreaming,
                                    supportsTools = true,
                                    supportsVision = false,
                                    customHeadersJson = headers.ifBlank { "{}" },
                                    timeoutMs = timeout.toIntOrNull() ?: 60000,
                                    status = "unknown",
                                    hasApiKey = apiKey.isNotBlank() || (existing?.hasApiKey == true),
                                    createdAt = "",
                                    updatedAt = ""
                                )
                                // For unsaved providers, probe directly without persisting
                                val keyToUse = apiKey.ifBlank {
                                    if (existing != null) viewModel.secureStore().getApiKey(existing.id) ?: "" else ""
                                }
                                val hdrs = try {
                                    viewModel.providerRepository().parseHeaders(headers.ifBlank { "{}" })
                                } catch (_: Exception) {
                                    emptyMap()
                                }
                                val result = com.example.data.ai.ProviderProbe.testConnection(tmp, keyToUse, hdrs)
                                // Persist status/models when editing an existing provider
                                if (existing != null) {
                                    viewModel.providerRepository().testConnection(existing.id)
                                    val updated = viewModel.providerRepository().getProvider(existing.id)
                                    testOk = updated?.status == "connected" || updated?.status == "no_models"
                                    testMsg = if (testOk) "Connected${updated?.latencyMs?.let { " • $it ms" } ?: ""}" else (updated?.lastError ?: result.message)
                                } else {
                                    testOk = result.status == "connected" || result.status == "no_models"
                                    testMsg = if (testOk && result.models.isEmpty()) {
                                        "Connected. Enter a Model ID manually."
                                    } else if (testOk) {
                                        "Connected • ${result.latencyMs} ms • ${result.models.size} models: ${result.models.take(3).joinToString(", ")}"
                                    } else {
                                        result.message
                                    }
                                    if (testOk && result.models.isNotEmpty() && model.isBlank()) {
                                        model = result.models.first()
                                    }
                                }
                            } catch (e: Exception) {
                                testOk = false
                                testMsg = e.message
                            } finally {
                                testing = false
                            }
                        }
                    }
                ) { Text(strings.testConnection) }
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            try {
                                if (name.isBlank()) throw IllegalArgumentException("Name required")
                                if (existing == null) {
                                    viewModel.providerRepository().createProvider(
                                        name = name, type = type, baseUrl = baseUrl, apiKey = apiKey,
                                        defaultModel = model, customHeadersJson = headers.ifBlank { "{}" },
                                        timeoutMs = timeout.toIntOrNull() ?: 60000
                                    )
                                } else {
                                    viewModel.providerRepository().updateProvider(
                                        id = existing.id, name = name, baseUrl = baseUrl, defaultModel = model,
                                        customHeadersJson = headers.ifBlank { "{}" },
                                        timeoutMs = timeout.toIntOrNull() ?: 60000,
                                        enabled = existing.enabled,
                                        newApiKey = if (apiKey.isBlank()) null else apiKey
                                    )
                                }
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving
                ) { Text(strings.saveProvider) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.closePreview) }
        }
    )
}
