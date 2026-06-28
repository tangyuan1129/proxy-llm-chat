@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.proxyllm

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                ProxyChatApp()
            }
        }
    }
}

private class ChatViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appContext = context.applicationContext
        return ChatViewModel(
            settingsRepository = SettingsRepository(appContext),
            sessionRepository = SessionRepository(appContext),
            client = OpenAiClient()
        ) as T
    }
}

@Composable
fun ProxyChatApp() {
    val context = LocalContext.current
    val factory = remember(context) { ChatViewModelFactory(context) }
    val viewModel: ChatViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var composerDeepThinkingEnabled by rememberSaveable { mutableStateOf(state.settings.deepThinkingEnabled) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri).orEmpty().ifBlank { "image/*" }
            val dataUri = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    "data:$mimeType;base64,$encoded"
                }
            }.getOrNull()
            val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "image" } ?: "image"
            if (dataUri != null) {
                viewModel.attachImageFromUri(name, dataUri)
            }
        }
    }

    LaunchedEffect(state.settings.deepThinkingEnabled) {
        composerDeepThinkingEnabled = state.settings.deepThinkingEnabled
    }

    val filteredSessions = remember(state.sessions, historyQuery) {
        val query = historyQuery.trim()
        state.sessions
            .sortedByDescending { it.createdAt }
            .filter { session ->
                query.isBlank() ||
                    session.title.contains(query, ignoreCase = true) ||
                    session.model.contains(query, ignoreCase = true) ||
                    session.messages.any { it.content.contains(query, ignoreCase = true) }
            }
    }

    val activeSession = state.sessions.firstOrNull { it.id == state.activeSessionId }
    val visibleMessages = activeSession?.messages.orEmpty()

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                query = historyQuery,
                sessions = filteredSessions,
                activeSessionId = state.activeSessionId,
                onQueryChange = { historyQuery = it },
                onSessionClick = {
                    scope.launch { drawerState.close() }
                    viewModel.selectSession(it)
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    viewModel.newChat()
                },
                onDeleteSession = { pendingDelete = it },
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF8FAFF), Color(0xFFF5F5FB), Color(0xFFF8F7FC))
                    )
                )
                .navigationBarsPadding()
                .imePadding(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatTopBar(
                    session = activeSession,
                    settings = state.settings,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewChat = viewModel::newChat,
                    onSettingsClick = { showSettings = true }
                )

                AnimatedVisibility(visible = state.errorMessage != null || state.infoMessage != null) {
                    StatusBanner(
                        error = state.errorMessage,
                        info = state.infoMessage,
                        onDismiss = viewModel::dismissStatus
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (visibleMessages.isEmpty()) {
                        EmptyState(
                            model = state.settings.model,
                            onNewChat = viewModel::newChat,
                            onDetectModels = viewModel::refreshModels,
                            onTestConnection = viewModel::testConnectivity
                        )
                    } else {
                        MessagesList(
                            messages = visibleMessages,
                            listState = listState,
                            typingMessageId = state.typingMessageId,
                            partialAssistantText = state.partialAssistantText,
                            collapsedReasoningMessageIds = state.collapsedReasoningMessageIds,
                            onToggleReasoning = viewModel::toggleReasoningVisibility
                        )
                    }
                }

                ComposerBar(
                    text = state.input,
                    onTextChange = viewModel::onInputChange,
                    onSend = viewModel::sendMessage,
                    onStop = viewModel::stopSending,
                    isSending = state.isSending,
                    attachedImage = state.attachedImage,
                    deepThinkingEnabled = composerDeepThinkingEnabled,
                    onDeepThinkingChange = {
                        composerDeepThinkingEnabled = it
                        viewModel.updateDeepThinkingEnabled(it)
                    },
                    onPickImage = { imagePickerLauncher.launch(arrayOf("image/*")) },
                    onClearImage = viewModel::clearAttachedImage
                )
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState
        ) {
            SettingsSheet(
                settings = state.settings,
                availableModels = state.availableModels,
                isLoadingModels = state.isLoadingModels,
                isTestingConnection = state.isTestingConnection,
                statusMessage = state.errorMessage ?: state.infoMessage,
                statusIsError = state.errorMessage != null,
                onClose = { showSettings = false },
                onSave = { baseUrl, apiKey, model ->
                    viewModel.saveSettings(baseUrl, apiKey, model, composerDeepThinkingEnabled)
                },
                onDetectModels = viewModel::refreshModels,
                onTestConnection = viewModel::testConnectivity,
                onSelectModel = viewModel::selectModel
            )
        }
    }

    pendingDelete?.let { sessionId ->
        val session = state.sessions.firstOrNull { it.id == sessionId }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("删除会话") },
            text = { Text("确定删除「${session?.title ?: "这条会话"}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deleteSession(sessionId)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    session: ChatSession?,
    settings: AppSettings,
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = session?.title ?: "新对话",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${settings.model.ifBlank { "未选择模型" }} · ${session?.messages?.size ?: 0} 条消息",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Outlined.Menu, contentDescription = "打开历史")
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Outlined.Add, contentDescription = "新对话")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = Color(0xFF111827)
        )
    )
}

