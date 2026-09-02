package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ui.AgentViewModel
import com.example.ui.components.AppBrandHeader
import com.example.ui.components.ExecutionLogList
import com.example.ui.localization.AppLanguage
import com.example.ui.localization.LocalAppLanguage
import com.example.ui.localization.LocalStrings
import com.example.ui.localization.StringsArabic
import com.example.ui.localization.StringsEnglish
import com.example.ui.navigation.MainDestination
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AgentViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val currentLanguage by viewModel.currentLanguage.collectAsState()
            val strings = remember(currentLanguage) {
                if (currentLanguage == AppLanguage.ARABIC) StringsArabic else StringsEnglish
            }

            CompositionLocalProvider(
                LocalAppLanguage provides currentLanguage,
                LocalStrings provides strings,
                LocalLayoutDirection provides currentLanguage.layoutDirection
            ) {
                MyApplicationTheme {
                    AgentForgeApp(
                        viewModel = viewModel,
                        currentLanguage = currentLanguage,
                        onToggleLanguage = { viewModel.toggleLanguage() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentForgeApp(
    viewModel: AgentViewModel,
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit
) {
    val strings = LocalStrings.current
    var currentDestination by remember { mutableStateOf(MainDestination.CHAT) }
    val approvals by viewModel.approvals.collectAsState()
    val pendingApprovalsCount = remember(approvals) {
        approvals.count { it.status == "pending" }
    }
    var showGlobalLogsSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppBrandHeader(
                currentLanguage = currentLanguage,
                onToggleLanguage = onToggleLanguage,
                onOpenLogs = { showGlobalLogsSheet = true }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                tonalElevation = 6.dp
            ) {
                MainDestination.entries.forEach { destination ->
                    val selected = currentDestination == destination
                    val label = when (destination) {
                        MainDestination.CHAT -> strings.navChat
                        MainDestination.AGENTS -> strings.navAgents
                        MainDestination.COMPUTER -> strings.navComputer
                        MainDestination.AUTOMATIONS -> strings.navAutomations
                        MainDestination.APPROVALS -> strings.navApprovals
                        MainDestination.FILES -> strings.navFiles
                        MainDestination.SETTINGS -> strings.navSettings
                    }

                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentDestination = destination },
                        icon = {
                            if (destination == MainDestination.APPROVALS && pendingApprovalsCount > 0) {
                                BadgedBox(badge = {
                                    Badge { Text("$pendingApprovalsCount") }
                                }) {
                                    Icon(destination.icon, contentDescription = label)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, fontSize = 10.sp) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { innerPadding ->
        Crossfade(
            targetState = currentDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "screen_transition"
        ) { destination ->
            when (destination) {
                MainDestination.CHAT -> ChatScreen(viewModel = viewModel)
                MainDestination.AGENTS -> AgentsScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { currentDestination = MainDestination.CHAT }
                )
                MainDestination.COMPUTER -> ComputerScreen(viewModel = viewModel)
                MainDestination.AUTOMATIONS -> AutomationsScreen(viewModel = viewModel)
                MainDestination.APPROVALS -> ApprovalsScreen(viewModel = viewModel)
                MainDestination.FILES -> FilesScreen(viewModel = viewModel)
                MainDestination.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }

        if (showGlobalLogsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showGlobalLogsSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                ExecutionLogList(
                    repository = viewModel.repository,
                    modifier = Modifier
                        .fillMaxHeight(0.85f)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}
