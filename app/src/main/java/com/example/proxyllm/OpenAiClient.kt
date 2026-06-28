package com.example.proxyllm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        explicitNulls = false
    }
) {
    suspend fun sendChat(baseUrl: String, apiKey: String, request: ChatRequest): ChatReply {
        val httpRequest = Request.Builder()
            .url(chatEndpoint(baseUrl))
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(
                buildChatRequestBody(request)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()

        val raw = executeAsync(httpRequest, "聊天请求失败")
        val parsed = json.decodeFromString<ChatResponse>(raw)
        val message = parsed.choices.firstOrNull()?.message
            ?: error("接口已返回成功，但没有收到有效回复")
        return ChatReply(
            content = message.content.trim(),
            reasoningContent = message.reasoningContent?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url(modelsEndpoint(baseUrl))
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()

        val raw = executeAsync(request, "模型列表获取失败")
        val parsed = json.decodeFromString<ModelListResponse>(raw)
        return parsed.data
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    suspend fun testConnectivity(baseUrl: String, apiKey: String): String {
        val request = Request.Builder()
            .url(modelsEndpoint(baseUrl))
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()

        val raw = executeAsync(request, "连通性测试失败")
        val parsed = json.decodeFromString<ModelListResponse>(raw)
        return "连通性正常，当前检测到 ${parsed.data.size} 个模型"
    }

    private suspend fun executeAsync(request: Request, action: String): String {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(httpError(it.code, raw, action))
                        } else {
                            continuation.resume(raw)
                        }
                    }
                }
            })
        }
    }

    private fun chatEndpoint(baseUrl: String): String {
        val root = baseRoot(baseUrl)
        return if (root.endsWith("/chat/completions")) root else root.trimEnd('/') + "/chat/completions"
    }

    private fun modelsEndpoint(baseUrl: String): String {
        val root = baseRoot(baseUrl)
        return if (root.endsWith("/models")) root else root.trimEnd('/') + "/models"
    }

    private fun baseRoot(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized.removeSuffix("/chat/completions")
            normalized.endsWith("/models") -> normalized.removeSuffix("/models")
            normalized.endsWith("/responses") -> normalized.removeSuffix("/responses")
            else -> normalized
        }.trimEnd('/')
    }

    private fun buildChatRequestBody(request: ChatRequest): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("temperature", JsonPrimitive(request.temperature))
            request.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                put("reasoning_effort", JsonPrimitive(it))
            }
            put("messages", buildJsonArray {
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(message.role))
                        if (message.imageDataUri != null) {
                            put("content", buildJsonArray {
                                if (message.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(message.content))
                                    })
                                }
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put("image_url", buildJsonObject {
                                        put("url", JsonPrimitive(message.imageDataUri))
                                    })
                                })
                            })
                        } else {
                            put("content", JsonPrimitive(message.content))
                        }
                    })
                }
            })
        }
        return payload.toString()
    }

    private fun httpError(code: Int, body: String, action: String): IOException {
        val snippet = body.replace(Regex("\\s+"), " ").trim().take(260)
        val message = when (code) {
            401 -> "$action: API Key 无效、缺失或已过期"
            403 -> {
                if (snippet.contains("Cloudflare", ignoreCase = true)) {
                    "$action: 接口返回了 Cloudflare 或站点防护页，通常说明当前地址不是可直接调用的 API，或者请求被站点防护拦截"
                } else {
                    "$action: 服务器拒绝访问，可能是权限不足、模型无权限或地址不对"
                }
            }
            404 -> "$action: 接口路径不存在，请检查 Base URL 是否填写了正确的 OpenAI 兼容地址，例如以 /v1 结尾"
            408 -> "$action: 请求超时，请检查网络连接、代理或 VPN 状态"
            429 -> "$action: 请求过于频繁或触发了限流，请稍后再试"
            500, 502, 503, 504 -> "$action: 服务器暂时不可用，可能是上游服务异常"
            else -> if (snippet.isBlank()) "$action: HTTP $code" else "$action: HTTP $code: $snippet"
        }
        return IOException(message)
    }
}
