package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AgentViewModel
import com.example.ui.components.ApprovalCard
import com.example.ui.components.ScreenHeader
import com.example.ui.localization.LocalStrings

@Composable
fun ApprovalsScreen(viewModel: AgentViewModel) {
    val strings = LocalStrings.current
    val approvals by viewModel.approvals.collectAsState()
    var selectedFilter by remember { mutableStateOf("all") }

    val filteredApprovals = remember(approvals, selectedFilter) {
        when (selectedFilter) {
            "pending" -> approvals.filter { it.status == "pending" }
            "approved" -> approvals.filter { it.status == "approved" }
            "rejected" -> approvals.filter { it.status == "rejected" }
            else -> approvals
        }
    }

    val pendingCount = approvals.count { it.status == "pending" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = strings.approvalsTitle,
            subtitle = if (pendingCount > 0) "$pendingCount ${strings.pendingAction}" else strings.allSystemsOperational
        )

        // Filter chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedFilter == "all",
                    onClick = { selectedFilter = "all" },
                    label = { Text("${strings.logsFilterAll} (${approvals.size})") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "pending",
                    onClick = { selectedFilter = "pending" },
                    label = { Text("${strings.pendingAction} ($pendingCount)") }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "approved",
                    onClick = { selectedFilter = "approved" },
                    label = { Text(strings.statusActive) }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == "rejected",
                    onClick = { selectedFilter = "rejected" },
                    label = { Text(strings.rejectButton) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (filteredApprovals.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.noApprovals,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.allSystemsOperational,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(filteredApprovals, key = { it.id }) { approval ->
                ApprovalCard(
                    approval = approval,
                    onApprove = { viewModel.approveAction(approval) },
                    onReject = { viewModel.rejectAction(approval) }
                )
            }
        }
    }
}
