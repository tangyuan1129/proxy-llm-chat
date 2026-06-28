package com.example.proxyllm

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val imageDataUri: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("thinking_duration_ms")
    val thinkingDurationMs: Long? = null
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val reasoningEffort: String? = null
)

@Serializable
data class ImageAttachment(
    val name: String,
    val dataUri: String
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChatReply(
    val content: String,
    val reasoningContent: String? = null
)

@Serializable
data class ModelListResponse(
    val data: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val id: String
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val model: String,
    val messages: List<ChatMessage>,
    val createdAt: Long
)
