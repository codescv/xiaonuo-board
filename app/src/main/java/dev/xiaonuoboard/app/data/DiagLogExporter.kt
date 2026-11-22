package dev.xiaonuoboard.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.xiaonuoboard.app.BuildConfig
import dev.xiaonuoboard.app.eink.DiagLog
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Settings → 「导出诊断日志」 / 「分享日志」: writes the [DiagLog] ring buffer plus a device / app /
 * BOOX status header as a plain-text file. No new permissions: MediaStore.Downloads on API 29+,
 * the app-specific external Downloads dir on API 26–28, and the existing FileProvider for sharing.
 */
object DiagLogExporter {
    const val FOLDER = "XiaonuoBoard"
    private const val MIME = "text/plain"

    data class Result(val location: String, val entries: Int)

    /** `xiaonuo-diag-YYYYMMDD-HHmmss.txt` (pure; unit-tested). */
    fun fileName(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }
        return "xiaonuo-diag-${f.format(Date(nowMillis))}.txt"
    }

    /** Full report text: header (app, device, BOOX, canvas, settings) + all buffered entries. */
    fun buildReport(settings: AppSettings, nowMillis: Long = System.currentTimeMillis()): String = buildString {
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMillis))
        append("=== 小诺画板 XiaonuoBoard diagnostic log ===\n")
        append("exported: ").append(wall).append(" | monotonicNow=").append(DiagLog.nowMs()).append("ms\n")
        append("app: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") ")
            .append(BuildConfig.BUILD_TYPE).append('\n')
        append("device: manufacturer=").append(Build.MANUFACTURER).append(" brand=").append(Build.BRAND)
            .append(" model=").append(Build.MODEL).append(" device=").append(Build.DEVICE)
            .append(" board=").append(Build.BOARD).append(" hardware=").append(Build.HARDWARE).append('\n')
        append("android: sdk=").append(Build.VERSION.SDK_INT).append(" release=").append(Build.VERSION.RELEASE)
            .append(" abis=").append(Build.SUPPORTED_ABIS.orEmpty().joinToString(",")).append('\n')
        append("boox: ").append(OnyxEinkBridge.diagSummary()).append('\n')
        append("settings: einkMode=").append(settings.einkMode.name)
            .append(" booxSdkMode=").append(settings.booxSdkMode.name)
            .append(" stylusOnly=").append(settings.stylusOnly)
            .append(" stayInsideLines=").append(settings.stayInsideLines).append(" ghostUnderlay=").append(settings.ghostUnderlay)
            .append('\n')
        val state = DiagLog.stateSnapshot()
        if (state.isNotEmpty()) {
            append("state:\n")
            state.forEach { (k, v) -> append("  ").append(k).append(": ").append(v).append('\n') }
        }
        append("log: entries=").append(DiagLog.count).append(" capacity=").append(DiagLog.capacity)
            .append(" dropped=").append(DiagLog.dropped).append(" (columns: monotonicMs +deltaMs TAG message)\n")
        append("==========================================\n")
        append(DiagLog.formatEntries())
    }

    /** Saves the report to `Download/XiaonuoBoard/` (or the app's external Downloads dir on API < 29). */
    fun saveToDownloads(context: Context, settings: AppSettings): Result {
        val now = System.currentTimeMillis()
        val name = fileName(now)
        val bytes = buildReport(settings, now).toByteArray(Charsets.UTF_8)
        val entries = DiagLog.count
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("无法写入下载目录")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Result("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name", entries)
        }
        // API 26–28: app-specific external storage needs no permission (removed with the app).
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        val dir = File(base, FOLDER).apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return Result(file.absolutePath, entries)
    }

    /** Writes the report into the cache and returns a chooser for `ACTION_SEND` (text/plain). */
    fun shareIntent(context: Context, settings: AppSettings): Pair<Intent, Result> {
        val now = System.currentTimeMillis()
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        // Keep only the latest shared copy.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(now))
        file.writeText(buildReport(settings, now), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享诊断日志").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return chooser to Result(file.name, DiagLog.count)
    }
}
