package dev.xiaonuoboard.app.i18n

import java.util.Locale

/** User-selectable UI language preference persisted in SettingsStore. */
enum class AppLanguage(val storageKey: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
    JA("ja"),
    ;

    fun resolve(systemLocale: Locale = Locale.getDefault()): ResolvedLanguage = when (this) {
        ZH -> ResolvedLanguage.ZH
        EN -> ResolvedLanguage.EN
        JA -> ResolvedLanguage.JA
        SYSTEM -> resolveSystemLocale(systemLocale)
    }

    companion object {
        fun fromStorageKey(raw: String?): AppLanguage {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT) ?: return SYSTEM
            return entries.firstOrNull {
                it.storageKey == normalized || it.name.lowercase(Locale.ROOT) == normalized
            } ?: SYSTEM
        }

        fun resolveSystemLocale(locale: Locale): ResolvedLanguage {
            val lang = locale.language.lowercase(Locale.ROOT)
            return when {
                lang == "zh" || lang.startsWith("zh-") || lang.startsWith("zh_") -> ResolvedLanguage.ZH
                lang == "ja" || lang.startsWith("ja-") || lang.startsWith("ja_") -> ResolvedLanguage.JA
                else -> ResolvedLanguage.EN
            }
        }
    }
}

/** Active concrete UI language after resolving [AppLanguage.SYSTEM]. */
enum class ResolvedLanguage(val code: String) {
    ZH("zh"),
    EN("en"),
    JA("ja"),
    ;

    fun toLocale(): Locale = when (this) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        JA -> Locale.JAPANESE
    }
}

/**
 * Process-wide reactive bridge so non-Compose layers (AI client, OTA updater, BOOX bridge,
 * exporters) always emit messages in the active UI language without needing a Compose context.
 */
object AppI18n {
    @Volatile
    private var configuredLanguage: AppLanguage = AppLanguage.SYSTEM

    @Volatile
    private var resolvedOverride: ResolvedLanguage? = null

    fun setLanguage(language: AppLanguage) {
        configuredLanguage = language
        resolvedOverride = null
    }

    fun setResolved(language: ResolvedLanguage) {
        resolvedOverride = language
    }

    fun resetForTest() {
        configuredLanguage = AppLanguage.SYSTEM
        resolvedOverride = null
    }

    fun resolved(systemLocale: Locale = Locale.getDefault()): ResolvedLanguage =
        resolvedOverride ?: configuredLanguage.resolve(systemLocale)

    fun strings(language: ResolvedLanguage = resolved()): AppStrings = AppStrings.forLanguage(language)
}
