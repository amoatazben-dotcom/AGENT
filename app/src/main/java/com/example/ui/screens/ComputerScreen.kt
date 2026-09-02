package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AgentViewModel
import com.example.ui.components.ScreenHeader
import com.example.ui.components.StatusPill
import com.example.ui.localization.LocalStrings
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@Composable
fun ComputerScreen(viewModel: AgentViewModel) {
    val strings = LocalStrings.current
    val session by viewModel.computerSession.collectAsState()
    var urlInput by remember { mutableStateOf(session.activeUrl) }
    var userHasControl by remember { mutableStateOf(false) }

    LaunchedEffect(session.activeUrl) {
        urlInput = session.activeUrl
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ScreenHeader(
            title = strings.computerTitle,
            subtitle = strings.computerSubtitle
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Virtual Desktop / Browser Window Frame
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ElectricCyan.copy(alpha = 0.35f))
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Browser Window Titlebar
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Window traffic lights
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(StatusError))
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(StatusWarning))
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(StatusSuccess))
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Browser address bar
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = StatusSuccess,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = session.activeUrl,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            StatusPill(
                                text = session.status.uppercase(),
                                color = if (session.status == "running") StatusSuccess else StatusWarning
                            )
                        }
                    }

                    // Virtual Screen Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(Color(0xFF0A0F1D))
                            .border(1.dp, MaterialTheme.colorScheme.outline),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Chromium Sandbox Viewport",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = session.activeUrl,
                                fontSize = 12.sp,
                                color = ElectricCyan,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${strings.screenResolution}: 1280x800 • Cursor: (${session.cursorX}, ${session.cursorY})",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Simulated mouse pointer indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = 60.dp, y = 90.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(ElectricCyan)
                                .border(2.dp, Color.White, CircleShape)
                        )
                    }

                    // Bottom info strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = session.lastAction,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick URL Navigation Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.sandboxUrl, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = { Text("https://...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) {
                                    viewModel.updateComputerUrl(urlInput.trim())
                                }
                            }
                        ) {
                            Text(strings.navigateAction)
                        }
                    }
                }
            }

            // Remote Computer Session Controls
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.terminalOutput, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { userHasControl = !userHasControl },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (userHasControl) StatusWarning else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (userHasControl) Icons.Default.PanTool else Icons.Default.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (userHasControl) strings.releaseControl else strings.takeControl)
                        }

                        OutlinedButton(
                            onClick = { viewModel.restartComputer() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restart")
                        }

                        OutlinedButton(
                            onClick = { viewModel.stopComputer() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError)
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
