package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.model.AutomationModel
import com.example.ui.AgentViewModel
import com.example.ui.components.ScreenHeader
import com.example.ui.components.StatusPill
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(viewModel: AgentViewModel) {
    val strings = LocalStrings.current
    val automations by viewModel.automations.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = strings.automationsTitle,
                subtitle = strings.automationsSubtitle,
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
                Icon(Icons.Default.Add, contentDescription = strings.createAutomation)
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
            items(automations, key = { it.id }) { auto ->
                AutomationCard(
                    auto = auto,
                    onToggle = { viewModel.toggleAutomation(auto.id) },
                    onRunNow = { viewModel.runAutomationNow(auto) }
                )
            }
        }
    }
}

@Composable
fun AutomationCard(
    auto: AutomationModel,
    onToggle: () -> Unit,
    onRunNow: () -> Unit
) {
    val strings = LocalStrings.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = auto.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${strings.cronExpression}: ${auto.cronExpression}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Switch(
                    checked = auto.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = auto.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Last: ${auto.lastRunAt ?: "Never"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Next: ${auto.nextRunAt ?: "N/A"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusPill(
                    text = if (auto.enabled) strings.statusActive else strings.statusPaused,
                    color = if (auto.enabled) StatusSuccess else StatusWarning
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRunNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.runAutomationNow)
            }
        }
    }
}
