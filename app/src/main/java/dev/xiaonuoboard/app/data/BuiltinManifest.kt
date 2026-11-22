package dev.xiaonuoboard.app.data

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
data class ManifestCategory(val id: String, val title: String)

@Serializable
data class ManifestPage(
    val id: String,
    val category: String,
    val index: Int,
    val title: String,
    val difficulty: String = "easy",
    val line: String,
    val ref: String? = null,
    val regions: Int = 0,
    val palette: List<String> = emptyList(),
)

enum class PageSource { BUILTIN, AI, IMPORTED }

/** A coloring template (line art + optional pixel-aligned reference). */
data class ColoringPage(
    val id: String,
    val category: String,
    val title: String,
    val difficulty: String,
    /** Asset path (BUILTIN) or absolute file path (AI / IMPORTED). */
    val linePath: String,
    val refPath: String?,
    val source: PageSource,
    val palette: List<Int> = emptyList(),
)

/** Home tabs: 4 IP packs + 字母 + AI 魔法 + 我的作品. */
object HomeTabs {
    const val AI = "ai"
    const val WORKS = "works"

    /** Tab glyph, drawn by the UI as a custom E-Ink vector icon (`KidIcons.TabGlyph`) — no emoji. */
    enum class Icon { SNOWFLAKE, DOG, PIG, BUN_FACE, LETTERS, MAGIC, GALLERY }

    data class Tab(val id: String, val title: String, val icon: Icon)

    val ALL = listOf(
        Tab("elsa", "艾莎", Icon.SNOWFLAKE),
        Tab("bluey", "布鲁伊", Icon.DOG),
        Tab("peppa", "小猪佩奇", Icon.PIG),
        Tab("anpanman", "面包超人", Icon.BUN_FACE),
        Tab("alphabet", "字母", Icon.LETTERS),
        Tab(AI, "AI 魔法", Icon.MAGIC),
        Tab(WORKS, "我的作品", Icon.GALLERY),
    )
}
