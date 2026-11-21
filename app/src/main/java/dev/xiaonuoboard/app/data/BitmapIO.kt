package dev.xiaonuoboard.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import dev.xiaonuoboard.app.engine.CanvasSpec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Bitmap helpers bridging Android bitmaps ↔ engine IntArray/BooleanArray buffers. */
object BitmapIO {

    fun openPageStream(context: Context, page: ColoringPage, ref: Boolean): InputStream? {
        val path = (if (ref) page.refPath else page.linePath) ?: return null
        return if (page.source == PageSource.BUILTIN) {
            context.assets.open("${BuiltinManifest.ASSET_DIR}/$path")
        } else {
            File(path).takeIf { it.exists() }?.inputStream()
        }
    }

    fun decode(stream: InputStream?, sample: Int = 1): Bitmap? = stream?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    fun loadPageBitmap(context: Context, page: ColoringPage, ref: Boolean, sample: Int = 1): Bitmap? =
        runCatching { decode(openPageStream(context, page, ref), sample) }.getOrNull()

    /** Scales/letterboxes any bitmap onto a white 1200×1600 canvas (keeps aspect ratio). */
    fun fitToCanvas(src: Bitmap, w: Int = CanvasSpec.WIDTH, h: Int = CanvasSpec.HEIGHT): Bitmap {
        if (src.width == w && src.height == h && src.config == Bitmap.Config.ARGB_8888) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        val left = (w - dw) / 2
        val top = (h - dh) / 2
        c.drawBitmap(src, null, Rect(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    /** Line art → ink mask (dark, opaque pixels are ink). */
    fun inkMask(line: Bitmap): BooleanArray {
        val px = pixels(line)
        return BooleanArray(px.size) { i ->
            val c = px[i]
            val a = c ushr 24
            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            a > 127 && lum < 128
        }
    }

    /** Ink mask → overlay bitmap (black ink, transparent paper) drawn on top of the paint layer. */
    fun inkOverlay(ink: BooleanArray, w: Int, h: Int): Bitmap {
        val px = IntArray(ink.size) { if (ink[it]) 0xFF111111.toInt() else 0 }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun decodeUri(context: Context, uri: Uri, maxDim: Int = 2400): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** White paper + paint + line art; the export / thumbnail composite. */
    fun composite(paint: Bitmap, lineOverlay: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(paint.width, paint.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(paint, 0f, 0f, null)
        c.drawBitmap(lineOverlay, 0f, 0f, null)
        return out
    }
}
