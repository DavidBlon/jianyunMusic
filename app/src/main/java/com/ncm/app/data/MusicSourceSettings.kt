package com.ncm.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User-owned paid music source credentials.
 *
 * The key is stored in the app-private preferences file and is never written to
 * logs, analytics, or BuildConfig.
 */
class MusicSourceSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _cardKey = MutableStateFlow(prefs.getString(KEY_CARD_KEY, "").orEmpty())
    val cardKey: StateFlow<String> = _cardKey

    private val _firstUsePromptCompleted = MutableStateFlow(
        prefs.getBoolean(KEY_FIRST_USE_PROMPT_COMPLETED, false)
    )
    val firstUsePromptCompleted: StateFlow<Boolean> = _firstUsePromptCompleted

    fun saveValidatedCardKey(value: String) {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "Card key must not be blank" }
        prefs.edit().putString(KEY_CARD_KEY, normalized).apply()
        _cardKey.value = normalized
    }

    fun clearCardKey() {
        prefs.edit().remove(KEY_CARD_KEY).apply()
        _cardKey.value = ""
    }

    fun completeFirstUsePrompt() {
        prefs.edit().putBoolean(KEY_FIRST_USE_PROMPT_COMPLETED, true).apply()
        _firstUsePromptCompleted.value = true
    }

    fun maskedCardKey(): String? {
        val value = _cardKey.value
        if (value.isBlank()) return null
        return "••••${value.takeLast(4)}"
    }

    private companion object {
        const val PREFS_NAME = "music_source_settings"
        const val KEY_CARD_KEY = "card_key"
        const val KEY_FIRST_USE_PROMPT_COMPLETED = "first_use_prompt_completed"
    }
}
