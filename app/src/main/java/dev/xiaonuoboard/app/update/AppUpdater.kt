package dev.xiaonuoboard.app.update

import dev.xiaonuoboard.app.i18n.AppI18n
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

enum class UpdateErrorCode {
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    NOT_FOUND,
    NO_ASSET,
    BAD_JSON,
    INSECURE_URL,
    CORRUPT_DOWNLOAD,
    VERIFY_FAILED,
    CANCELLED,
}

class UpdateException(
    val code: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause)

class CancelToken {
    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var abortHook: (() -> Unit)? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun attach(onCancel: () -> Unit) {
        abortHook = onCancel
        if (cancelled.get()) runCatching { onCancel() }
    }

    fun detach() {
        abortHook = null
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            runCatching { abortHook?.invoke() }
        }
    }
}

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = -1L,
    @SerialName("content_type") val contentType: String? = null,
)

@Serializable
data class ReleaseJson(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val cleanVersion: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")
}

data class UpdateCheckResult(
    val currentVersion: String,
    val latest: ReleaseJson,
    val apk: ReleaseAsset?,
    val updateAvailable: Boolean,
    /** Lower-case hex SHA-256 of [apk] from `SHA256SUMS.txt` when attached, else null. */
    val expectedSha256: String? = null,
)

