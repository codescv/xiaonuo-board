package dev.xiaonuoboard.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** 1-tap HD PNG export into `Pictures/小诺画板/` via MediaStore (scoped storage on API 29+). */
object GalleryExporter {
    const val ALBUM = "小诺画板"

    /** @return a human-readable location string. */
    fun exportPng(context: Context, bitmap: Bitmap, displayName: String): String {
        val name = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册文件")
            try {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ?: throw IllegalStateException("无法写入相册")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        }
        return "Pictures/$ALBUM/$name"
    }
}

/** Optional gentle feedback sounds; silent by default (toggle in Parent Settings). */
class SoundFeedback(private val enabled: () -> Boolean) {
    private val tone: ToneGenerator? by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull() }

    fun fill() {
        if (enabled()) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }

    fun cheer() {
        if (enabled()) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
    }

    fun release() {
        runCatching { tone?.release() }
    }
}