@Composable
private fun StatusBanner(error: String?, info: String?, onDismiss: () -> Unit) {
    val message = error ?: info ?: return
    val isError = error != null
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (isError) Color(0xFFFFEEF1) else Color(0xFFEFF4FF)),
        border = BorderStroke(1.dp, if (isError) Color(0xFFF3C0C8) else Color(0xFFC9D8FF))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.Info else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (isError) Color(0xFFDC4E5A) else Color(0xFF2F63D9)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = message, modifier = Modifier.weight(1f), color = Color(0xFF1F2937))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun EmptyState(
    model: String,
    onNewChat: () -> Unit,
    onDetectModels: () -> Unit,
    onTestConnection: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE7EAF4))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("准备开始对话", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111827))
                Text("当前模型：$model", color = Color(0xFF6B7280), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AssistChip(onClick = onNewChat, label = { Text("新对话") })
                    AssistChip(onClick = onDetectModels, label = { Text("检测模型") })
                    AssistChip(onClick = onTestConnection, label = { Text("测试连通性") })
                }
            }
        }
    }
}

@Composable
private fun HistoryDrawer(
    query: String,
    sessions: List<ChatSession>,
    activeSessionId: String,
    onQueryChange: (String) -> Unit,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "历史会话", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索会话") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4F46E5),
                    unfocusedBorderColor = Color(0xFFD1D5DB)
                )
            )
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("新对话")
            }
            HorizontalDivider()
            if (sessions.isEmpty()) {
                Text(text = "没有找到会话", color = Color(0xFF6B7280), modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            selected = session.id == activeSessionId,
                            onClick = { onSessionClick(session.id) },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEFF2FF) else Color.White),
        border = BorderStroke(1.dp, if (selected) Color(0xFFB9C7FF) else Color(0xFFE4E7EE))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, contentDescription = null, tint = Color(0xFF4F46E5))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = session.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = session.model, fontSize = 12.sp, color = Color(0xFF6B7280), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除会话") }
        }
    }
}

@Composable
private fun MessagesList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    typingMessageId: String?,
    partialAssistantText: String,
    collapsedReasoningMessageIds: Set<String>,
    onToggleReasoning: (String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                typingMessageId = typingMessageId,
                partialAssistantText = partialAssistantText,
                collapsedReasoningMessageIds = collapsedReasoningMessageIds,
                onToggleReasoning = onToggleReasoning
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    typingMessageId: String?,
    partialAssistantText: String,
    collapsedReasoningMessageIds: Set<String>,
    onToggleReasoning: (String) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        SelectionContainer {
            Surface(
                modifier = Modifier.widthIn(max = 340.dp),
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = if (isUser) 22.dp else 8.dp,
                    bottomEnd = if (isUser) 8.dp else 22.dp
                ),
                color = if (isUser) Color(0xFF4F46E5) else Color.White,
                border = if (isUser) null else BorderStroke(1.dp, Color(0xFFE4E7EE))
            ) {
                if (isUser) {
                    UserMessageContent(message)
                } else {
                    AssistantMessageContent(
                        message = message,
                        typingMessageId = typingMessageId,
                        partialAssistantText = partialAssistantText,
                        collapsedReasoningMessageIds = collapsedReasoningMessageIds,
                        onToggleReasoning = onToggleReasoning
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageContent(message: ChatMessage) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!message.imageDataUri.isNullOrBlank()) {
            ImagePreview(message.imageDataUri)
        }
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
    }
}

@Composable
private fun AssistantMessageContent(
    message: ChatMessage,
    typingMessageId: String?,
    partialAssistantText: String,
    collapsedReasoningMessageIds: Set<String>,
    onToggleReasoning: (String) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!message.reasoningContent.isNullOrBlank() || message.thinkingDurationMs != null) {
            ThinkingSummaryCard(
                message = message,
                collapsed = message.id in collapsedReasoningMessageIds,
                onToggle = { onToggleReasoning(message.id) }
            )
        }
        FormattedAssistantBody(
            content = if (message.id == typingMessageId) partialAssistantText else message.content
        )
    }
}

