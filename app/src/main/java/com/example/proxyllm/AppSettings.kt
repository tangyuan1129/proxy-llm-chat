package com.example.proxyllm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val baseUrl: String = "https://api.example.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val deepThinkingEnabled: Boolean = false
)

fun AppSettings.isReady(): Boolean {
    return baseUrl.isNotBlank() && apiKey.isNotBlank()
}

class SettingsRepository(private val context: Context) {
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")
    private val keyDeepThinking = booleanPreferencesKey("deep_thinking_enabled")

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[keyBaseUrl] ?: AppSettings().baseUrl,
            apiKey = prefs[keyApiKey] ?: AppSettings().apiKey,
            model = prefs[keyModel] ?: AppSettings().model,
            deepThinkingEnabled = prefs[keyDeepThinking] ?: AppSettings().deepThinkingEnabled
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[keyBaseUrl] = settings.baseUrl.trim()
            prefs[keyApiKey] = settings.apiKey.trim()
            prefs[keyModel] = settings.model.trim()
            prefs[keyDeepThinking] = settings.deepThinkingEnabled
        }
    }
}
