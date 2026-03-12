package com.meshbrain.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ── Colors ────────────────────────────────────────────────────────────

private val BgDark       = Color(0xFF02040A)
private val SurfaceDark  = Color(0xFF070D1A)
private val BorderColor  = Color(0xFF0F2040)
private val CyanAccent   = Color(0xFF00E5FF)
private val GreenAccent  = Color(0xFF39FF14)
private val GoldAccent   = Color(0xFFF5C842)
private val RedAccent    = Color(0xFFFF2D55)
private val VioletAccent = Color(0xFFA855F7)
private val TextPrimary  = Color(0xFFD0E4F7)
private val TextMuted    = Color(0xFF2A4A6A)

// ── Main Activity ─────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeshBrainTheme {
                MeshBrainApp(viewModel)
            }
        }
    }
}

// ── Theme ─────────────────────────────────────────────────────────────

@Composable
fun MeshBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background   = BgDark,
            surface      = SurfaceDark,
            primary      = CyanAccent,
            onPrimary    = BgDark,
            onBackground = TextPrimary,
            onSurface    = TextPrimary,
        ),
        content = content
    )
}

// ── Root App ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshBrainApp(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showConnectDialog by remember { mutableStateOf(false) }

    // Auto-scroll to latest message
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ──
            TopBar(
                state = state,
                onMeshClick = { vm.toggleMeshPanel() },
                onConnectClick = { showConnectDialog = true },
                onClearClick = { vm.clearHistory() }
            )

            // ── Mesh Panel (collapsible) ──
            AnimatedVisibility(
                visible = state.showMeshPanel,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                MeshPanel(state = state, onConnectClick = { showConnectDialog = true })
            }

            // ── Messages ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    ChatBubble(msg)
                }
                if (state.isThinking) {
                    item { ThinkingIndicator() }
                }
            }

            // ── Input Bar ──
            InputBar(
                text = state.inputText,
                onTextChange = { vm.updateInputText(it) },
                onSend = { vm.sendMessage(state.inputText) },
                enabled = !state.isThinking && !state.isInitializing
            )
        }

        // ── Connect peer dialog ──
        if (showConnectDialog) {
            ConnectPeerDialog(
                onDismiss = { showConnectDialog = false },
                onConnect = { url ->
                    vm.connectToPeer(url)
                    showConnectDialog = false
                }
            )
        }
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────

