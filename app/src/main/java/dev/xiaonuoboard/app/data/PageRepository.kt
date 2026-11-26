package dev.xiaonuoboard.app.data

import android.content.Context
import android.graphics.Bitmap
import dev.xiaonuoboard.app.engine.PaletteExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Metadata stored next to each AI-generated / imported page (`filesDir/ai_packs/{id}/meta.json`). */
@Serializable
data class CustomPageMeta(
    val id: String,
    val title: String,
    val source: String,
    val createdAt: Long,
    val palette: List<String> = emptyList(),
    val hasRef: Boolean = true,
    val regions: Int = 0,
)

/**
 * Single source of coloring templates: built-in asset packs (manifest), AI-generated pages
 * (permanent offline cache in `filesDir/ai_packs/`) and locally imported images.
 */
class PageRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val aiDir: File get() = File(context.filesDir, "ai_packs").apply { mkdirs() }

    val manifest: BuiltinManifest by lazy {
        runCatching {
            context.assets.open(BuiltinManifest.ASSET_PATH).bufferedReader().use { BuiltinManifest.parse(it.readText()) }
        }.getOrElse { BuiltinManifest() }
    }

    val builtinPages: List<ColoringPage> by lazy {
        manifest.pages.sortedWith(compareBy({ it.category }, { it.index })).map { p ->
            ColoringPage(
                id = p.id,
                category = p.category,
                title = p.title,
                titleZh = p.titleZh.ifBlank { p.title },
                titleEn = p.titleEn.ifBlank { p.title },
                titleJa = p.titleJa.ifBlank { p.title },
                difficulty = p.difficulty,
                linePath = p.line,
                refPath = p.ref,
                source = PageSource.BUILTIN,
                palette = p.palette.mapNotNull { PaletteExtractor.parseHex(it) },
            )
        }
    }

    fun pagesFor(category: String): List<ColoringPage> = when (category) {
        HomeTabs.AI -> customPages()
        else -> builtinPages.filter { it.category == category }
    }

    fun customPages(): List<ColoringPage> {
        val dirs = aiDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = runCatching {
                json.decodeFromString(CustomPageMeta.serializer(), File(dir, "meta.json").readText())
            }.getOrNull() ?: return@mapNotNull null
            val line = File(dir, "line.png")
            if (!line.exists()) return@mapNotNull null
            val ref = File(dir, "ref.png").takeIf { it.exists() }
            meta.createdAt to ColoringPage(
                id = meta.id,
                category = HomeTabs.AI,
                title = meta.title,
                difficulty = if (meta.source == "imported") "imported" else "ai",
                linePath = line.absolutePath,
                refPath = ref?.absolutePath,
                source = if (meta.source == "imported") PageSource.IMPORTED else PageSource.AI,
                palette = meta.palette.mapNotNull { PaletteExtractor.parseHex(it) },
            )
        }.sortedByDescending { it.first }.map { it.second }
    }

    fun findBuiltinPage(id: String): ColoringPage? =
        builtinPages.firstOrNull { it.id == id }

    fun findPage(id: String): ColoringPage? =
        findBuiltinPage(id) ?: customPages().firstOrNull { it.id == id }

    /** Persists a new custom page (AI result or local import). Bitmaps must already be 1200×1600. */
    fun saveCustomPage(title: String, line: Bitmap, ref: Bitmap?, source: String, regions: Int): ColoringPage {
        val id = "${if (source == "imported") "imp" else "ai"}_${System.currentTimeMillis()}"
        val dir = File(aiDir, id).apply { mkdirs() }
        BitmapIO.savePng(line, File(dir, "line.png"))
        val palette = ref?.let { PaletteExtractor.extract(BitmapIO.pixels(it), stride = 3) } ?: emptyList()
        if (ref != null) BitmapIO.savePng(ref, File(dir, "ref.png"))
        val meta = CustomPageMeta(
            id = id, title = title, source = source, createdAt = System.currentTimeMillis(),
            palette = palette.map { PaletteExtractor.toHex(it) }, hasRef = ref != null, regions = regions,
        )
        File(dir, "meta.json").writeText(json.encodeToString(CustomPageMeta.serializer(), meta))
        return findPage(id)!!
    }

    fun deleteCustomPage(id: String) {
        File(aiDir, id).takeIf { it.isDirectory && id.isNotBlank() }?.deleteRecursively()
    }
}
