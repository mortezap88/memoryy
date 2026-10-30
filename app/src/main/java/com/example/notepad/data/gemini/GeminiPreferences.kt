package com.example.notepad.data.gemini

import android.content.Context
import com.example.network.ApiKeyRotation
import com.example.settings.ApiKeyProvider
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the notepad's AI settings onto FlashRecall's shared encrypted Houshyar24 API-key pool
 * (powering Gemini 3.8 Flash STT & linguistic analysis and Claude Haiku 5.5 main answers).
 */
class GeminiPreferences(context: Context) {

    private val settings = AppSettingsRepository(context.applicationContext)

    private val _apiKey = MutableStateFlow(resolveActiveKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()
    val avalaiApiKey: StateFlow<String> get() = apiKey

    private fun resolveActiveKey(): String = runCatching {
        val keys = settings.getApiKeys(ApiKeyProvider.HOUSHYAR24)
        if (keys.isEmpty()) {
            ""
        } else {
            keys[settings.activeKeyIndex(ApiKeyProvider.HOUSHYAR24).coerceIn(0, keys.lastIndex)]
        }
    }.getOrDefault("")

    fun refreshFromSharedSettings() {
        _apiKey.value = resolveActiveKey()
    }

    /** Returns `(poolIndex, apiKey)` pairs in active-first rotation order for Houshyar24. */
    fun getOrderedKeyCandidates(): List<Pair<Int, String>> = runCatching {
        val keys = settings.getApiKeys(ApiKeyProvider.HOUSHYAR24)
        if (keys.isEmpty()) return@runCatching emptyList()
        val activeIndex = settings.activeKeyIndex(ApiKeyProvider.HOUSHYAR24)
        ApiKeyRotation.order(keys.size, activeIndex).map { index -> index to keys[index] }
    }.getOrDefault(emptyList())

    fun markKeySucceeded(poolIndex: Int) {
        runCatching {
            if (poolIndex != settings.activeKeyIndex(ApiKeyProvider.HOUSHYAR24)) {
                settings.saveActiveKeyIndex(ApiKeyProvider.HOUSHYAR24, poolIndex)
                refreshFromSharedSettings()
            }
        }
    }

    fun updateSettings(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isNotEmpty() && trimmed != _apiKey.value) {
            runCatching { settings.addApiKey(ApiKeyProvider.HOUSHYAR24, trimmed) }
        }
        refreshFromSharedSettings()
    }
}