@Composable
fun TopBar(
    state: MeshBrainUiState,
    onMeshClick: () -> Unit,
    onConnectClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderColor), shape = RectangleShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo / title
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "MESHBRAIN",
                    color = CyanAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 3.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Model indicator
                    StatusDot(color = if (state.isModelReady) GreenAccent else GoldAccent)
                    Text(
                        state.modelName,
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Peer count chip
            MeshChip(
                peerCount = state.peerCount,
                knowledgeCount = state.knowledgeAbsorbed,
                onClick = onMeshClick
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Connect button
            IconButton(onClick = onConnectClick) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = "Connect peer",
                    tint = CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Clear button
            IconButton(onClick = onClearClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear history",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Mesh Panel ────────────────────────────────────────────────────────

@Composable
fun MeshPanel(state: MeshBrainUiState, onConnectClick: () -> Unit) {
    Surface(
        color = Color(0xFF040A14),
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderColor), RectangleShape)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MeshStat("PEERS", state.peerCount.toString(), CyanAccent)
                MeshStat("KNOWLEDGE", state.knowledgeAbsorbed.toString(), GreenAccent)
                MeshStat("NODE ID", state.nodeIdShort, GoldAccent)
                MeshStat("MERKLE", state.merkleRoot.take(8) + "..", VioletAccent)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Recent mesh events
            Text(
                "// MESH EVENTS",
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.recentMeshEvents.takeLast(8).forEach { event ->
                    Text(
                        event,
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (state.recentMeshEvents.isEmpty()) {
                    Text(
                        "Waiting for peers...",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Peer list
            if (state.peers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "// CONNECTED PEERS",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                state.peers.forEach { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusDot(color = GreenAccent)
                        Text(
                            peer.nodeId.take(16) + "...",
                            color = CyanAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        // Reputation bar
                        val repColor = when {
                            peer.reputation > 0.7f -> GreenAccent
                            peer.reputation > 0.4f -> GoldAccent
                            else -> RedAccent
                        }
                        Text(
                            "rep=${"%.2f".format(peer.reputation)}",
                            color = repColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ── Chat Bubble ───────────────────────────────────────────────────────

@Composable
fun ChatBubble(msg: ChatMessage) {
    when (msg.role) {
        MessageRole.USER -> UserBubble(msg)
        MessageRole.ASSISTANT -> AssistantBubble(msg)
        MessageRole.SYSTEM -> SystemMessage(msg)
    }
}

@Composable
fun UserBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF0F3060), Color(0xFF0A1F40))
                        )
                    )
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .padding(12.dp, 10.dp)
            ) {
                Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text(
                msg.timeStr,
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun AssistantBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Brain icon
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(SurfaceDark)
                .border(1.dp, CyanAccent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🧠", fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, if (msg.isStreaming) CyanAccent.copy(alpha = 0.4f) else BorderColor,
                            RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .padding(12.dp, 10.dp)
            ) {
                if (msg.content.isEmpty() && msg.isStreaming) {
                    // Typing indicator
                    TypingDots()
                } else {
                    Text(
                        msg.content + if (msg.isStreaming) "▋" else "",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            // Footer row
            if (!msg.isStreaming && msg.content.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        msg.timeStr,
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (msg.qualityScore > 0) {
                        val qColor = when {
                            msg.qualityScore > 0.7f -> GreenAccent
                            msg.qualityScore > 0.5f -> GoldAccent
                            else -> RedAccent
                        }
                        Text(
                            "q=${"%.2f".format(msg.qualityScore)}",
                            color = qColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (msg.sharedToMesh) {
                        Text(
                            "mesh✓",
                            color = GreenAccent,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemMessage(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            msg.content,
            color = TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF040810))
                .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ── Input Bar ─────────────────────────────────────────────────────────

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        color = SurfaceDark,
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderColor), RectangleShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Ask anything...",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = CyanAccent.copy(alpha = 0.6f),
                    unfocusedBorderColor = BorderColor,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = CyanAccent,
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default,
                    color = TextPrimary
                ),
                maxLines = 4,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp)
            )

            // Send button
            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (enabled && text.isNotBlank())
                            Brush.linearGradient(listOf(CyanAccent, Color(0xFF0080AA)))
                        else
                            Brush.linearGradient(listOf(BorderColor, BorderColor))
                    )
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) BgDark else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Connect Dialog ────────────────────────────────────────────────────

@Composable
fun ConnectPeerDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var url by remember { mutableStateOf("ws://192.168.1.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        titleContentColor = CyanAccent,
        textContentColor = TextPrimary,
        title = {
            Text(
                "CONNECT TO PEER",
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter peer WebSocket URL\n(same WiFi network)",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("ws://192.168.1.X:8765/mesh") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "💡 Your IP shows in /status on the Python node",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(url) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text("CONNECT", color = BgDark, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

// ── Small Composables ─────────────────────────────────────────────────

@Composable
fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun MeshChip(peerCount: Int, knowledgeCount: Int, onClick: () -> Unit) {
    val isConnected = peerCount > 0
    val color = if (isConnected) GreenAccent else TextMuted

    Surface(
        onClick = onClick,
        color = if (isConnected) Color(0xFF0A2010) else Color(0xFF080D16),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            StatusDot(color)
            Text(
                if (isConnected) "$peerCount peers · $knowledgeCount✨" else "mesh offline",
                color = color,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun MeshStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 14.sp,
             fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 8.sp,
             fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .border(1.dp, CyanAccent.copy(0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TypingDots()
        }
    }
}

@Composable
fun TypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue  = 1f,
                animationSpec = infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 600,
                        delayMillis    = i * 150
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(CyanAccent.copy(alpha = alpha))
            )
        }
    }
}
