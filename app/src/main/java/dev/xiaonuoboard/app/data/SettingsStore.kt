package dev.xiaonuoboard.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.xiaonuoboard.app.ai.GeminiConfig
import dev.xiaonuoboard.app.ai.GeminiModels
import dev.xiaonuoboard.app.eink.EinkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = GeminiModels.DEFAULT_BASE_URL,
    val model: String = GeminiModels.DEFAULT,
    val einkMode: EinkMode = EinkMode.REGAL,
    val soundEnabled: Boolean = false,
    val stylusOnly: Boolean = false,
    val stayInsideLines: Boolean = true,
    val ghostUnderlay: Boolean = false,
) {
    fun geminiConfig() = GeminiConfig(apiKey, baseUrl, model)
}

/** Parent settings persisted in private SharedPreferences (never leaves the device). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("xiaonuo_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    val current: AppSettings get() = state.value

    private fun load() = AppSettings(
        apiKey = prefs.getString(K_KEY, "") ?: "",
        baseUrl = prefs.getString(K_BASE, GeminiModels.DEFAULT_BASE_URL) ?: GeminiModels.DEFAULT_BASE_URL,
        model = prefs.getString(K_MODEL, GeminiModels.DEFAULT) ?: GeminiModels.DEFAULT,
        einkMode = runCatching { EinkMode.valueOf(prefs.getString(K_EINK, EinkMode.REGAL.name)!!) }.getOrDefault(EinkMode.REGAL),
        soundEnabled = prefs.getBoolean(K_SOUND, false),
        stylusOnly = prefs.getBoolean(K_STYLUS, false),
        stayInsideLines = prefs.getBoolean(K_INSIDE, true),
        ghostUnderlay = prefs.getBoolean(K_GHOST, false),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(state.value)
        prefs.edit()
            .putString(K_KEY, s.apiKey)
            .putString(K_BASE, s.baseUrl)
            .putString(K_MODEL, s.model)
            .putString(K_EINK, s.einkMode.name)
            .putBoolean(K_SOUND, s.soundEnabled)
            .putBoolean(K_STYLUS, s.stylusOnly)
            .putBoolean(K_INSIDE, s.stayInsideLines)
            .putBoolean(K_GHOST, s.ghostUnderlay)
            .apply()
        state.value = s
    }

    private companion object {
        const val K_KEY = "gemini_api_key"
        const val K_BASE = "gemini_base_url"
        const val K_MODEL = "gemini_model"
        const val K_EINK = "eink_mode"
        const val K_SOUND = "sound_enabled"
        const val K_STYLUS = "stylus_only"
        const val K_INSIDE = "stay_inside"
        const val K_GHOST = "ghost_underlay"
    }
}
