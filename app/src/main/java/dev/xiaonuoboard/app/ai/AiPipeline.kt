package dev.xiaonuoboard.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.engine.CanvasSpec
import dev.xiaonuoboard.app.engine.RefAligner
import dev.xiaonuoboard.app.engine.RegionMapEngine
import java.io.ByteArrayOutputStream

/**
 * AI 魔法画坊 3-step pipeline:
 *  1. Gemini → thick-black-line closed coloring page (optionally from a photo),
 *  2. on-device [LineArtSanitizer] (binarize + despeckle + gap closing + region QC),
 *  3. Gemini colorization of the sanitized line art, then [RefAligner] flattening so the reference
 *     is 100% pixel-aligned with the line art.
 */
class AiPipeline(private val client: GeminiImageClient) {

    class Output(val line: Bitmap, val ref: Bitmap?, val regions: Int, val qcPassed: Boolean, val note: String)

    fun interface Progress {
        fun step(index: Int, message: String)
    }

    fun run(prompt: String, photo: Bitmap? = null, colorize: Boolean = true, progress: Progress? = null): Output {
        val w = CanvasSpec.WIDTH
        val h = CanvasSpec.HEIGHT
        progress?.step(1, "① 正在生成黑白封闭线稿…")
        val inputs = photo?.let { listOf(InlineImage("image/jpeg", jpeg(it))) } ?: emptyList()
        val raw = client.generate(prompt, inputs).firstImage
        val rawBmp = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
            ?: throw GeminiException("无法解码模型返回的线稿图片")

        progress?.step(2, "② 本地二值化去噪 + 闭运算封口质检…")
        val fitted = BitmapIO.fitToCanvas(rawBmp, w, h)
        if (fitted !== rawBmp) rawBmp.recycle()
        val clean = LineArtSanitizer.sanitize(BitmapIO.pixels(fitted), w, h)
        fitted.recycle()
        val lineBmp = Bitmap.createBitmap(LineArtSanitizer.toLineArgb(clean.ink, transparentPaper = false), w, h, Bitmap.Config.ARGB_8888)

        var ref: Bitmap? = null
        var note = "线稿质检：${clean.regionCount} 块可填色区域"
        if (colorize) {
            progress?.step(3, "③ 正在生成像素对齐的参考涂色图…")
            try {
                val colored = client.generate(PromptBuilder.COLORIZE, listOf(InlineImage("image/png", png(lineBmp)))).firstImage
                val colBmp = BitmapFactory.decodeByteArray(colored.bytes, 0, colored.bytes.size)
                if (colBmp != null) {
                    val map = RegionMapEngine.label(clean.ink, w, h)
                    val colFitted = BitmapIO.fitToCanvas(colBmp, w, h)
                    if (colFitted !== colBmp) colBmp.recycle()
                    val flat = RefAligner.flatten(BitmapIO.pixels(colFitted), map, clean.ink)
                    colFitted.recycle()
                    ref = Bitmap.createBitmap(flat, w, h, Bitmap.Config.ARGB_8888)
                }
            } catch (e: GeminiException) {
                note += "（参考图生成失败：${e.message}）"
            }
        }
        return Output(lineBmp, ref, clean.regionCount, clean.passesQc, note)
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().use {
        b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val scale = minOf(1f, 1536f / maxOf(b.width, b.height))
        val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        try {
            return ByteArrayOutputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        } finally {
            if (s !== b) s.recycle()
        }
    }

    companion object {
        /** Local import path (no network): sanitize an arbitrary picture into closed line art. Does not recycle [src]. */
        fun importLocal(src: Bitmap): Output {
            val w = CanvasSpec.WIDTH
            val h = CanvasSpec.HEIGHT
            val fitted = BitmapIO.fitToCanvas(src, w, h)
            val px = BitmapIO.pixels(fitted)
            if (fitted !== src) fitted.recycle()
            val clean = LineArtSanitizer.sanitize(px, w, h)
            val lineBmp = Bitmap.createBitmap(LineArtSanitizer.toLineArgb(clean.ink, transparentPaper = false), w, h, Bitmap.Config.ARGB_8888)
            // A colored source picture doubles as the reference (flattened to be pixel-aligned);
            // a black & white scan has no meaningful reference.
            val ref = if (dev.xiaonuoboard.app.engine.PaletteExtractor.extract(px, stride = 7).size >= 3) {
                val map = RegionMapEngine.label(clean.ink, w, h)
                Bitmap.createBitmap(RefAligner.flatten(px, map, clean.ink), w, h, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
            return Output(lineBmp, ref, clean.regionCount, clean.passesQc, "导入线稿：${clean.regionCount} 块可填色区域")
        }
    }
}
