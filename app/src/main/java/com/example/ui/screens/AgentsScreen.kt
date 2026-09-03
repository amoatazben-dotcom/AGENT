package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentModel
import com.example.ui.AgentViewModel
import com.example.ui.components.ScreenHeader
import com.example.ui.components.StatusPill
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonIndigo
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    viewModel: AgentViewModel,
    onNavigateToChat: () -> Unit
) {
    val strings = LocalStrings.current
    val agents by viewModel.agents.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = strings.agentsTitle,
                subtitle = strings.agentsSubtitle,
                actionIcon = Icons.Default.Add,
                onActionClick = { showCreateDialog = true }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = strings.createAgent)
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(agents, key = { it.id }) { agent ->
                AgentCard(
                    agent = agent,
                    onChatClick = {
                        viewModel.selectAgent(agent.id)
                        viewModel.createNewConversation("${strings.navChat}: ${agent.name}", agent.id)
                        onNavigateToChat()
                    },
                    onDeleteClick = { viewModel.deleteAgent(agent.id) }
                )
            }
        }

        if (showCreateDialog) {
            CreateAgentDialog(
                viewModel = viewModel,
                onDismiss = { showCreateDialog = false },
                onConfirm = { newAgent ->
                    viewModel.createAgent(newAgent)
                    showCreateDialog = false
                }
            )
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentModel,
    onChatClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val strings = LocalStrings.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(ElectricCyan.copy(alpha = 0.25f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (agent.icon == "terminal") Icons.Default.Terminal else Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = agent.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${agent.primaryProvider.uppercase()} • ${agent.primaryModel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                StatusPill(
                    text = if (agent.approvalPolicy == "require_approval") "HUMAN-CHECK" else "AUTONOMOUS",
                    color = if (agent.approvalPolicy == "require_approval") StatusWarning else StatusSuccess
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = agent.description.ifBlank { agent.systemPrompt.take(120) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Capabilities badges
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (agent.computerPermission) item { PermissionTag(strings.navComputer) }
                if (agent.shellPermission) item { PermissionTag("Shell Sandbox") }
                if (agent.filesystemPermission) item { PermissionTag(strings.navFiles) }
                if (agent.subagentPermission) item { PermissionTag("Subagents") }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                }

                Button(
                    onClick = onChatClick,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.navChat)
                }
            }
        }
    }
}

@Composable
fun PermissionTag(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentDialog(
    viewModel: AgentViewModel,
    onDismiss: () -> Unit,
    onConfirm: (AgentModel) -> Unit
) {
    val strings = LocalStrings.current
    val providers by viewModel.providers.collectAsState()
    val enabledProviders = providers.filter { it.enabled }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("You are a helpful AI assistant.") }
    var selectedProviderId by remember(enabledProviders) {
        mutableStateOf(enabledProviders.firstOrNull { it.isDefault }?.id ?: enabledProviders.firstOrNull()?.id)
    }
    var selectedModel by remember { mutableStateOf("") }
    var modelOptions by remember { mutableStateOf(listOf<String>()) }
    var approvalPolicy by remember { mutableStateOf("require_approval") }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
    LaunchedEffect(selectedProviderId) {
        val pid = selectedProviderId
        if (pid != null) {
            try {
                modelOptions = viewModel.modelsForProvider(pid)
                if (selectedModel.isBlank()) {
                    selectedModel = selectedProvider?.defaultModel ?: modelOptions.firstOrNull() ?: ""
                }
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createAgent, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Agent Name") },
                    placeholder = { Text("e.g. Market Research Agent") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("System Prompt") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (enabledProviders.isEmpty()) {
                    Text(
                        strings.noProvidersTitle,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(strings.configureProvider, style = MaterialTheme.typography.bodySmall)
                } else {
                    // Provider picker (REAL — from ProviderRepository)
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                        OutlinedTextField(
                            value = selectedProvider?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(strings.providerType) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            enabledProviders.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("${p.name} (${p.type})") },
                                    onClick = {
                                        selectedProviderId = p.id
                                        selectedModel = p.defaultModel
                                        providerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Model picker (discovered models + manual entry)
                    ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                        OutlinedTextField(
                            value = selectedModel,
                            onValueChange = { selectedModel = it },
                            label = { Text("${strings.defaultModel} (ID)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        if (modelOptions.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                                modelOptions.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = { selectedModel = m; modelExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && selectedProvider != null) {
                        val newAgent = AgentModel(
                            name = name,
                            description = description,
                            systemPrompt = prompt,
                            primaryProvider = selectedProvider.type,
                            primaryModel = selectedModel,
                            primaryProviderId = selectedProvider.id,
                            approvalPolicy = approvalPolicy
                        )
                        onConfirm(newAgent)
                    }
                },
                enabled = name.isNotBlank() && selectedProvider != null
            ) {
                Text(strings.createAgent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closePreview)
            }
        }
    )
}
