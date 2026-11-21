package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.update.AppUpdater
import dev.xiaonuoboard.app.update.CancelToken
import dev.xiaonuoboard.app.update.ReleaseParser
import dev.xiaonuoboard.app.update.SemanticVersion
import dev.xiaonuoboard.app.update.Sha256SumsParser
import dev.xiaonuoboard.app.update.UpdateErrorCode
import dev.xiaonuoboard.app.update.UpdateException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdaterTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("xiaonuo-ota-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun semVerComparesCorrectly() {
        assertTrue(SemanticVersion.isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-debug", "v1.0.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.2.0", "1.10.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.1", "v1.0.1"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.1-debug", "v1.0.1"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.2", "v1.0.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.1-rc1", "1.0.1"))
    }

    @Test
    fun sha256SumsParserHandlesStandardFormat() {
        val hash = "a".repeat(64)
        val parsed = Sha256SumsParser.parse(
            """
            # checksums
            $hash  *dist/xiaonuo-board-v1.0.1.apk
            invalid line
            """.trimIndent(),
        )
        assertEquals(hash, parsed["xiaonuo-board-v1.0.1.apk"])
        assertTrue(Sha256SumsParser.isValidHex64(hash))
        assertFalse(Sha256SumsParser.isValidHex64("xyz"))
    }

    @Test
    fun checkAndDownloadWithSha256Succeeds() {
        val apkBytes = buildFakeApk()
        val sha256 = sha256Hex(apkBytes)
        val apkUrl = server.url("/download/xiaonuo-board-v1.0.1.apk").toString()
        val sumUrl = server.url("/download/SHA256SUMS.txt").toString()

        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.0.1",
                  "body": "Added OTA updater",
                  "html_url": "https://github.com/codescv/xiaonuo-board/releases/tag/v1.0.1",
                  "assets": [
                    {"name": "xiaonuo-board-v1.0.1.apk", "browser_download_url": "$apkUrl", "size": ${apkBytes.size}},
                    {"name": "SHA256SUMS.txt", "browser_download_url": "$sumUrl", "size": 90}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("$sha256  xiaonuo-board-v1.0.1.apk\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))

        val updater = AppUpdater(
            latestUrl = server.url("/releases/latest").toString(),
            listUrl = server.url("/releases").toString(),
            allowInsecureLoopback = true,
        )
        val check = updater.checkLatestRelease("1.0.0")
        assertTrue(check.updateAvailable)
        assertNotNull(check.apk)
        assertEquals(sha256, check.expectedSha256)

        val dest = File(tempDir, "xiaonuo-board-1.0.1.apk")
        var validated = false
        val out = updater.downloadApk(
            url = check.apk!!.url,
            dest = dest,
            expectedSize = check.apk!!.size,
            expectedSha256 = check.expectedSha256,
            validator = { validated = true },
        )
        assertTrue(validated)
        assertTrue(out.isFile)
        assertEquals(apkBytes.size.toLong(), out.length())
    }

    @Test
    fun downloadRejectsSha256MismatchAndCleansTmp() {
        val apkBytes = buildFakeApk()
        val apkUrl = server.url("/download/xiaonuo-board-v1.0.1.apk").toString()
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))

        val updater = AppUpdater(allowInsecureLoopback = true)
        val dest = File(tempDir, "bad.apk")
        try {
            updater.downloadApk(
                url = apkUrl,
                dest = dest,
                expectedSize = apkBytes.size.toLong(),
                expectedSha256 = "0".repeat(64),
            )
            fail("Expected VERIFY_FAILED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.VERIFY_FAILED, e.code)
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".tmp").exists())
    }

    @Test
    fun downloadRejectsNonApkPayload() {
        val apkUrl = server.url("/download/bad.apk").toString()
        server.enqueue(MockResponse().setBody("not a zip file"))
        val updater = AppUpdater(allowInsecureLoopback = true)
        try {
            updater.downloadApk(url = apkUrl, dest = File(tempDir, "bad.apk"))
            fail("Expected CORRUPT_DOWNLOAD")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
        }
    }

    @Test
    fun rejectsInsecureNonLoopbackUrl() {
        val updater = AppUpdater(
            latestUrl = "http://evil.example.com/releases/latest",
            allowInsecureLoopback = false,
        )
        try {
            updater.checkLatestRelease("1.0.0")
            fail("Expected INSECURE_URL")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.INSECURE_URL, e.code)
        }
    }

    @Test
    fun handlesRateLimit429() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val updater = AppUpdater(
            latestUrl = server.url("/releases/latest").toString(),
            allowInsecureLoopback = true,
        )
        try {
            updater.checkLatestRelease("1.0.0")
            fail("Expected RATE_LIMITED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.RATE_LIMITED, e.code)
        }
    }

    @Test
    fun respectsCancelToken() {
        val cancel = CancelToken().apply { cancel() }
        val updater = AppUpdater(
            latestUrl = server.url("/releases/latest").toString(),
            allowInsecureLoopback = true,
        )
        try {
            updater.checkLatestRelease("1.0.0", cancel = cancel)
            fail("Expected CANCELLED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.CANCELLED, e.code)
        }
    }

    @Test
    fun releaseParserRejectsUntrustedAssetHost() {
        val r = ReleaseParser.parseLatest(
            """
            {
              "tag_name": "v1.0.1",
              "assets": [
                {"name": "xiaonuo-board-v1.0.1.apk", "browser_download_url": "https://evil.example.com/xiaonuo-board-v1.0.1.apk"}
              ]
            }
            """.trimIndent(),
        )
        assertNull(ReleaseParser.pickApk(r, allowInsecureLoopback = false))
    }

    private fun buildFakeApk(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"dev.xiaonuoboard.app\"/>".toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