object SemanticVersion {
    private val CORE = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""")

    fun stripDebugSuffix(version: String): String {
        val t = version.trim()
        return if (t.endsWith("-debug", ignoreCase = true)) t.dropLast("-debug".length) else t
    }

    /** True iff [candidate] is strictly newer than [current] under SemVer (ignoring `-debug`). */
    fun isNewerVersion(current: String, candidate: String): Boolean {
        val c1 = parse(stripDebugSuffix(current)) ?: return false
        val c2 = parse(stripDebugSuffix(candidate)) ?: return false
        if (c2.major != c1.major) return c2.major > c1.major
        if (c2.minor != c1.minor) return c2.minor > c1.minor
        if (c2.patch != c1.patch) return c2.patch > c1.patch
        // Stable (null prerelease) > prerelease for same major.minor.patch.
        return when {
            c1.pre == null && c2.pre == null -> false
            c1.pre != null && c2.pre == null -> true
            c1.pre == null && c2.pre != null -> false
            else -> c2.pre!! > c1.pre!!
        }
    }

    private data class Parsed(val major: Int, val minor: Int, val patch: Int, val pre: String?)

    private fun parse(raw: String): Parsed? {
        val m = CORE.matchEntire(raw.trim()) ?: return null
        return Parsed(
            major = m.groupValues[1].toIntOrNull() ?: return null,
            minor = m.groupValues[2].toIntOrNull() ?: return null,
            patch = m.groupValues[3].toIntOrNull() ?: return null,
            pre = m.groupValues[4].takeIf { it.isNotEmpty() },
        )
    }
}

/** Parses `sha256sum`-style `SHA256SUMS.txt` (`<64 hex>  [*]<filename>`). */
object Sha256SumsParser {
    private val HEX_64 = Regex("""^[0-9a-fA-F]{64}$""")
    private val LINE_SPLIT = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

    fun parse(rawText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (rawLine in rawText.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val m = LINE_SPLIT.matchEntire(trimmed) ?: continue
            val hash = m.groupValues[1].lowercase(Locale.ROOT)
            val filePart = m.groupValues[2].trim().replace('\\', '/').substringAfterLast('/')
            if (filePart.isNotEmpty()) out[filePart.lowercase(Locale.ROOT)] = hash
        }
        return out
    }

    fun isValidHex64(s: String?): Boolean = s != null && HEX_64.matches(s.trim())
}

object ReleaseParser {
    const val REPO_SLUG = "codescv/xiaonuo-board"
    const val DEFAULT_RELEASE_HTML_URL = "https://github.com/$REPO_SLUG/releases"
    const val ALLOWED_ASSET_PREFIX = "https://github.com/$REPO_SLUG/releases/download/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val PREFERRED_APK = Regex("""^xiaonuo-board-.*\.apk$""", RegexOption.IGNORE_CASE)

    fun parseLatest(rawJson: String): ReleaseJson {
        val parsed = try {
            json.decodeFromString<ReleaseJson>(rawJson)
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.BAD_JSON, "Invalid GitHub release JSON", e)
        }
        if (parsed.tagName.isBlank()) throw UpdateException(UpdateErrorCode.BAD_JSON, "Missing tag_name")
        if (parsed.draft) throw UpdateException(UpdateErrorCode.NOT_FOUND, "Latest release is a draft")
        return parsed
    }

    fun parseList(rawJson: String): ReleaseJson? {
        val list = try {
            json.decodeFromString<List<ReleaseJson>>(rawJson)
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.BAD_JSON, "Invalid GitHub releases array JSON", e)
        }
        return list.firstOrNull { !it.draft && !it.prerelease && it.tagName.isNotBlank() }
    }

    fun pickApk(r: ReleaseJson, allowInsecureLoopback: Boolean = false): ReleaseAsset? {
        val allowed = r.assets.filter {
            isAllowedAssetUrl(it.url, allowInsecureLoopback) && it.name.endsWith(".apk", ignoreCase = true)
        }
        return allowed.firstOrNull { PREFERRED_APK.matches(it.name) } ?: allowed.firstOrNull()
    }

    fun pickChecksums(r: ReleaseJson, allowInsecureLoopback: Boolean = false): ReleaseAsset? =
        r.assets.firstOrNull {
            isAllowedAssetUrl(it.url, allowInsecureLoopback) && it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        }

    internal fun isAllowedAssetUrl(rawUrl: String, allowInsecureLoopback: Boolean): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith(ALLOWED_ASSET_PREFIX, ignoreCase = true)) return true
        if (allowInsecureLoopback) {
            @Suppress("DEPRECATION")
            val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
            val proto = parsed.protocol?.lowercase(Locale.ROOT) ?: return false
            return (proto == "http" || proto == "https") && (host == "127.0.0.1" || host == "localhost")
        }
        return false
    }
}

/**
 * Pure-JVM GitHub Releases OTA updater (modeled on MiniFile's updater).
 * Enforces HTTPS, manual redirect cap (≤5 hops, no https→http downgrade), 2 MiB JSON cap,
 * download size & ZIP/APK structure verification (`PK\x03\x04` + `AndroidManifest.xml`),
 * optional `SHA256SUMS.txt` digest check, caller package/signature [validator], and atomic rename.
 */
class AppUpdater(
    private val latestUrl: String = DEFAULT_LATEST_URL,
    private val listUrl: String = DEFAULT_LIST_URL,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val allowInsecureLoopback: Boolean = false,
) {
    companion object {
        const val DEFAULT_LATEST_URL = "https://api.github.com/repos/${ReleaseParser.REPO_SLUG}/releases/latest"
        const val DEFAULT_LIST_URL = "https://api.github.com/repos/${ReleaseParser.REPO_SLUG}/releases?per_page=5"

        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_CHECKSUM_BYTES = 256 * 1024
        const val MAX_UNKNOWN_SIZE_BYTES = 256L * 1024 * 1024

        private const val PROGRESS_INTERVAL_MS = 120L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024

        fun sha256HexOfFile(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    fun checkLatestRelease(
        currentVersion: String,
        cancel: CancelToken? = null,
        timeoutMs: Int = 15_000,
    ): UpdateCheckResult {
        val baseVersion = SemanticVersion.stripDebugSuffix(currentVersion)
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to userAgent(baseVersion),
        )
        val latest = try {
            val parsed = ReleaseParser.parseLatest(fetchText(latestUrl, headers, cancel, timeoutMs, MAX_JSON_BYTES))
            if (parsed.prerelease) {
                ReleaseParser.parseList(fetchText(listUrl, headers, cancel, timeoutMs, MAX_JSON_BYTES))
                    ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No stable release found")
            } else {
                parsed
            }
        } catch (e: UpdateException) {
            if (e.code == UpdateErrorCode.NOT_FOUND && listUrl.isNotBlank()) {
                ReleaseParser.parseList(fetchText(listUrl, headers, cancel, timeoutMs, MAX_JSON_BYTES))
                    ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No stable release found", e)
            } else {
                throw e
            }
        }

        val apk = ReleaseParser.pickApk(latest, allowInsecureLoopback = allowInsecureLoopback)
        val newer = SemanticVersion.isNewerVersion(baseVersion, latest.tagName)
        var expectedSha256: String? = null
        val checksumsAsset = ReleaseParser.pickChecksums(latest, allowInsecureLoopback = allowInsecureLoopback)
        if (newer && apk != null && checksumsAsset != null) {
            val sumsRaw = fetchText(
                url = checksumsAsset.url,
                headers = headers + ("Accept" to "application/octet-stream"),
                cancel = cancel,
                timeoutMs = timeoutMs,
                maxBytes = MAX_CHECKSUM_BYTES,
            )
            expectedSha256 = Sha256SumsParser.parse(sumsRaw)[apk.name.lowercase(Locale.ROOT)]
                ?: throw UpdateException(UpdateErrorCode.VERIFY_FAILED, "SHA256SUMS.txt missing entry for ${apk.name}")
        }
        return UpdateCheckResult(
            currentVersion = baseVersion,
            latest = latest,
            apk = apk,
            updateAvailable = newer,
            expectedSha256 = expectedSha256,
        )
    }

    fun downloadApk(
        url: String,
        dest: File,
        expectedSize: Long = -1L,
        cancel: CancelToken? = null,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        expectedSha256: String? = null,
        validator: (File) -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        if (expectedSha256 != null && !Sha256SumsParser.isValidHex64(expectedSha256)) {
            throw UpdateException(UpdateErrorCode.VERIFY_FAILED, "Invalid expected SHA-256 digest")
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.delete()

        val headers = mapOf(
            "Accept" to "application/octet-stream",
            "Accept-Encoding" to "identity",
            "User-Agent" to userAgent(null),
        )
        var conn: HttpURLConnection? = null
        var ok = false
        try {
            conn = openFollowingRedirects(url, headers, cancel, connectTimeoutMs, readTimeoutMs)
            val status = conn.responseCode
            if (status != 200) throw httpError(conn, status)

            if (conn.contentType.orEmpty().trim().startsWith("text/html", ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Server returned text/html instead of APK")
            }
            val contentLength = conn.contentLengthLong
            if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Content-Length ($contentLength) != expected ($expectedSize)")
            }
            val total = when {
                expectedSize > 0 -> expectedSize
                contentLength > 0 -> contentLength
                else -> -1L
            }
            val written = streamToFile(conn.inputStream, tmp, total, cancel, onProgress)
            verifyZipApk(tmp, written, total, expectedSize)
            if (expectedSha256 != null) {
                val actual = sha256HexOfFile(tmp)
                if (!MessageDigest.isEqual(
                        actual.toByteArray(Charsets.US_ASCII),
                        expectedSha256.trim().lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    throw UpdateException(
                        UpdateErrorCode.VERIFY_FAILED,
                        "SHA-256 mismatch (expected ${expectedSha256.take(12)}…, got ${actual.take(12)}…)",
                    )
                }
            }
            validator(tmp)

            dest.delete()
            if (!tmp.renameTo(dest)) {
                throw UpdateException(UpdateErrorCode.NETWORK, "Failed to finalize ${dest.name}")
            }
            ok = true
            return dest
        } catch (e: Exception) {
            throw mapError(e, cancel, readTimeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
            if (!ok) tmp.delete()
        }
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        timeoutMs: Int,
        maxBytes: Int,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(url, headers, cancel, timeoutMs, timeoutMs)
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(conn, status)
            val text = readLimited(conn.inputStream, maxBytes, cancel)
            if (cancel?.isCancelled == true) throw cancelled()
            return text
        } catch (e: Exception) {
            throw mapError(e, cancel, timeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    private fun streamToFile(
        input: InputStream,
        tmp: File,
        total: Long,
        cancel: CancelToken?,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var written = 0L
        var lastReportBytes = 0L
        var lastReportAt = 0L
        var reported = false
        input.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancel?.isCancelled == true) throw cancelled()
                    val n = try {
                        ins.read(buf)
                    } catch (e: SocketTimeoutException) {
                        throw e
                    } catch (e: IOException) {
                        if (cancel?.isCancelled == true) throw cancelled()
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download interrupted at $written bytes", e)
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    written += n
                    if (total > 0 && written > total) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download exceeded expected size ($written > $total)")
                    }
                    if (total <= 0 && written > MAX_UNKNOWN_SIZE_BYTES) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download exceeded safety cap")
                    }
                    val now = System.currentTimeMillis()
                    if (!reported || now - lastReportAt >= PROGRESS_INTERVAL_MS || written - lastReportBytes >= PROGRESS_INTERVAL_BYTES) {
                        onProgress(written, total)
                        reported = true
                        lastReportAt = now
                        lastReportBytes = written
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        if (cancel?.isCancelled == true) throw cancelled()
        if (lastReportBytes != written || !reported) onProgress(written, total)
        return written
    }

    private fun verifyZipApk(tmp: File, written: Long, total: Long, expectedSize: Long) {
        if (total > 0 && written != total) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Incomplete download ($written / $total bytes)")
        }
        if (expectedSize > 0 && written != expectedSize) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Size mismatch ($written != $expectedSize bytes)")
        }
        val head = ByteArray(4)
        val n = if (written >= 4) tmp.inputStream().use { readFully(it, head) } else 0
        if (n < 4 || !head.contentEquals(APK_MAGIC)) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Missing APK/ZIP magic header")
        }
        val hasManifest = try {
            ZipFile(tmp).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Corrupt ZIP archive", e)
        }
        if (!hasManifest) throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Archive missing AndroidManifest.xml")
    }

    private fun openFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        @Suppress("DEPRECATION")
        var current = try { URL(url) } catch (e: MalformedURLException) {
            throw UpdateException(UpdateErrorCode.INSECURE_URL, "Invalid URL: $url", e)
        }
        validateAllowedUrl(current)
        var hops = 0
        while (true) {
            if (cancel?.isCancelled == true) throw cancelled()
            val conn = openConnection(current)
            var handedOff = false
            try {
                cancel?.attach { conn.disconnect() }
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs.coerceAtLeast(1_000)
                conn.readTimeout = readTimeoutMs.coerceAtLeast(1_000)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                val status = conn.responseCode
                if (status !in REDIRECT_CODES) {
                    handedOff = true
                    return conn
                }
                val location = conn.getHeaderField("Location")?.trim()
                if (location.isNullOrEmpty()) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Redirect missing Location (HTTP $status)", httpStatus = status)
                }
                if (++hops > MAX_REDIRECTS) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Too many redirects (>$MAX_REDIRECTS)")
                }
                @Suppress("DEPRECATION")
                val next = try { URL(current, location) } catch (e: MalformedURLException) {
                    throw UpdateException(UpdateErrorCode.INSECURE_URL, "Invalid redirect Location: $location", e, status)
                }
                if (current.protocol.equals("https", ignoreCase = true) && next.protocol.equals("http", ignoreCase = true)) {
                    throw UpdateException(UpdateErrorCode.INSECURE_URL, "Refusing https -> http redirect downgrade")
                }
                validateAllowedUrl(next)
                current = next
            } finally {
                if (!handedOff) {
                    cancel?.detach()
                    conn.disconnect()
                }
            }
        }
    }

    private fun validateAllowedUrl(url: URL) {
        val proto = url.protocol?.lowercase(Locale.ROOT).orEmpty()
        val host = url.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) throw UpdateException(UpdateErrorCode.INSECURE_URL, "URL missing host")
        if (proto == "https") return
        if (allowInsecureLoopback && proto == "http" && (host == "127.0.0.1" || host == "localhost")) return
        throw UpdateException(UpdateErrorCode.INSECURE_URL, "Refusing non-HTTPS URL: $url")
    }

    private fun httpError(conn: HttpURLConnection, status: Int): UpdateException {
        val body = runCatching { conn.errorStream?.use { readPrefix(it, 2048) } }.getOrNull().orEmpty()
        val rateLimited = status == 429 || (
            status == 403 && (
                conn.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" ||
                    body.contains("rate limit", ignoreCase = true)
                )
            )
        return when {
            rateLimited -> UpdateException(UpdateErrorCode.RATE_LIMITED, "GitHub API rate limit exceeded", httpStatus = status)
            status == 404 -> UpdateException(UpdateErrorCode.NOT_FOUND, "Release not found (404)", httpStatus = 404)
            else -> UpdateException(UpdateErrorCode.NETWORK, "HTTP $status: ${body.trim().take(120)}", httpStatus = status)
        }
    }

    private fun mapError(e: Exception, cancel: CancelToken?, timeoutMs: Int): UpdateException {
        if (cancel?.isCancelled == true) {
            return if (e is UpdateException && e.code == UpdateErrorCode.CANCELLED) e else cancelled()
        }
        if (e is UpdateException) return e
        return when (e) {
            is SocketTimeoutException -> UpdateException(UpdateErrorCode.TIMEOUT, "Request timed out ($timeoutMs ms)", e)
            is UnknownServiceException -> UpdateException(UpdateErrorCode.INSECURE_URL, "Cleartext HTTP not permitted", e)
            is UnknownHostException -> UpdateException(UpdateErrorCode.NETWORK, AppI18n.strings().updateErrCannotReachGithub, e)
            is IOException -> UpdateException(UpdateErrorCode.NETWORK, e.message ?: "Network error", e)
            else -> UpdateException(UpdateErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun cancelled() = UpdateException(UpdateErrorCode.CANCELLED, AppI18n.strings().updateErrCancelled)

    private fun userAgent(version: String?): String {
        val clean = version?.trim()?.replace(Regex("[^0-9A-Za-z._-]"), "")?.takeIf { it.isNotEmpty() }
        return if (clean != null) "XiaonuoBoard/$clean (Android)" else "XiaonuoBoard (Android)"
    }

    private fun readLimited(input: InputStream, limit: Int, cancel: CancelToken?): String {
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel?.isCancelled == true) throw cancelled()
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw UpdateException(UpdateErrorCode.BAD_JSON, "Response exceeds $limit bytes")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun readPrefix(input: InputStream, limit: Int): String {
        val buf = ByteArray(limit)
        val n = readFully(input, buf)
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
