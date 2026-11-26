package dev.xiaonuoboard.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.xiaonuoboard.app.BuildConfig
import dev.xiaonuoboard.app.data.SettingsStore
import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val checkedAtMs: Long) : UpdateUiState
    data class Available(
        val release: ReleaseJson,
        val asset: ReleaseAsset,
        val expectedSha256: String? = null,
    ) : UpdateUiState
    data class Downloading(
        val release: ReleaseJson,
        val asset: ReleaseAsset,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
    ) : UpdateUiState
    data class Verifying(val release: ReleaseJson) : UpdateUiState
    data class Ready(val release: ReleaseJson, val apkFile: File) : UpdateUiState
    data class Error(
        val code: UpdateErrorCode,
        val message: String,
        val releasePageUrl: String = ReleaseParser.DEFAULT_RELEASE_HTML_URL,
    ) : UpdateUiState
}

sealed interface InstallLaunchResult {
    data object Launched : InstallLaunchResult
    data class NeedsUnknownSourcesPermission(val settingsIntent: Intent) : InstallLaunchResult
    data class Failed(val message: String) : InstallLaunchResult
}

/** Verifies downloaded APK package name, versionCode, and signing certificate against the installed app. */
object ApkValidator {
    fun validate(context: Context, apkFile: File, strings: AppStrings = AppI18n.strings()) {
        val pm = context.packageManager
        val installed = getInstalledPackageInfo(pm, context.packageName)
            ?: throw UpdateException(UpdateErrorCode.VERIFY_FAILED, strings.updateApkReadInstalledFailed)
        val archive = getArchivePackageInfo(pm, apkFile.absolutePath)
            ?: throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, strings.updateApkParseFailed)

        if (archive.packageName != context.packageName) {
            throw UpdateException(UpdateErrorCode.VERIFY_FAILED, strings.updateApkPackageMismatch(archive.packageName))
        }
        val installedCode = versionCodeOf(installed)
        val archiveCode = versionCodeOf(archive)
        if (archiveCode < installedCode) {
            throw UpdateException(UpdateErrorCode.VERIFY_FAILED, strings.updateApkDowngrade(archiveCode, installedCode))
        }
        val installedDigests = certDigests(installed)
        val archiveDigests = certDigests(archive)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty() || installedDigests != archiveDigests) {
            throw UpdateException(UpdateErrorCode.VERIFY_FAILED, strings.updateApkSignatureMismatch)
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(pm: PackageManager, pkg: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(pm: PackageManager, path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }.toSet()
    }
}

