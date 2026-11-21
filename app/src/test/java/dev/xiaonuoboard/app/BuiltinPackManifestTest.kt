package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.data.BuiltinManifest
import dev.xiaonuoboard.app.data.HomeTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** Verifies the 66 bundled packs (40 IP + 26 alphabet) exist with valid line-art and reference assets. */
class BuiltinPackManifestTest {

    private val assetsRoot: File = listOf("src/main/assets", "app/src/main/assets")
        .map { File(it, BuiltinManifest.ASSET_DIR) }
        .firstOrNull { File(it, "manifest.json").exists() }
        ?: error("builtin_packs/manifest.json not found (cwd=${File(".").absolutePath})")

    private val manifest = BuiltinManifest.parse(File(assetsRoot, "manifest.json").readText())

    @Test
    fun hasSixtySixPagesAcrossFiveCategories() {
        assertEquals(1200, manifest.canvas.width)
        assertEquals(1600, manifest.canvas.height)
        assertEquals(66, manifest.pages.size)
        val byCat = manifest.pages.groupingBy { it.category }.eachCount()
        assertEquals(mapOf("elsa" to 10, "bluey" to 10, "peppa" to 10, "anpanman" to 10, "alphabet" to 26), byCat)
        assertEquals(66, manifest.pages.map { it.id }.toSet().size)
        // Every manifest category is a home tab.
        val tabIds = HomeTabs.ALL.map { it.id }.toSet()
        manifest.categories.forEach { assertTrue("tab for ${it.id}", it.id in tabIds) }
        assertEquals("字母", manifest.categories.first { it.id == "alphabet" }.title)
    }

    @Test
    fun alphabetCoversAaToZz() {
        val alpha = manifest.pages.filter { it.category == "alphabet" }.sortedBy { it.index }
        assertEquals((1..26).toList(), alpha.map { it.index })
        alpha.forEachIndexed { i, p ->
            val up = 'A' + i
            val pair = "$up${up.lowercaseChar()}"
            assertTrue("${p.id} title '${p.title}' should start with $pair", p.title.startsWith(pair))
        }
    }

    @Test
    fun ipPacksHaveFiveEasyAndFiveStandard() {
        for (cat in listOf("elsa", "bluey", "peppa", "anpanman")) {
            val pages = manifest.pages.filter { it.category == cat }.sortedBy { it.index }
            assertEquals((1..10).toList(), pages.map { it.index })
            assertEquals(5, pages.count { it.difficulty == "easy" })
            assertEquals(5, pages.count { it.difficulty == "standard" })
        }
    }

    @Test
    fun everyPageHasOneBitLineArtAndMatchingReference() {
        var totalBytes = 0L
        for (p in manifest.pages) {
            val line = File(assetsRoot, p.line)
            assertTrue("missing ${p.line}", line.isFile)
            val ihdr = readPngHeader(line)
            assertEquals("${p.id} line width", 1200, ihdr.width)
            assertEquals("${p.id} line height", 1600, ihdr.height)
            assertEquals("${p.id} line must be 1-bit", 1, ihdr.bitDepth)
            assertTrue("${p.id} line must be grayscale or palette", ihdr.colorType == 0 || ihdr.colorType == 3)

            val refPath = p.ref ?: error("${p.id} has no ref")
            val ref = File(assetsRoot, refPath)
            assertTrue("missing $refPath", ref.isFile)
            val (rw, rh) = imageSize(ref)
            assertEquals("${p.id} ref width", 1200, rw)
            assertEquals("${p.id} ref height", 1600, rh)

            assertTrue("${p.id} regions ${p.regions}", p.regions >= 3)
            assertEquals("${p.id} palette", 6, p.palette.size)
            p.palette.forEach { assertTrue("bad hex $it", Regex("^#[0-9A-Fa-f]{6}$").matches(it)) }
            assertTrue(p.title.isNotBlank())
            totalBytes += line.length() + ref.length()
        }
        assertTrue("bundled packs are ${totalBytes / 1024} KB", totalBytes < 12L * 1024 * 1024)
    }

    private data class Ihdr(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int)

    private fun readPngHeader(f: File): Ihdr = DataInputStream(f.inputStream().buffered()).use { s ->
        val sig = ByteArray(8).also { s.readFully(it) }
        assertTrue("${f.name} is not a PNG", sig.contentEquals(PNG_SIG))
        s.readInt() // chunk length
        val type = ByteArray(4).also { s.readFully(it) }
        assertEquals("IHDR", String(type))
        val w = s.readInt()
        val h = s.readInt()
        Ihdr(w, h, s.readUnsignedByte(), s.readUnsignedByte())
    }

    /** PNG or WebP (VP8L lossless / VP8 lossy / VP8X extended) dimensions. */
    private fun imageSize(f: File): Pair<Int, Int> {
        val b = f.readBytes()
        if (b.size > 24 && b.copyOfRange(0, 8).contentEquals(PNG_SIG)) {
            val ih = readPngHeader(f)
            return ih.width to ih.height
        }
        assertTrue("${f.name} is neither PNG nor WebP", String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP")
        fun u8(i: Int) = b[i].toInt() and 0xFF
        return when (val chunk = String(b, 12, 4)) {
            "VP8L" -> {
                val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8 " -> ((u8(26) or (u8(27) shl 8)) and 0x3FFF) to ((u8(28) or (u8(29) shl 8)) and 0x3FFF)
            "VP8X" -> (1 + (u8(24) or (u8(25) shl 8) or (u8(26) shl 16))) to (1 + (u8(27) or (u8(28) shl 8) or (u8(29) shl 16)))
            else -> error("unknown WebP chunk $chunk")
        }
    }

    private companion object {
        val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
