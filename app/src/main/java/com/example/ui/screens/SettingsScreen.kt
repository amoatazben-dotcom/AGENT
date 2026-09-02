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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AgentViewModel
import com.example.ui.components.ScreenHeader
import com.example.ui.localization.AppLanguage
import com.example.ui.localization.LocalAppLanguage
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess

@Composable
fun SettingsScreen(viewModel: AgentViewModel) {
    val strings = LocalStrings.current
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentUrl by viewModel.gatewayUrl.collectAsState()

    var urlInput by remember { mutableStateOf(currentUrl) }
    var geminiKey by remember { mutableStateOf("AIzaSy••••••••••••••••••••••••") }
    var openAiKey by remember { mutableStateOf("sk-proj-••••••••••••••••••••••••") }
    var claudeKey by remember { mutableStateOf("sk-ant-••••••••••••••••••••••••") }

    var isolateContainers by remember { mutableStateOf(true) }
    var redactLogs by remember { mutableStateOf(true) }
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = strings.settingsTitle,
            subtitle = strings.settingsSubtitle
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language Selection Card
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = currentLanguage == AppLanguage.ARABIC,
                            onClick = {
                                if (currentLanguage != AppLanguage.ARABIC) {
                                    viewModel.toggleLanguage()
                                }
                            },
                            label = { Text(strings.arabic, fontWeight = FontWeight.Bold) },
                            leadingIcon = {
                                if (currentLanguage == AppLanguage.ARABIC) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        FilterChip(
                            selected = currentLanguage == AppLanguage.ENGLISH,
                            onClick = {
                                if (currentLanguage != AppLanguage.ENGLISH) {
                                    viewModel.toggleLanguage()
                                }
                            },
                            label = { Text(strings.english, fontWeight = FontWeight.Bold) },
                            leadingIcon = {
                                if (currentLanguage == AppLanguage.ENGLISH) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Local Database Management Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = StatusSuccess)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.databaseManagement, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = strings.databaseDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.resetDatabase)
                    }
                }
            }

            // Gateway Connection Card
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

                    Button(
                        onClick = { viewModel.updateGatewayUrl(urlInput.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.saveGateway)
                    }
                }
            }

            // AI Providers & API Keys Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.apiKeys, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = { Text("Google Gemini API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = openAiKey,
                        onValueChange = { openAiKey = it },
                        label = { Text("OpenAI API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = claudeKey,
                        onValueChange = { claudeKey = it },
                        label = { Text("Anthropic Claude API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Security & Sandboxing Card
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.dockerIsolation, fontWeight = FontWeight.Medium)
                            Text(
                                strings.dockerIsolationDesc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isolateContainers, onCheckedChange = { isolateContainers = it })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.redactSecrets, fontWeight = FontWeight.Medium)
                            Text(
                                strings.redactSecretsDesc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = redactLogs, onCheckedChange = { redactLogs = it })
                    }
                }
            }

            // Architecture Info
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AgentForge Core Engine", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Architecture: Local-First Room SQLite + MVVM Coroutines\n• Multilingual: Native RTL Arabic & LTR English\n• Autonomous Engine: Dynamic Tool Dispatcher + Human In Loop\n• Security: Policy Gatekeeper with Risk Tiering",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(strings.resetDatabase, fontWeight = FontWeight.Bold) },
                text = { Text(strings.confirmReset) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetDatabase()
                            showResetDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                    ) {
                        Text(strings.resetDatabase)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(strings.closePreview)
                    }
                }
            )
        }
    }
}