class UpdateManager(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
        const val STALE_APK_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updater = AppUpdater()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var activeCancelToken: CancelToken? = null
    private var activeJob: Job? = null
    private var pendingInstallApk: File? = null
    private var startupCheckedThisProcess = false

    val updatesDir: File
        get() = File(appContext.cacheDir, "updates")

    fun checkOnStartupIfDue() {
        if (startupCheckedThisProcess) return
        startupCheckedThisProcess = true
        val now = System.currentTimeMillis()
        if (now - settingsStore.current.updateLastCheckMs < AUTO_CHECK_INTERVAL_MS) return
        if (!isNetworkConnected()) return
        check(force = false)
    }

    fun check(force: Boolean) {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        activeJob?.cancel()
        val token = CancelToken()
        activeCancelToken = token
        if (force) _state.value = UpdateUiState.Checking

        activeJob = scope.launch {
            withContext(Dispatchers.IO) { purgeStaleCachedApks() }
            try {
                val result = withContext(Dispatchers.IO) {
                    updater.checkLatestRelease(currentVersion = BuildConfig.VERSION_NAME, cancel = token)
                }
                val now = System.currentTimeMillis()
                settingsStore.update { it.copy(updateLastCheckMs = now) }

                if (result.updateAvailable) {
                    val asset = result.apk
                    if (asset == null) {
                        if (force) {
                            _state.value = UpdateUiState.Error(
                                code = UpdateErrorCode.NO_ASSET,
                                message = AppI18n.strings().updateFoundNoAsset(result.latest.tagName),
                                releasePageUrl = result.latest.htmlUrl ?: ReleaseParser.DEFAULT_RELEASE_HTML_URL,
                            )
                        }
                    } else {
                        val cached = cachedApkFileForTag(result.latest.tagName)
                        val expectedSha = result.expectedSha256
                        val cachedOk = withContext(Dispatchers.IO) {
                            cached.isFile &&
                                (expectedSha == null || runCatching {
                                    AppUpdater.sha256HexOfFile(cached).equals(expectedSha, ignoreCase = true)
                                }.getOrDefault(false)) &&
                                runCatching { ApkValidator.validate(appContext, cached) }.isSuccess
                        }
                        _state.value = if (cachedOk) {
                            UpdateUiState.Ready(result.latest, cached)
                        } else {
                            UpdateUiState.Available(result.latest, asset, expectedSha)
                        }
                    }
                } else if (force) {
                    _state.value = UpdateUiState.UpToDate(now)
                }
            } catch (e: UpdateException) {
                if (e.code == UpdateErrorCode.CANCELLED) {
                    _state.value = UpdateUiState.Idle
                } else if (force || _state.value !is UpdateUiState.Idle) {
                    _state.value = UpdateUiState.Error(e.code, friendlyMessage(e))
                }
            } catch (e: Exception) {
                if (force) {
                    _state.value = UpdateUiState.Error(UpdateErrorCode.NETWORK, e.message ?: AppI18n.strings().updateCheckFailed)
                }
            }
        }
    }

    fun download(onReadyToInstall: ((File) -> Unit)? = null) {
        val current = _state.value as? UpdateUiState.Available ?: return
        activeJob?.cancel()
        val token = CancelToken()
        activeCancelToken = token
        val release = current.release
        val asset = current.asset
        val dest = cachedApkFileForTag(release.tagName)

        activeJob = scope.launch {
            val startedMs = System.currentTimeMillis()
            _state.value = UpdateUiState.Downloading(release, asset, 0L, asset.size, 0L)
            try {
                val verifiedFile = withContext(Dispatchers.IO) {
                    updater.downloadApk(
                        url = asset.url,
                        dest = dest,
                        expectedSize = asset.size,
                        cancel = token,
                        expectedSha256 = current.expectedSha256,
                        validator = { tmpFile ->
                            scope.launch { _state.value = UpdateUiState.Verifying(release) }
                            ApkValidator.validate(appContext, tmpFile)
                        },
                        onProgress = { done, total ->
                            val elapsedSec = ((System.currentTimeMillis() - startedMs) / 1000.0).coerceAtLeast(0.1)
                            _state.value = UpdateUiState.Downloading(release, asset, done, total, (done / elapsedSec).toLong())
                        },
                    )
                }
                _state.value = UpdateUiState.Ready(release, verifiedFile)
                onReadyToInstall?.invoke(verifiedFile)
            } catch (e: UpdateException) {
                dest.delete()
                if (e.code == UpdateErrorCode.CANCELLED) {
                    _state.value = UpdateUiState.Available(release, asset, current.expectedSha256)
                } else {
                    _state.value = UpdateUiState.Error(
                        code = e.code,
                        message = friendlyMessage(e),
                        releasePageUrl = release.htmlUrl ?: ReleaseParser.DEFAULT_RELEASE_HTML_URL,
                    )
                }
            } catch (e: Exception) {
                dest.delete()
                _state.value = UpdateUiState.Error(
                    code = UpdateErrorCode.NETWORK,
                    message = e.message ?: AppI18n.strings().updateDownloadFailed,
                    releasePageUrl = release.htmlUrl ?: ReleaseParser.DEFAULT_RELEASE_HTML_URL,
                )
            }
        }
    }

    fun cancel() {
        activeCancelToken?.cancel()
    }

    fun launchInstaller(context: Context): InstallLaunchResult {
        val s = AppI18n.strings()
        val ready = _state.value as? UpdateUiState.Ready
            ?: return InstallLaunchResult.Failed(s.updateNoVerifiedApk)
        if (!ready.apkFile.isFile) {
            pendingInstallApk = null
            _state.value = UpdateUiState.Idle
            return InstallLaunchResult.Failed(s.updateCachedApkCleared)
        }
        return if (canRequestPackageInstalls()) {
            pendingInstallApk = null
            startInstallerActivity(context, ready.apkFile)
        } else {
            pendingInstallApk = ready.apkFile
            InstallLaunchResult.NeedsUnknownSourcesPermission(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Auto-launches system installer when returning from "Install unknown apps" settings. */
    fun onHostResume(context: Context): InstallLaunchResult? {
        val pending = pendingInstallApk ?: return null
        if (!canRequestPackageInstalls()) return null
        pendingInstallApk = null
        return startInstallerActivity(context, pending)
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    private fun startInstallerActivity(context: Context, apkFile: File): InstallLaunchResult {
        val s = AppI18n.strings()
        if (!apkFile.isFile) {
            _state.value = UpdateUiState.Idle
            return InstallLaunchResult.Failed(s.updateApkMissing)
        }
        return try {
            val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.Launched
        } catch (e: Exception) {
            InstallLaunchResult.Failed(e.message ?: s.updateLaunchInstallerFailed)
        }
    }

    private fun cachedApkFileForTag(tagName: String): File {
        val clean = tagName.trim().removePrefix("v").replace(Regex("[^0-9A-Za-z._-]"), "_")
        updatesDir.mkdirs()
        return File(updatesDir, "xiaonuo-board-$clean.apk")
    }

    private fun purgeStaleCachedApks() {
        val dir = updatesDir
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        val baseInstalled = SemanticVersion.stripDebugSuffix(BuildConfig.VERSION_NAME)
        dir.listFiles()?.forEach { f ->
            val tooOld = now - f.lastModified() > STALE_APK_MAX_AGE_MS
            val isTmp = f.name.endsWith(".tmp")
            val ver = f.name.removePrefix("xiaonuo-board-").removeSuffix(".apk")
            val notNewer = f.name.endsWith(".apk") && !SemanticVersion.isNewerVersion(baseInstalled, ver)
            if (isTmp || tooOld || notNewer) runCatching { f.delete() }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun friendlyMessage(e: UpdateException, s: AppStrings = AppI18n.strings()): String = when (e.code) {
        UpdateErrorCode.RATE_LIMITED -> s.updateErrRateLimited
        UpdateErrorCode.VERIFY_FAILED -> e.message ?: s.updateErrVerifyFailed
        UpdateErrorCode.INSECURE_URL -> s.updateErrInsecureUrl
        UpdateErrorCode.TIMEOUT -> s.updateErrTimeout
        UpdateErrorCode.NOT_FOUND -> s.updateErrNotFound
        UpdateErrorCode.NO_ASSET -> s.updateErrNoAsset
        UpdateErrorCode.CORRUPT_DOWNLOAD -> s.updateErrCorruptDownload
        UpdateErrorCode.BAD_JSON -> s.updateErrBadJson
        UpdateErrorCode.CANCELLED -> s.updateErrCancelled
        UpdateErrorCode.NETWORK -> e.message ?: s.updateErrNetwork
    }
}
