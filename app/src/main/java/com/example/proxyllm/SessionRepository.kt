package com.example.proxyllm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sessionDataStore by preferencesDataStore(name = "chat_sessions")

class SessionRepository(private val context: Context) {
    data class SessionState(
        val sessions: List<ChatSession> = listOf(defaultSession()),
        val activeSessionId: String = DEFAULT_SESSION_ID
    )

    private val keySessions = stringPreferencesKey("sessions_json")
    private val keyActiveSession = stringPreferencesKey("active_session_id")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        explicitNulls = false
    }

    val stateFlow: Flow<SessionState> = context.sessionDataStore.data.map { prefs ->
        val storedSessions = prefs[keySessions]
            ?.let { runCatching { json.decodeFromString<List<ChatSession>>(it) }.getOrNull() }
            .orEmpty()

        val sessions = if (storedSessions.isEmpty()) listOf(defaultSession()) else storedSessions

        val activeSessionId = prefs[keyActiveSession]
            ?.takeIf { id -> sessions.any { it.id == id } }
            ?: sessions.first().id

        SessionState(sessions = sessions, activeSessionId = activeSessionId)
    }

    suspend fun save(state: SessionState) {
        val sessions = state.sessions.ifEmpty { listOf(defaultSession()) }
        val activeSessionId = state.activeSessionId.takeIf { id -> sessions.any { it.id == id } }
            ?: sessions.first().id

        context.sessionDataStore.edit { prefs ->
            prefs[keySessions] = json.encodeToString(sessions)
            prefs[keyActiveSession] = activeSessionId
        }
    }

    companion object {
        const val DEFAULT_SESSION_ID: String = "default"

        fun defaultSession(): ChatSession = ChatSession(
            id = DEFAULT_SESSION_ID,
            title = "新对话",
            model = AppSettings().model,
            messages = emptyList(),
            createdAt = 0L
        )

        fun newSession(model: String): ChatSession = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            model = model,
            messages = emptyList(),
            createdAt = System.currentTimeMillis()
        )
    }
}
