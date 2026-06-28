package com.example.proxyllm

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val sessions: List<ChatSession> = listOf(SessionRepository.defaultSession()),
    val activeSessionId: String = SessionRepository.DEFAULT_SESSION_ID,
    val availableModels: List<String> = emptyList(),
    val input: String = "",
    val attachedImage: ImageAttachment? = null,
    val isSending: Boolean = false,
    val isLoadingModels: Boolean = false,
    val isTestingConnection: Boolean = false,
    val thinkingStartedAt: Long? = null,
    val typingMessageId: String? = null,
    val partialAssistantText: String = "",
    val collapsedReasoningMessageIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val currentRequestMessageId: String? = null,
    val currentAssistantJobActive: Boolean = false
)

class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val client: OpenAiClient
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                _state.update { current ->
                    val activeSession = current.sessions.firstOrNull { it.id == current.activeSessionId }
                    val sessionModel = activeSession?.model ?: settings.model
                    current.copy(settings = settings.copy(model = sessionModel))
                }
            }
        }

        viewModelScope.launch {
            sessionRepository.stateFlow.collectLatest { sessionState ->
                _state.update { current ->
                    val activeSession = sessionState.sessions.firstOrNull { it.id == sessionState.activeSessionId }
                    val mergedSettings = current.settings.copy(model = activeSession?.model ?: current.settings.model)
                    current.copy(
                        settings = mergedSettings,
                        sessions = sessionState.sessions,
                        activeSessionId = sessionState.activeSessionId
                    )
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun attachImage(name: String, dataUri: String) {
        _state.update {
            it.copy(
                attachedImage = ImageAttachment(name = name, dataUri = dataUri),
                errorMessage = null,
                infoMessage = "已添加图片"
            )
        }
    }

    fun clearAttachedImage() {
        _state.update { it.copy(attachedImage = null) }
    }

    fun dismissStatus() {
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun toggleReasoningVisibility(messageId: String) {
        _state.update { current ->
            val next = current.collapsedReasoningMessageIds.toMutableSet()
            if (!next.add(messageId)) {
                next.remove(messageId)
            }
            current.copy(collapsedReasoningMessageIds = next)
        }
    }

    fun saveSettings(baseUrl: String, apiKey: String, model: String, deepThinkingEnabled: Boolean) {
        viewModelScope.launch {
            val normalized = AppSettings(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { AppSettings().model },
                deepThinkingEnabled = deepThinkingEnabled
            )
            settingsRepository.save(normalized)
            replaceActiveSession { it.copy(model = normalized.model) }
            _state.update {
                it.copy(settings = normalized, errorMessage = null, infoMessage = "设置已保存")
            }
        }
    }

    fun attachImageFromUri(name: String, dataUri: String) {
        attachImage(name, dataUri)
    }

    fun refreshModels() {
        val settings = _state.value.settings
        if (!settings.isReady()) {
            _state.update { it.copy(errorMessage = "请先填写 Base URL 和 API Key") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoadingModels = true, errorMessage = null, infoMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    client.listModels(settings.baseUrl, settings.apiKey)
                }
            }.onSuccess { models ->
                _state.update { current ->
                    val cleaned = models.distinct().sorted()
                    val currentModel = current.settings.model
                    val info = when {
                        cleaned.isEmpty() -> "没有检测到可用模型"
                        cleaned.contains(currentModel) -> "已检测到 ${cleaned.size} 个模型，当前模型可用"
                        else -> "已检测到 ${cleaned.size} 个模型，当前模型不在列表中"
                    }
                    current.copy(
                        availableModels = cleaned,
                        isLoadingModels = false,
                        errorMessage = null,
                        infoMessage = info
                    )
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(isLoadingModels = false, errorMessage = throwable.message ?: "模型列表获取失败")
                }
            }
        }
    }

    fun testConnectivity() {
        val settings = _state.value.settings
        if (!settings.isReady()) {
            _state.update { it.copy(errorMessage = "请先填写 Base URL 和 API Key") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isTestingConnection = true, errorMessage = null, infoMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    client.testConnectivity(settings.baseUrl, settings.apiKey)
                }
            }.onSuccess { message ->
                _state.update {
                    it.copy(isTestingConnection = false, errorMessage = null, infoMessage = message)
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(isTestingConnection = false, errorMessage = throwable.message ?: "连通性测试失败")
                }
            }
        }
    }

    fun newChat() {
        viewModelScope.launch {
            val currentModel = _state.value.settings.model
            val newSession = SessionRepository.newSession(currentModel)
            val sessions = listOf(newSession) + _state.value.sessions
            sessionRepository.save(
                SessionRepository.SessionState(
                    sessions = sessions,
                    activeSessionId = newSession.id
                )
            )
            _state.update {
                it.copy(input = "", errorMessage = null, infoMessage = "已创建新对话")
            }
        }
    }

    fun selectSession(sessionId: String) {
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        viewModelScope.launch {
            val updatedSettings = _state.value.settings.copy(model = session.model)
            settingsRepository.save(updatedSettings)
            sessionRepository.save(
                SessionRepository.SessionState(
                    sessions = _state.value.sessions,
                    activeSessionId = sessionId
                )
            )
            _state.update {
                it.copy(
                    settings = updatedSettings,
                    activeSessionId = sessionId,
                    input = "",
                    errorMessage = null,
                    infoMessage = "已切换到「${session.title}」"
                )
            }
        }
    }

    fun selectModel(model: String) {
        viewModelScope.launch {
            val updatedSettings = _state.value.settings.copy(model = model)
            settingsRepository.save(updatedSettings)
            replaceActiveSession { it.copy(model = model) }
            _state.update {
                it.copy(settings = updatedSettings, errorMessage = null, infoMessage = "已切换到模型：$model")
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val current = _state.value
            val remaining = current.sessions.filterNot { it.id == sessionId }
            val nextSessions = if (remaining.isEmpty()) {
                listOf(SessionRepository.newSession(current.settings.model))
            } else {
                remaining
            }
            val nextActiveId = when {
                nextSessions.any { it.id == current.activeSessionId && it.id != sessionId } -> current.activeSessionId
                else -> nextSessions.first().id
            }
            sessionRepository.save(
                SessionRepository.SessionState(
                    sessions = nextSessions,
                    activeSessionId = nextActiveId
                )
            )
            val active = nextSessions.firstOrNull { it.id == nextActiveId } ?: nextSessions.first()
            val updatedSettings = current.settings.copy(model = active.model)
            settingsRepository.save(updatedSettings)
            _state.update {
                it.copy(settings = updatedSettings, input = "", errorMessage = null, infoMessage = "已删除会话")
            }
        }
    }

    fun clearCurrentChat() {
        val session = activeSession() ?: return
        viewModelScope.launch {
            updateSession(session.copy(messages = emptyList(), title = "新对话"))
            _state.update { it.copy(input = "", errorMessage = null, infoMessage = "当前聊天已清空") }
        }
    }

    fun useSuggestion(text: String) {
        _state.update { current ->
            val nextInput = if (current.input.isBlank()) text else "${current.input.trimEnd()} $text"
            current.copy(input = nextInput)
        }
    }

    fun stopSending() {
        sendJob?.cancel()
        sendJob = null
        _state.update {
            it.copy(
                isSending = false,
                thinkingStartedAt = null,
                currentRequestMessageId = null,
                currentAssistantJobActive = false,
                errorMessage = null,
                infoMessage = "已停止生成"
            )
        }
    }

    fun sendMessage() {
        val current = _state.value
        if (current.isSending) {
            stopSending()
            return
        }

        val content = current.input.trim()
        val attachedImage = current.attachedImage
        val session = activeSession(current) ?: return
        if ((content.isBlank() && attachedImage == null)) return
        if (!current.settings.isReady()) {
            _state.update { it.copy(errorMessage = "请先填写 Base URL 和 API Key") }
            return
        }

        val userMessage = ChatMessage(
            role = "user",
            content = content,
            imageDataUri = attachedImage?.dataUri
        )
        val messagesWithUser = session.messages + userMessage
        val sessionId = session.id
        val requestSettings = current.settings
        val startedAt = SystemClock.elapsedRealtime()
        val assistantId = UUID.randomUUID().toString()

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    input = "",
                    attachedImage = null,
                    isSending = true,
                    thinkingStartedAt = if (requestSettings.deepThinkingEnabled) startedAt else null,
                    currentRequestMessageId = assistantId,
                    currentAssistantJobActive = true,
                    typingMessageId = null,
                    partialAssistantText = "",
                    errorMessage = null,
                    infoMessage = null
                )
            }
            updateSession(session.copy(messages = messagesWithUser))

            var replyReasoning: String? = null
            val streamedText = StringBuilder()

            try {
                val reply = withContext(Dispatchers.IO) {
                    client.sendChat(
                        baseUrl = requestSettings.baseUrl,
                        apiKey = requestSettings.apiKey,
                        request = ChatRequest(
                            model = requestSettings.model,
                            messages = messagesWithUser,
                            reasoningEffort = if (requestSettings.deepThinkingEnabled) "high" else null
                        )
                    )
                }
                replyReasoning = reply.reasoningContent?.trim()?.takeIf { it.isNotBlank() }
                val thinkingDuration = if (requestSettings.deepThinkingEnabled) {
                    SystemClock.elapsedRealtime() - startedAt
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        typingMessageId = assistantId,
                        partialAssistantText = "",
                        currentAssistantJobActive = true,
                        infoMessage = if (replyReasoning == null && requestSettings.deepThinkingEnabled) {
                            "该模型/接口未返回思考内容"
                        } else null
                    )
                }

                reply.content.forEach { ch ->
                    streamedText.append(ch)
                    _state.update {
                        it.copy(
                            typingMessageId = assistantId,
                            partialAssistantText = streamedText.toString()
                        )
                    }
                    delay(14L)
                }

                val assistantMessage = ChatMessage(
                    id = assistantId,
                    role = "assistant",
                    content = streamedText.toString(),
                    reasoningContent = replyReasoning,
                    thinkingDurationMs = thinkingDuration
                )
                val baseMessages = messagesWithUser + assistantMessage
                updateSession(session.copy(messages = baseMessages, title = titleFrom(messagesWithUser)))
                _state.update {
                    it.copy(
                        isSending = false,
                        thinkingStartedAt = null,
                        typingMessageId = null,
                        partialAssistantText = "",
                        currentRequestMessageId = null,
                        currentAssistantJobActive = false,
                        errorMessage = null,
                        infoMessage = if (replyReasoning == null && requestSettings.deepThinkingEnabled) {
                            "该模型/接口未返回思考内容"
                        } else {
                            "已收到回复"
                        }
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) {
                    if (streamedText.isNotBlank()) {
                        val thinkingDuration = if (requestSettings.deepThinkingEnabled) {
                            SystemClock.elapsedRealtime() - startedAt
                        } else {
                            null
                        }
                        val assistantMessage = ChatMessage(
                            id = assistantId,
                            role = "assistant",
                            content = streamedText.toString(),
                            reasoningContent = replyReasoning,
                            thinkingDurationMs = thinkingDuration
                        )
                        updateSession(
                            session.copy(
                                messages = messagesWithUser + assistantMessage,
                                title = titleFrom(messagesWithUser)
                            )
                        )
                    }
                    _state.update {
                        it.copy(
                            isSending = false,
                            thinkingStartedAt = null,
                            typingMessageId = null,
                            partialAssistantText = "",
                            currentRequestMessageId = null,
                            currentAssistantJobActive = false,
                            errorMessage = null,
                            infoMessage = "已停止生成"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isSending = false,
                            thinkingStartedAt = null,
                            typingMessageId = null,
                            partialAssistantText = "",
                            currentRequestMessageId = null,
                            currentAssistantJobActive = false,
                            errorMessage = throwable.message ?: "发送失败",
                            input = content,
                            attachedImage = attachedImage
                        )
                    }
                    restoreSessionMessages(sessionId, messagesWithUser)
                }
            }
        }
    }

    private fun activeSession(state: ChatUiState = _state.value): ChatSession? {
        return state.sessions.firstOrNull { it.id == state.activeSessionId }
    }

    private fun updateSession(updated: ChatSession) {
        viewModelScope.launch {
            val sessions = _state.value.sessions.map { if (it.id == updated.id) updated else it }
            sessionRepository.save(
                SessionRepository.SessionState(
                    sessions = sessions,
                    activeSessionId = updated.id
                )
            )
        }
    }

    fun updateDeepThinkingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updated = _state.value.settings.copy(deepThinkingEnabled = enabled)
            settingsRepository.save(updated)
            _state.update { it.copy(settings = updated, errorMessage = null, infoMessage = null) }
        }
    }

    private fun restoreSessionMessages(sessionId: String, messages: List<ChatMessage>) {
        viewModelScope.launch {
            val updated = _state.value.sessions.map {
                if (it.id == sessionId) it.copy(messages = messages) else it
            }
            sessionRepository.save(
                SessionRepository.SessionState(
                    sessions = updated,
                    activeSessionId = sessionId
                )
            )
        }
    }

    private fun replaceActiveSession(transform: (ChatSession) -> ChatSession) {
        val session = activeSession() ?: return
        updateSession(transform(session))
    }

    private fun titleFrom(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == "user" }?.content.orEmpty().trim()
        return firstUser.takeIf { it.isNotBlank() }?.take(18) ?: "新对话"
    }
}
