package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class MainDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    CHAT("chat", "Chat", Icons.Default.ChatBubble),
    AGENTS("agents", "Agents", Icons.Default.SmartToy),
    COMPUTER("computer", "Computer", Icons.Default.Tv),
    AUTOMATIONS("automations", "Auto", Icons.Default.Schedule),
    APPROVALS("approvals", "Approvals", Icons.Default.VerifiedUser),
    FILES("files", "Files", Icons.Default.Folder),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}
