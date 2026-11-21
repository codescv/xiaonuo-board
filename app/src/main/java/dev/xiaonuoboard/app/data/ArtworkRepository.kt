package dev.xiaonuoboard.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Artwork(
    val id: String,
    val pageId: String,
    val pageTitle: String,
    /** Copy number of this template ("#1", "#2" …). */
    val copyNo: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val progress: Float = 0f,
    val exported: Boolean = false,
) {
    val completed: Boolean get() = progress >= 0.9f
}

@Serializable
private data class ArtworkIndex(val works: List<Artwork> = emptyList())

/**
 * "我的作品": multiple artwork copies per template. Paint layers are stored as transparent PNGs in
 * `filesDir/works/{id}.png` alongside a JSON index; a small composite thumbnail `{id}_thumb.png`
 * is written for the gallery.
 */
class ArtworkRepository(context: Context) {
    private val dir = File(context.filesDir, "works").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private val state = MutableStateFlow(loadIndex())
    val works: StateFlow<List<Artwork>> = state.asStateFlow()

    private fun loadIndex(): List<Artwork> = runCatching {
        json.decodeFromString(ArtworkIndex.serializer(), indexFile.readText()).works
    }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }

    private fun persist(list: List<Artwork>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(ArtworkIndex.serializer(), ArtworkIndex(sorted)))
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true); tmp.delete()
        }
        state.value = sorted
    }

    fun paintFile(id: String) = File(dir, "$id.png")
    fun thumbFile(id: String) = File(dir, "${id}_thumb.png")

    fun get(id: String): Artwork? = state.value.firstOrNull { it.id == id }

    fun latestFor(pageId: String): Artwork? = state.value.filter { it.pageId == pageId }.maxByOrNull { it.updatedAt }

    fun hasWork(pageId: String): Boolean = state.value.any { it.pageId == pageId }

    fun create(page: ColoringPage): Artwork = synchronized(lock) {
        val now = System.currentTimeMillis()
        val copyNo = (state.value.filter { it.pageId == page.id }.maxOfOrNull { it.copyNo } ?: 0) + 1
        val art = Artwork("w_${now}_${(0..999).random()}", page.id, page.title, copyNo, now, now)
        persist(state.value + art)
        art
    }

    /** Auto-save of the paint layer (call off the main thread). */
    fun save(id: String, paint: Bitmap, thumb: Bitmap?, progress: Float) = synchronized(lock) {
        BitmapIO.savePng(paint, paintFile(id))
        thumb?.let { BitmapIO.savePng(it, thumbFile(id)) }
        val list = state.value.map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis(), progress = progress) else it }
        persist(list)
    }

    fun markExported(id: String) = synchronized(lock) {
        persist(state.value.map { if (it.id == id) it.copy(exported = true) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        paintFile(id).delete()
        thumbFile(id).delete()
        persist(state.value.filterNot { it.id == id })
    }

    fun loadPaint(id: String): Bitmap? = paintFile(id).takeIf { it.exists() }?.let { f ->
        BitmapIO.decode(f.inputStream())
    }
}
