package dev.xiaonuoboard.app.data

import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings
import dev.xiaonuoboard.app.i18n.ResolvedLanguage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Schema of `assets/builtin_packs/manifest.json` (written by scripts/generate_builtin_packs.py). */
@Serializable
data class BuiltinManifest(
    val version: Int = 1,
    val canvas: CanvasSize = CanvasSize(),
    val categories: List<ManifestCategory> = emptyList(),
    val pages: List<ManifestPage> = emptyList(),
) {
    companion object {
        const val ASSET_DIR = "builtin_packs"
        const val ASSET_PATH = "$ASSET_DIR/manifest.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BuiltinManifest = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CanvasSize(val width: Int = 1200, val height: Int = 1600)

@Serializable
data class ManifestCategory(
    val id: String,
    val title: String,
    @SerialName("title_zh") val titleZh: String = "",
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("title_ja") val titleJa: String = "",
) {
    fun localizedTitle(lang: ResolvedLanguage): String = when (lang) {
        ResolvedLanguage.ZH -> titleZh.ifBlank { title }
        ResolvedLanguage.EN -> titleEn.ifBlank { title }
        ResolvedLanguage.JA -> titleJa.ifBlank { title }
    }
}

@Serializable
data class ManifestPage(
    val id: String,
    val category: String,
    val index: Int,
    val title: String,
    @SerialName("title_zh") val titleZh: String = "",
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("title_ja") val titleJa: String = "",
    val difficulty: String = "easy",
    val line: String,
    val ref: String? = null,
    val regions: Int = 0,
    val palette: List<String> = emptyList(),
) {
    fun localizedTitle(lang: ResolvedLanguage): String = when (lang) {
        ResolvedLanguage.ZH -> titleZh.ifBlank { title }
        ResolvedLanguage.EN -> titleEn.ifBlank { title }
        ResolvedLanguage.JA -> titleJa.ifBlank { title }
    }
}

enum class PageSource { BUILTIN, AI, IMPORTED }

/** A coloring template (line art + optional pixel-aligned reference). */
data class ColoringPage(
    val id: String,
    val category: String,
    val title: String,
    val titleZh: String = title,
    val titleEn: String = title,
    val titleJa: String = title,
    val difficulty: String,
    /** Asset path (BUILTIN) or absolute file path (AI / IMPORTED). */
    val linePath: String,
    val refPath: String?,
    val source: PageSource,
    val palette: List<Int> = emptyList(),
) {
    fun localizedTitle(lang: ResolvedLanguage): String = when (source) {
        PageSource.BUILTIN -> when (lang) {
            ResolvedLanguage.ZH -> titleZh.ifBlank { title }
            ResolvedLanguage.EN -> titleEn.ifBlank { title }
            ResolvedLanguage.JA -> titleJa.ifBlank { title }
        }
        PageSource.AI, PageSource.IMPORTED -> title
    }
}

/** Home tabs: 4 IP packs + Alphabet + AI Magic + My Works. */
object HomeTabs {
    const val AI = "ai"
    const val WORKS = "works"

    /** Tab glyph, drawn by the UI as a custom E-Ink vector icon (`KidIcons.TabGlyph`) — no emoji. */
    enum class Icon { SNOWFLAKE, DOG, PIG, BUN_FACE, LETTERS, MAGIC, GALLERY }

    data class Tab(val id: String, val icon: Icon) {
        fun title(strings: AppStrings = AppI18n.strings()): String = strings.tabTitle(id)
    }

    val ALL = listOf(
        Tab("elsa", Icon.SNOWFLAKE),
        Tab("bluey", Icon.DOG),
        Tab("peppa", Icon.PIG),
        Tab("anpanman", Icon.BUN_FACE),
        Tab("alphabet", Icon.LETTERS),
        Tab(AI, Icon.MAGIC),
        Tab(WORKS, Icon.GALLERY),
    )
}
