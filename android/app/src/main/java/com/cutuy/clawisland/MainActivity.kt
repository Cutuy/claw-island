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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cutuy.clawisland.models.SessionInfo
import com.cutuy.clawisland.models.SessionsPayload
import com.cutuy.clawisland.models.TodoItem
import com.cutuy.clawisland.models.TodoStats
import com.cutuy.clawisland.notification.SessionsContent
import com.cutuy.clawisland.push.MonitorPreferences
import com.cutuy.clawisland.service.ClawIslandService
import com.cutuy.clawisland.service.ClawIslandStore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private var serverUrlState by mutableStateOf(ClawIslandService.DEFAULT_SERVER_URL)
    private var isServiceRunning by mutableStateOf(false)

    // Safe default — the real value is computed in onCreate/onResume. Calling
    // Settings.canDrawOverlays() from a property initializer would run inside
    // the Activity constructor, BEFORE the base context is attached (mBase ==
    // null), crashing with an NPE at launch (verified on API 36 emulator).
    private var hasOverlayPermission by mutableStateOf(false)

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
        // Base context is attached now — safe to query the overlay permission.
        hasOverlayPermission = canDrawOverlays()
        serverUrlState = MonitorPreferences.serverUrl(this, ClawIslandService.DEFAULT_SERVER_URL)
        isServiceRunning = MonitorPreferences.monitorEnabled(this)

        setContent {
            val context = LocalContext.current
            val pixelColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else darkColorScheme(
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

            val payload by ClawIslandStore.payload.collectAsState()
            val connected by ClawIslandStore.connected.collectAsState()
            val lifecycleEvents by ClawIslandStore.events.collectAsState()

            MaterialTheme(colorScheme = pixelColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SessionsDashboard(
                        serverUrl = serverUrlState,
                        onServerUrlChange = { serverUrlState = it },
                        isServiceRunning = isServiceRunning,
                        connected = connected,
                        payload = payload,
                        lifecycleEvents = lifecycleEvents,
                        onToggleService = {
                            if (isServiceRunning) {
                                stopMonitorService()
                            } else {
                                checkPermissionAndStart()
                            }
                        },
                        onSimulateTest = { simPayload ->
                            ClawIslandStore.payload.value = simPayload
                            // Route through ClawIslandService so the notification is posted under
                            // a foreground service context. Direct manager.notify() from an Activity
                            // is silently NOT promoted to vivo OriginOS atomic island.
                            val encoded = Json.encodeToString(SessionsPayload.serializer(), simPayload)
                            val simIntent = Intent(this, ClawIslandService::class.java).apply {
                                action = ClawIslandService.ACTION_SIMULATE
                                putExtra(ClawIslandService.EXTRA_SIM_PAYLOAD, encoded)
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
fun SessionsDashboard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    isServiceRunning: Boolean,
    connected: Boolean,
    payload: SessionsPayload,
    lifecycleEvents: List<com.cutuy.clawisland.models.SessionEvent> = emptyList(),
    onToggleService: () -> Unit,
    onSimulateTest: (SessionsPayload) -> Unit
) {
    val scrollState = rememberScrollState()
    var showConnection by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    // 1s ticker so elapsed times advance client-side between SSE ticks.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp)
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Session control plane",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            // Status Pill: LIVE when connected AND something is active
            val live = connected && payload.anyActive
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    live -> Color(0xFF4CAF50)
                                    connected -> Color(0xFFFFC107)
                                    else -> Color(0xFF9E9E9E)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            live -> "LIVE"
                            connected -> "IDLE"
                            else -> "OFFLINE"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (live) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        FleetOverview(payload = payload, connected = connected)
        Spacer(modifier = Modifier.height(16.dp))

        // --- Active Sessions ---
        if (payload.active.isNotEmpty()) {
            SectionHeader("Active Sessions · ${payload.active.size}")
            payload.active.forEach { session ->
                Spacer(modifier = Modifier.height(8.dp))
                ActiveSessionCard(
                    session = session,
                    nowMs = nowMs,
                    payloadNowTs = payload.nowTs,
                    onClick = { selectedSessionId = session.id }
                )
            }
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💤 All agents idle", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Standing by · ${payload.recent.size} recent sessions",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- Recent Sessions ---
        if (payload.recent.isNotEmpty()) {
            SectionHeader("Recent Sessions")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    payload.recent.forEachIndexed { index, session ->
                        RecentSessionRow(session = session, nowMs = nowMs, onClick = { selectedSessionId = session.id })
                        if (index < payload.recent.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- Recent Lifecycle Events ---
        if (lifecycleEvents.isNotEmpty()) {
            SectionHeader("Recent Events")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    lifecycleEvents.take(8).forEachIndexed { index, evt ->
                        EventRow(evt = evt, nowMs = nowMs)
                        if (index < minOf(lifecycleEvents.size, 8) - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { showConnection = !showConnection }, modifier = Modifier.weight(1f)) {
                Text(if (showConnection) "Hide gateway" else "Gateway")
            }
            TextButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.weight(1f)) {
                Text(if (showDiagnostics) "Hide diagnostics" else "Diagnostics")
            }
        }

        // --- Server & Control Card ---
        if (showConnection) Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onToggleService,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isServiceRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "停止监控服务" else "启动会话常驻监控",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Simulation Test Center ---
        if (showDiagnostics) Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "🧪 通知 & 会话面板测试",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "点击一键发送模拟 SessionsPayload（含 todo 进度条）：",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                FilledTonalButton(
                    onClick = { onSimulateTest(simSingleActive()) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔨 模拟单会话运行 (todo 3/8)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
                    onClick = { onSimulateTest(simMultiActive()) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔥 模拟双会话并行 (2 agents)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
                    onClick = { onSimulateTest(SessionsPayload(nowTs = System.currentTimeMillis())) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💤 模拟空闲待机 (Idle)")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    selectedSessionId?.let { selectedId ->
        val session = (payload.active + payload.recent).firstOrNull { it.id == selectedId }
        if (session != null) {
        SessionDetailSheet(
            session = session,
            nowMs = nowMs,
            payloadNowTs = payload.nowTs,
            events = lifecycleEvents.filter { it.session?.id == session.id },
            onDismiss = { selectedSessionId = null }
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailSheet(
    session: SessionInfo,
    nowMs: Long,
    payloadNowTs: Long,
    events: List<com.cutuy.clawisland.models.SessionEvent>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val liveElapsed = session.elapsedSeconds +
        if (session.isActive) ((nowMs - payloadNowTs) / 1000L).coerceAtLeast(0L).toInt() else 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (session.source == "codex") "Codex" else "OpenCode"} · ${session.repo}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close session details")
                }
            }

            Spacer(Modifier.height(14.dp))
            DetailMetrics(session, liveElapsed, nowMs)

            if (session.source == "codex" && listOf(session.phase, session.lastAction, session.gitBranch).any { it.isNotEmpty() }) {
                Spacer(Modifier.height(14.dp))
                DetailSection("Current execution") {
                    Text(
                        listOfNotNull(
                            session.phase.takeIf { it.isNotEmpty() },
                            session.lastAction.takeIf { it.isNotEmpty() },
                            session.gitBranch.takeIf { it.isNotEmpty() }?.let { "branch $it" },
                            session.reasoningEffort.takeIf { it.isNotEmpty() }?.let { "$it reasoning" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (session.lastActivity.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                DetailSection("Latest activity") {
                    Text(session.lastActivity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (session.todo.total > 0) {
                Spacer(Modifier.height(14.dp))
                DetailSection("Plan · ${session.todo.completed}/${session.todo.total}") {
                    LinearProgressIndicator(
                        progress = { session.todo.completed.toFloat() / session.todo.total },
                        modifier = Modifier.fillMaxWidth().height(5.dp)
                    )
                    session.todo.items.forEach { item ->
                        Row(modifier = Modifier.padding(top = 9.dp), verticalAlignment = Alignment.Top) {
                            val isComplete = item.status == "completed"
                            val isCurrent = item.status == "in_progress"
                            Text(
                                when (item.status) { "completed" -> "✓"; "in_progress" -> "●"; else -> "○" },
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isComplete -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                text = item.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.onSurface
                                    isComplete -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                textDecoration = if (isComplete) TextDecoration.LineThrough else TextDecoration.None
                            )
                        }
                    }
                }
            }

            if (events.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                DetailSection("Lifecycle") {
                    events.take(6).forEach { EventRow(it, nowMs) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailMetrics(session: SessionInfo, liveElapsed: Int, nowMs: Long) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Metric("Model", session.model.ifEmpty { "—" }, MaterialTheme.colorScheme.secondary)
            Metric(
                if (session.isActive) "Elapsed" else "Updated",
                if (session.isActive) formatElapsed(liveElapsed) else timeAgo(nowMs - session.updatedAt),
                MaterialTheme.colorScheme.primary
            )
            Metric(
                "Tokens",
                SessionsContent.fmtTokens(if (session.tokensTotal > 0) session.tokensTotal else session.tokensInput + session.tokensOutput),
                MaterialTheme.colorScheme.tertiary
            )
            Metric("Turns", session.messageCount.toString(), MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
private fun FleetOverview(payload: SessionsPayload, connected: Boolean) {
    val codex = payload.active.count { it.source == "codex" }
    val openCode = payload.active.count { it.source == "opencode" }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Metric("Active", payload.active.size.toString(), MaterialTheme.colorScheme.primary)
            VerticalDivider(modifier = Modifier.height(30.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Metric("Codex", codex.toString(), MaterialTheme.colorScheme.tertiary)
            VerticalDivider(modifier = Modifier.height(30.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Metric("OpenCode", openCode.toString(), MaterialTheme.colorScheme.secondary)
            VerticalDivider(modifier = Modifier.height(30.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Metric("Recent", payload.recent.size.toString(), if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ActiveSessionCard(session: SessionInfo, nowMs: Long, payloadNowTs: Long, onClick: () -> Unit) {
    // Elapsed ticks client-side between SSE ticks (recomputed on every 1s ticker
    // recomposition — no remember, it must track nowMs).
    val liveElapsed = session.elapsedSeconds +
        ((nowMs - payloadNowTs) / 1000L).coerceAtLeast(0L).toInt()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // Row 1: repo chip + model chip + elapsed
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sourceIcon = if (session.source == "codex") "◈" else "🔨"
                Chip(text = "$sourceIcon ${session.repo}", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.width(6.dp))
                Chip(
                    text = buildString {
                        append(session.model.ifEmpty { "—" })
                        if (session.reasoningEffort.isNotEmpty()) append(" · ${session.reasoningEffort}")
                    },
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatElapsed(liveElapsed),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Row 2: title + last activity + staleness
            Text(
                text = session.title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (session.lastActivity.isNotEmpty()) {
                Text(
                    text = session.lastActivity,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (session.source == "codex" && (session.phase.isNotEmpty() || session.gitBranch.isNotEmpty())) {
                Text(
                    text = listOfNotNull(
                        session.phase.takeIf { it.isNotEmpty() },
                        session.lastAction.takeIf { it.isNotEmpty() },
                        session.gitBranch.takeIf { it.isNotEmpty() }?.let { "⑂ $it" }
                    ).joinToString(" · "),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            // Staleness: the bridge updates session rows on every message; if
            // the row is older than 30s the session is thinking/long-generating
            // (or the feed is stale) — surface it in a muted warning tone.
            val staleMs = nowMs - session.updatedAt
            if (session.isActive && staleMs > 30_000L) {
                Text(
                    text = "last updated ${timeAgo(staleMs)} ago",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Row 3: todo progress
            val todo = session.todo
            if (todo.total > 0) {
                Spacer(modifier = Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { if (todo.total > 0) todo.completed.toFloat() / todo.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "✓ ${todo.completed}/${todo.total} · ● ${todo.inProgress} in progress",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                todo.items.firstOrNull { it.status == "in_progress" }?.let { current ->
                    Text(
                        text = "● ${current.content}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Row 4: tokens + cost
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (session.tokensTotal > 0) {
                    "${session.source} · tokens ${SessionsContent.fmtTokens(session.tokensTotal)}"
                } else {
                    "${session.source} · in ${SessionsContent.fmtTokens(session.tokensInput)} · out ${SessionsContent.fmtTokens(session.tokensOutput)} · cache ${SessionsContent.fmtTokens(session.tokensCacheRead)} · $${SessionsContent.fmtCost(session.cost)}"
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun RecentSessionRow(session: SessionInfo, nowMs: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${session.source} · ${session.model.ifEmpty { "—" }} · ${session.messageCount} turns · ✓${session.todo.completed}/${session.todo.total}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (session.cost > 0.0 || session.tokensInput > 0 || session.tokensTotal > 0) {
                Text(
                    text = if (session.tokensTotal > 0) {
                        "tokens ${SessionsContent.fmtTokens(session.tokensTotal)}"
                    } else {
                        "in ${SessionsContent.fmtTokens(session.tokensInput)} · out ${SessionsContent.fmtTokens(session.tokensOutput)} · $${SessionsContent.fmtCost(session.cost)}"
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Chip(text = session.repo, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = timeAgo(nowMs - session.updatedAt),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EventRow(evt: com.cutuy.clawisland.models.SessionEvent, nowMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (evt.type) {
                "session_started" -> "🆕"
                "session_resumed" -> "▶️"
                "session_finished" -> "✅"
                "session_exited" -> "⚠️"
                else -> "•"
            },
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${evt.session?.repo ?: "?"} · ${evt.session?.title ?: "Unknown"}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val s = evt.session
            if (s != null && (evt.type == "session_finished" || evt.type == "session_exited") && s.elapsedSeconds > 0) {
                Text(
                    text = "ran ${formatElapsed(s.elapsedSeconds)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = timeAgo(nowMs - evt.ts),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatElapsed(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val m = s / 60
    val h = m / 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m % 60, s % 60)
    else String.format("%02d:%02d", m, s % 60)
}

private fun timeAgo(deltaMs: Long): String {
    val seconds = deltaMs / 1000L
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

// --- Sim payloads (SessionsPayload shape) ---

private fun simSingleActive(): SessionsPayload {
    val now = System.currentTimeMillis()
    val todo = TodoStats(
        total = 8,
        completed = 3,
        inProgress = 1,
        pending = 4,
        items = listOf(
            TodoItem("N0: add chrono-tz dep to Cargo.toml", "completed", "high"),
            TodoItem("N1: broker.rs — Quote struct + bounds", "in_progress", "high"),
            TodoItem("N2: engine quote-cache → HashMap", "pending", "high"),
            TodoItem("N3: overnight guards G1/G2/G4", "pending", "medium")
        )
    )
    val session = SessionInfo(
        id = "sim_1",
        title = "Event-driven task unification proposal",
        repo = "robinhood-engine",
        directory = "/home/cutuy/.openclaw/workspace/robinhood-engine",
        agent = "build",
        model = "glm-5.3",
        isActive = true,
        elapsedSeconds = 402,
        formattedTime = "06:42",
        messageCount = 23,
        tokensInput = 55929,
        tokensOutput = 296,
        tokensReasoning = 52,
        tokensCacheRead = 30848,
        cost = 0.0919,
        createdAt = now - 402_000,
        updatedAt = now,
        lastActivity = "Read everything: event.rs, engine.rs, broker.rs — mapping the dispatch flow before proposing the task unification.",
        todo = todo
    )
    return SessionsPayload(
        nowTs = now,
        active = listOf(session),
        recent = emptyList(),
        events = listOf(
            com.cutuy.clawisland.models.SessionEvent(
                id = "sim_evt_1",
                seq = 1,
                type = "session_started",
                ts = now,
                session = session
            )
        )
    )
}

private fun simMultiActive(): SessionsPayload {
    val now = System.currentTimeMillis()
    val a = SessionInfo(
        id = "sim_a",
        title = "Feature B overnight session N3-N9",
        repo = "robinhood-engine",
        model = "glm-5.3",
        isActive = true,
        elapsedSeconds = 1873,
        formattedTime = "31:13",
        messageCount = 412,
        tokensInput = 66676,
        tokensOutput = 540,
        tokensCacheRead = 30848,
        cost = 0.1832,
        createdAt = now - 1_873_000,
        updatedAt = now,
        lastActivity = "cargo test — 136 passed, 0 failed. Moving to N4 dispatcher wiring.",
        todo = TodoStats(total = 10, completed = 4, inProgress = 1, pending = 5)
    )
    val b = SessionInfo(
        id = "sim_b",
        title = "Sessions observability spec",
        repo = "claw-island",
        model = "glm-5.3",
        isActive = true,
        elapsedSeconds = 245,
        formattedTime = "04:05",
        messageCount = 18,
        tokensInput = 49937,
        tokensOutput = 4135,
        cost = 0.2628,
        createdAt = now - 245_000,
        updatedAt = now,
        lastActivity = "Editing MainActivity.kt — Sessions Dashboard with todo progress + token lines.",
        todo = TodoStats(total = 4, completed = 2, inProgress = 1, pending = 1)
    )
    return SessionsPayload(nowTs = now, active = listOf(a, b), recent = emptyList())
}