@Composable
private fun ThinkingSummaryCard(
    message: ChatMessage,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FF)),
        border = BorderStroke(1.dp, Color(0xFFD9E0FF)),
        modifier = Modifier.animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .clickable { onToggle() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (collapsed) "思考已折叠" else "正在思考",
                    color = Color(0xFF3F4BCB),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (collapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color(0xFF3F4BCB)
                )
            }
            Text(
                text = when {
                    message.thinkingDurationMs != null -> "用时 ${message.thinkingDurationMs} ms"
                    collapsed -> "点击展开思考内容"
                    else -> "点击折叠思考内容"
                },
                color = Color(0xFF6B7280),
                fontSize = 12.sp
            )
            if (!collapsed && !message.reasoningContent.isNullOrBlank()) {
                Text(
                    text = message.reasoningContent,
                    color = Color(0xFF1F2937),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun FormattedAssistantBody(content: String) {
    val blocks = remember(content) { parseChatBlocks(content) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ChatBlock.Text -> Text(
                    text = block.value,
                    color = Color(0xFF111827),
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                is ChatBlock.Code -> Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F7FB)),
                    border = BorderStroke(1.dp, Color(0xFFE1E5EF))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (block.language.isNotBlank()) {
                            Text(
                                text = block.language,
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            color = Color(0xFF1F2937)
                        )
                    }
                }
            }
        }
    }
}

private sealed interface ChatBlock {
    data class Text(val value: String) : ChatBlock
    data class Code(val language: String, val code: String) : ChatBlock
}

private fun parseChatBlocks(content: String): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var language = ""

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) {
            blocks += ChatBlock.Text(text)
        }
        paragraph.clear()
    }

    fun flushCode() {
        val text = code.toString().trimEnd()
        if (text.isNotBlank()) {
            blocks += ChatBlock.Code(language, text)
        }
        code.clear()
        language = ""
    }

    content.replace("\r\n", "\n").split("\n").forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
                language = trimmed.removePrefix("```").trim()
            }
        } else if (inCode) {
            code.appendLine(line)
        } else if (line.isBlank()) {
            flushParagraph()
        } else {
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
        }
    }

    if (inCode) flushCode()
    flushParagraph()

    return if (blocks.isEmpty()) listOf(ChatBlock.Text(content.trim())) else blocks
}

@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isSending: Boolean,
    attachedImage: ImageAttachment?,
    deepThinkingEnabled: Boolean,
    onDeepThinkingChange: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5E8F2))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = onPickImage) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "选择图片")
                }
                FilterChip(
                    selected = deepThinkingEnabled,
                    onClick = { onDeepThinkingChange(!deepThinkingEnabled) },
                    label = { Text("深度思考") },
                    leadingIcon = { if (deepThinkingEnabled) Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEFF2FF),
                        selectedLabelColor = Color(0xFF3F4BCB),
                        selectedLeadingIconColor = Color(0xFF3F4BCB)
                    )
                )
            }

            if (attachedImage != null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFF)),
                    border = BorderStroke(1.dp, Color(0xFFE5E8F2))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选择图片",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF111827)
                        )
                        TextButton(onClick = onClearImage) { Text("移除") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息给 AI") },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4F46E5),
                        unfocusedBorderColor = Color(0xFFD6DAE5),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        cursorColor = Color(0xFF4F46E5)
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (isSending) onStop() else onSend() }),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 5
                )

                Button(
                    onClick = if (isSending) onStop else onSend,
                    enabled = (text.isNotBlank() || attachedImage != null) || isSending,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSending) Color(0xFFDC4E5A) else Color(0xFF4F46E5),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE1E5F2),
                        disabledContentColor = Color(0xFF8B93A7)
                    )
                ) {
                    if (isSending) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = "停止生成")
                    } else {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "发送")
                    }
                }
            }
        }
    }
}
@Composable
private fun ImagePreview(dataUri: String) {
    val bitmap = remember(dataUri) {
        runCatching {
            val base64 = dataUri.substringAfter("base64,", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "图片预览",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun SettingsSheet(
    settings: AppSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    isTestingConnection: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    onClose: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDetectModels: () -> Unit,
    onTestConnection: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "设置",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        if (statusMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (statusIsError) Color(0xFFFFEEF1) else Color(0xFFEFF4FF)
                ),
                border = BorderStroke(1.dp, if (statusIsError) Color(0xFFF3C0C8) else Color(0xFFC9D8FF))
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFF1F2937)
                )
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4F46E5),
                unfocusedBorderColor = Color(0xFFD1D5DB)
            )
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4F46E5),
                unfocusedBorderColor = Color(0xFFD1D5DB)
            )
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4F46E5),
                unfocusedBorderColor = Color(0xFFD1D5DB)
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onDetectModels,
                enabled = !isLoadingModels,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text("检测模型")
                }
            }
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !isTestingConnection,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF4F46E5)
                    )
                } else {
                    Text("测试连通性")
                }
            }
        }

        if (availableModels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("检测到的模型", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableModels.forEach { item ->
                        FilterChip(
                            selected = item == model,
                            onClick = {
                                model = item
                                onSelectModel(item)
                            },
                            label = { Text(item) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEFF2FF),
                                selectedLabelColor = Color(0xFF3F4BCB)
                            )
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                onSave(baseUrl, apiKey, model)
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("保存设置")
        }
    }
}
