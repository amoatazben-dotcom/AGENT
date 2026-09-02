package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ExecutionLogEntity
import com.example.data.repository.AgentRepository
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.*

/**
 * A logging UI component that queries the [AgentRepository] and displays
 * a list of recent execution logs using a LazyColumn in Jetpack Compose.
 */
@Composable
fun ExecutionLogList(
    repository: AgentRepository,
    modifier: Modifier = Modifier,
    conversationId: String? = null,
    showHeader: Boolean = true
) {
    // Query logs directly from AgentRepository via Flow
    val logs by remember(repository, conversationId) {
        if (conversationId != null) {
            repository.getLogsForConversation(conversationId)
        } else {
            repository.executionLogs
        }
    }.collectAsState(initial = emptyList())

    ExecutionLogListContent(
        logs = logs,
        modifier = modifier,
        showHeader = showHeader
    )
}

/**
 * Renders the execution logs list using LazyColumn with level filters and detail inspection.
 */
@Composable
fun ExecutionLogListContent(
    logs: List<ExecutionLogEntity>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true
) {
    var selectedLevelFilter by remember { mutableStateOf("all") }
    val listState = rememberLazyListState()

    val filteredLogs = remember(logs, selectedLevelFilter) {
        if (selectedLevelFilter == "all") {
            logs
        } else {
            logs.filter { it.level.equals(selectedLevelFilter, ignoreCase = true) }
        }
    }

    val strings = LocalStrings.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("execution_log_list_container")
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.logsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Text(
                        text = "${logs.size} ${strings.logsEntriesCount}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val levels = listOf("all", "info", "tool", "warn", "error")
                items(levels) { level ->
                    val count = if (level == "all") logs.size else logs.count { it.level.equals(level, ignoreCase = true) }
                    val labelText = if (level == "all") "${strings.logsFilterAll} ($count)" else "${level.uppercase()} ($count)"
                    FilterChip(
                        selected = selectedLevelFilter == level,
                        onClick = { selectedLevelFilter = level },
                        label = { Text(labelText, fontSize = 11.sp) },
                        modifier = Modifier.testTag("filter_log_${level}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ListAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (logs.isEmpty()) strings.noLogsTitle else "No logs match '$selectedLevelFilter'",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.noLogsDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("execution_log_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    ExecutionLogItem(
                        log = log,
                        modifier = Modifier.testTag("execution_log_item_${log.id}")
                    )
                }
            }
        }
    }
}

/**
 * Individual log item card with level badge, event, message, and expandable details.
 */
@Composable
fun ExecutionLogItem(
    log: ExecutionLogEntity,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val levelColor = when (log.level.lowercase()) {
        "error" -> StatusError
        "warn" -> StatusWarning
        "tool" -> MaterialTheme.colorScheme.tertiary
        "debug" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }

    val timeFormatted = remember(log.timestamp) {
        val date = Date(log.timestamp)
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = CardDefaults.outlinedCardBorder(),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Level badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = levelColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, levelColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = log.level.uppercase(),
                            color = levelColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Event identifier
                    Text(
                        text = log.event,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Time
                Text(
                    text = timeFormatted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Log message
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            // Optional expandable details / stack / tool arguments
            AnimatedVisibility(
                visible = expanded || log.details.isNotBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (log.details.isNotBlank() || expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (log.details.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = log.details,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        if (expanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Run: ${log.runId.take(16)}...",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Agent: ${log.agentId}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
