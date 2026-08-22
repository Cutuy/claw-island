package com.cutuy.clawisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cutuy.clawisland.models.AgentStatus
import com.cutuy.clawisland.notification.VivoSuperXHelper
import com.cutuy.clawisland.service.ClawIslandService
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private var serverUrlState by mutableStateOf(ClawIslandService.DEFAULT_SERVER_URL)
    private var isServiceRunning by mutableStateOf(false)
    private var currentStatus by mutableStateOf(AgentStatus())

    private var hasOverlayPermission by mutableStateOf(canDrawOverlays())

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                ensureOverlayPermissionThenStart()
            }
        }

    private val requestOverlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasOverlayPermission = canDrawOverlays()
            if (hasOverlayPermission) startMonitorService()
        }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { requestOverlayPermissionLauncher.launch(intent) }
            .onFailure { startActivity(intent) }
    }

    private fun ensureOverlayPermissionThenStart() {
        if (!canDrawOverlays()) {
            openOverlayPermissionSettings()
        } else {
            hasOverlayPermission = true
            startMonitorService()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh after the user comes back from Settings.
        hasOverlayPermission = canDrawOverlays()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Google Pixel Material 3 Expressive Dark Palette
            val pixelColorScheme = darkColorScheme(
                primary = Color(0xFFFFB59D),
                onPrimary = Color(0xFF5F1500),
                primaryContainer = Color(0xFF812800),
                onPrimaryContainer = Color(0xFFFFDBCF),
                secondary = Color(0xFFE7BDB2),
                onSecondary = Color(0xFF442A22),
                secondaryContainer = Color(0xFF5D4037),
                onSecondaryContainer = Color(0xFFFFDBCF),
                tertiary = Color(0xFFDDC48C),
                background = Color(0xFF141218),
                surface = Color(0xFF1D1B20),
                surfaceVariant = Color(0xFF49454E),
                onSurface = Color(0xFFE6E0E9),
                onSurfaceVariant = Color(0xFFCAC4D0)
            )

            MaterialTheme(colorScheme = pixelColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PixelIslandDashboard(
                        serverUrl = serverUrlState,
                        onServerUrlChange = { serverUrlState = it },
                        isServiceRunning = isServiceRunning,
                        currentStatus = currentStatus,
                        onToggleService = {
                            if (isServiceRunning) {
                                stopMonitorService()
                            } else {
                                checkPermissionAndStart()
                            }
                        },
                        onSimulateTest = { status ->
                            currentStatus = status
                            // Route through ClawIslandService so the notification is posted under
                            // a foreground service context. Direct manager.notify() from an Activity
                            // is silently NOT promoted to vivo OriginOS atomic island.
                            val payload = Json.encodeToString(AgentStatus.serializer(), status)
                            val simIntent = Intent(this, ClawIslandService::class.java).apply {
                                action = ClawIslandService.ACTION_SIMULATE
                                putExtra(ClawIslandService.EXTRA_SIM_PAYLOAD, payload)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(simIntent)
                            } else {
                                startService(simIntent)
                            }
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
                ensureOverlayPermissionThenStart()
            } else {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            ensureOverlayPermissionThenStart()
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
fun PixelIslandDashboard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    isServiceRunning: Boolean,
    currentStatus: AgentStatus,
    onToggleService: () -> Unit,
    onSimulateTest: (AgentStatus) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Claw Island",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "vivo OriginOS 5/6 Live Tracker",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Status Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (isServiceRunning) Color(0xFF1B4D3E) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceRunning) "LIVE" else "IDLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isServiceRunning) Color(0xFFC8E6C9) else Color.LightGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Live Island Preview Screen (Pixel Material 3 Hero Card) ---
        LiveIslandHeroPreview(status = currentStatus)

        Spacer(modifier = Modifier.height(20.dp))

        // --- Server & Control Card ---
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Gateway 连接控制",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("Server SSE URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onToggleService,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isServiceRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "停止原子岛服务" else "启动原子岛常驻监控",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Simulation Test Center (Pixel Chips) ---
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "🧪 原子岛动效 & 模版测试",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "点击一键向 vivo OriginOS 发送不同运行状态的原子通知：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                FilledTonalButton(
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
                                elapsedSeconds = 245,
                                formattedTime = "04:05",
                                procCount = 1
                            )
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔨 模拟 OpenCode 运行 (04:05 • Refactoring)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
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
                                elapsedSeconds = 85,
                                formattedTime = "01:25",
                                procCount = 1
                            )
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧠 模拟 Subagent 深度思考 (01:25 • Thinking)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
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
                                elapsedSeconds = 0,
                                formattedTime = "00:00",
                                procCount = 0
                            )
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💤 模拟空闲待机 (Idle)")
                }
            }
        }
    }
}

@Composable
fun LiveIslandHeroPreview(status: AgentStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0E13)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF2B2930), Color(0xFF3F3B48), Color(0xFF2B2930))
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "STATUS BAR PREVIEW",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color(0xFF8E8B98)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Simulated Punch Hole & Island Capsule
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF26242E), RoundedCornerShape(26.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Left Island: Icon + Agent Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (status.agent == "OpenCode") "🐾" else "🏝️",
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = status.agent,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Punch Hole Mock
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A1A1A))
                    )
                    Spacer(modifier = Modifier.width(16.dp))

                    // Right Island: Status Badge / Timer
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status.active) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFB59D).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val badge = when (status.stateType) {
                                "coding" -> "🔨 ${if (status.formattedTime != "00:00") status.formattedTime else "Coding"}"
                                "thinking" -> "🧠 ${if (status.formattedTime != "00:00") status.formattedTime else "Thinking"}"
                                else -> "Active"
                            }
                            Text(
                                text = badge,
                                color = Color(0xFFFFB59D),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Idle",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Expanded Card Info Mock
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E1C24),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = status.title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = status.model,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (status.detail.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status.detail,
                            fontSize = 12.sp,
                            color = Color(0xFFCAC4D0)
                        )
                    }
                }
            }
        }
    }
}
