package com.cutuy.clawisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cutuy.clawisland.models.AgentStatus
import com.cutuy.clawisland.notification.VivoSuperXHelper
import com.cutuy.clawisland.service.ClawIslandService

class MainActivity : ComponentActivity() {

    private var serverUrlState by mutableStateOf(ClawIslandService.DEFAULT_SERVER_URL)
    private var isServiceRunning by mutableStateOf(false)

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startMonitorService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF5722),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E24)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IslandDashboardScreen(
                        serverUrl = serverUrlState,
                        onServerUrlChange = { serverUrlState = it },
                        isServiceRunning = isServiceRunning,
                        onToggleService = {
                            if (isServiceRunning) {
                                stopMonitorService()
                            } else {
                                checkPermissionAndStart()
                            }
                        },
                        onSimulateTest = { status ->
                            val notification = VivoSuperXHelper.buildIslandNotification(
                                this,
                                status,
                                isUpdate = true
                            )
                            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                            manager.notify(VivoSuperXHelper.NOTIFICATION_ID, notification)
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startMonitorService()
            } else {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, ClawIslandService::class.java).apply {
            action = ClawIslandService.ACTION_START
            putExtra(ClawIslandService.EXTRA_SERVER_URL, serverUrlState)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
    }

    private fun stopMonitorService() {
        val intent = Intent(this, ClawIslandService::class.java).apply {
            action = ClawIslandService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
    }
}

@Composable
fun IslandDashboardScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onSimulateTest: (AgentStatus) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "🏝️ Claw Island",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "OriginOS 5/6 原子岛 & 状态栏实时监控",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Gateway Server 配置",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("Server SSE URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF5722),
                        unfocusedBorderColor = Color.DarkGray
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) Color(0xFFD32F2F) else Color(0xFFFF5722)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isServiceRunning) "停止常驻监控服务" else "启动原子岛常驻监控")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🧪 本地测试模拟 (OriginOS SuperX 验证)",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = "点击以下按钮可直接测试原子岛左岛、右岛和卡片展开效果：",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Button(
                    onClick = {
                        onSimulateTest(
                            AgentStatus(
                                active = true,
                                agent = "OpenCode",
                                status = "running",
                                stateType = "coding",
                                title = "Refactoring store.ts",
                                detail = "3 files changed • +45/-12 lines",
                                model = "glm-5.3",
                                procCount = 1
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟 OpenCode 正在写代码")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        onSimulateTest(
                            AgentStatus(
                                active = true,
                                agent = "Subagent",
                                status = "running",
                                stateType = "thinking",
                                title = "Researching OriginOS API",
                                detail = "Analyzing SuperX bundle documentation",
                                model = "ox-alpha-free",
                                procCount = 1
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟 Subagent 正在思考/调研")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        onSimulateTest(
                            AgentStatus(
                                active = false,
                                agent = "OpenClaw",
                                status = "idle",
                                stateType = "idle",
                                title = "All Agents Idle",
                                detail = "Standing by for instructions",
                                model = "ox-alpha-free",
                                procCount = 0
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟空闲 (Idle)")
                }
            }
        }
    }
}
