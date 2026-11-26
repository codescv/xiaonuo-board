package dev.xiaonuoboard.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import dev.xiaonuoboard.app.ai.PromptBuilder
import dev.xiaonuoboard.app.eink.BooxSdkMode
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.engine.BrushSize

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { EnStrings }

/**
 * Complete single-language UI string table for XiaonuoBoard (`zh`, `en`, `ja`).
 * Every field is non-nullable so the compiler guarantees 100% key parity across all locales.
 */
interface AppStrings {

    val language: ResolvedLanguage

    // App & Common
    val appName: String
    val back: String
    val backArrow: String
    val close: String
    val cancel: String
    val ok: String
    val delete: String
    val save: String
    val useLabel: String
    val show: String
    val hide: String

    // Home screen
    val homeSubtitle: String
    val homeAiWorkshop: String
    val homeUpdateBadge: String
    val pagerPrev: String
    val pagerNext: String
    val pagerInfoTemplate: String // %1$d current, %2$d pages, %3$d total
    val badgeColored: String
    val badgeCompleted: String
    val difficultyEasy: String
    val difficultyStandard: String
    val difficultyAi: String
    val difficultyImported: String
    val homeNewAiTitle: String
    val homeNewAiSubtitle: String
    val homeNoWorks: String
    val tabTitles: Map<String, String>

    // Parent Gate Dialog
    val parentGateTitle: String
    val parentGatePromptTemplate: String // %s = "a × b = ?"
    val parentGateWrong: String
    val parentGateLongPress: String

    // Canvas screen
    val canvasLoadError: String
    val canvasLoading: String
    val canvasCelebration: String
    val canvasEyedropperBlackLine: String
    val canvasEyedropperPickedTemplate: String // %s = hex
    val canvasNothingToUndo: String
    val canvasExportingPng: String
    val canvasExportSuccessTemplate: String // %s = location
    val exportFailedTemplate: String // %s = error
    val canvasConfirmReset: String
    val canvasRefExpandedTitle: String
    val canvasStylusOnlyOn: String
    val canvasStylusOnlyOff: String
    val brushThin: String
    val brushMedium: String
    val brushThick: String

    // Gallery screen
    val galleryTitleTemplate: String // %d = count
    val galleryDatePattern: String
    val galleryInProgressTemplate: String // %d = percent
    val galleryStatusExported: String
    val galleryStatusAutosaved: String
    val galleryContinue: String
    val galleryFreshCopy: String
    val galleryExportPng: String
    val galleryExporting: String
    val galleryMissingFile: String
    val galleryExportedTemplate: String // %s = location
    val galleryConfirmDelete: String

    // AI Workshop screen
    val aiTabCombo: String
    val aiTabFree: String
    val aiTabPhoto: String
    val aiPhotoReadError: String
    val aiNeedApiKey: String
    val aiTitleAlphabetTemplate: String // %s = Aa
    val aiTitleComboTemplate: String // %1$s = character, %2$s = scene
    val aiTitleFreeTemplate: String // %s = snippet
    val aiTitlePhoto: String
    val aiNeedFreeText: String
    val aiNeedPhoto: String
    val aiPreparing: String
    val aiQcPassedPrefix: String
    val aiQcWarningPrefix: String
    val aiGenerateFailedTemplate: String // %s = error
    val aiSavedToLibrary: String
    val aiSaveFailedTemplate: String // %s = error
    val aiHeaderTitle: String
    val aiModelLabelTemplate: String // %s = model
    val aiComboCharacterScene: String
    val aiComboAlphabet: String
    val aiStepCharacter: String
    val aiStepScene: String
    val aiPickLetterPromptTemplate: String // %s = localized object word
    val aiFreePlaceholder: String
    val aiPickPhoto: String
    val aiChangePhoto: String
    val aiPhotoHint: String
    val aiStepDifficulty: String
    val aiGenerating: String
    val aiGenerateButton: String
    val aiPreviewLineArt: String
    val aiPreviewRef: String
    val aiSaving: String
    val aiSaveAndColor: String
    val aiSaveOnly: String
    val aiNoRef: String
    val promptDifficultyEasy: String
    val promptDifficultyStandard: String
    val promptCharacters: Map<String, String>
    val promptScenes: Map<String, String>
    val alphabetObjects: List<String>

    // AI Pipeline & Gemini Client
    val aiStep1GenerateLine: String
    val aiStep2Sanitize: String
    val aiStep3Colorize: String
    val aiDecodeLineError: String
    val aiLineQcNoteTemplate: String // %d = regions
    val aiRefFailedSuffixTemplate: String // %s = error
    val aiImportNoteTemplate: String // %d = regions
    val geminiDefaultSuffix: String
    val geminiNoImageReturnedTemplate: String // %s = text
    val geminiMissingApiKeySettings: String
    val geminiMissingApiKeyShort: String
    val geminiNetworkFailedTemplate: String // %s = error
    val geminiParseFailed: String
    val geminiSafetyBlockedTemplate: String // %s = reason
    val geminiMissingCandidates: String
    val geminiCorruptImage: String
    val geminiEmptyImage: String
    val geminiConnectSuccessTemplate: String // %s = model name
    val geminiInvalidBaseUrlTemplate: String // %s = url
    val geminiInvalidApiKeyChars: String
    val geminiHttp400: String
    val geminiHttp401_403: String
    val geminiHttp404: String
    val geminiHttp429: String
    val geminiHttp5xx: String
    val geminiHttpOther: String

    // Settings screen
    val settingsTitle: String
    val settingsVersionEditionTemplate: String // %s = version
    val settingsLanguageTitle: String
    val settingsLanguageDesc: String
    val languageSystem: String
    val settingsGeminiTitle: String
    val settingsApiKeyLabel: String
    val settingsBaseUrlLabel: String
    val settingsModelLabel: String
    val settingsCustomModelPlaceholder: String
    val settingsSaved: String
    val settingsTestConnectivity: String
    val settingsTesting: String
    val settingsBooxCardTitle: String
    val settingsOnyxDeviceTemplate: String // %s = device detail
    val settingsBooxSdkModeTitle: String
    val settingsBooxSdkOff: String
    val settingsBooxSdkEpdOnly: String
    val settingsBooxSdkFull: String
    val settingsBooxSdkDesc: String
    val settingsRefreshModeTitle: String
    val settingsRefreshRegalRec: String
    val settingsSoundTitle: String
    val settingsSoundOff: String
    val settingsSoundOn: String
    val settingsPalmRejectionTitle: String
    val settingsInputBoth: String
    val settingsInputStylusOnly: String
    val settingsImportTitle: String
    val settingsImportDesc: String
    val settingsImportButton: String
    val settingsImportBusy: String
    val settingsImportDecodeError: String
    val settingsImportTitleTemplate: String // %s = id suffix
    val settingsImportFailedTemplate: String // %s = error
    val settingsDiagTitle: String
    val settingsDiagDescTemplate: String // %1$d capacity, %2$d count
    val settingsDiagExport: String
    val settingsDiagExporting: String
    val settingsDiagExportOkTemplate: String // %1$d entries, %2$s location
    val settingsDiagExportFailedTemplate: String // %s = error
    val settingsDiagShare: String
    val settingsDiagShareOkTemplate: String // %1$d entries, %2$s location
    val settingsDiagShareLaunchFailedTemplate: String // %s = error
    val settingsDiagShareFailedTemplate: String // %s = error
    val diagCreateFileFailed: String
    val diagWriteFailed: String
    val diagShareChooserTitle: String
    val galleryCreateFileFailed: String
    val galleryWriteFailed: String

    // BOOX / E-Ink Bridge & Raw Drawing
    val einkModeNormal: String
    val einkModeRegal: String
    val einkModeFast: String
    val einkModeFull: String
    val booxSdkModeOffShort: String
    val booxSdkModeEpdOnlyShort: String
    val booxSdkModeFullShort: String
    val booxStatusNotBooxTemplate: String // %1$s manufacturer, %2$s model
    val booxStatusSdkDisabledTemplate: String // %s model
    val booxStatusSdkFailedTemplate: String // %s reason
    val booxStatusUninitialized: String
    val booxStatusEpdTouchHelperTemplate: String // %1$s model, %2$s sdk versions
    val booxStatusEpdOnlyReason: String
    val booxStatusNoStylusReason: String
    val booxStatusEpdTemplate: String // %1$s model, %2$s why
    val booxTouchHelperMissedTemplate: String // %d max misses

    // OTA Update Card & UpdateManager / ApkValidator
    val otaTitle: String
    val otaCurrentVersionTemplate: String // %s version
    val otaIdleDesc: String
    val otaCheckButton: String
    val otaChecking: String
    val otaUpToDateTemplate: String // %s version
    val otaRecheckButton: String
    val otaAvailableTemplate: String // %1$s tag, %2$s size suffix
    val otaDownloadInstallButton: String
    val otaReleasePageButton: String
    val otaDownloadingPctTemplate: String // %1$s tag, %2$d pct, %3$s dlMb, %4$s totMb, %5$s spd
    val otaDownloadingNoPctTemplate: String // %1$s tag, %2$s dlMb, %3$s totMb, %4$s spd
    val otaCancelDownloadButton: String
    val otaVerifying: String
    val otaReadyTemplate: String // %s tag
    val otaInstallNowTemplate: String // %s tag
    val otaRetryButton: String
    val otaInstallerLaunched: String
    val otaUnknownSourcesHint: String
    val updateApkReadInstalledFailed: String
    val updateApkParseFailed: String
    val updateApkPackageMismatchTemplate: String // %s package
    val updateApkDowngradeTemplate: String // %1$d archive, %2$d installed
    val updateApkSignatureMismatch: String
    val updateFoundNoAssetTemplate: String // %s tag
    val updateCheckFailed: String
    val updateDownloadFailed: String
    val updateNoVerifiedApk: String
    val updateCachedApkCleared: String
    val updateApkMissing: String
    val updateLaunchInstallerFailed: String
    val updateErrRateLimited: String
    val updateErrVerifyFailed: String
    val updateErrInsecureUrl: String
    val updateErrTimeout: String
    val updateErrNotFound: String
    val updateErrNoAsset: String
    val updateErrCorruptDownload: String
    val updateErrBadJson: String
    val updateErrCancelled: String
    val updateErrNetwork: String
    val updateErrCannotReachGithub: String

    // Palette crayon names
    val crayons: Map<String, String>

    // Content descriptions (KidIcons & CanvasScreen)
    val cdBack: String
    val cdCrayon: String
    val cdPaintBucket: String
    val cdEraser: String
    val cdClipLocked: String
    val cdClipUnlocked: String
    val cdUndo: String
    val cdClearGhosting: String
    val cdSaveExport: String
    val cdResetCanvas: String
    val cdStylusOnly: String
    val cdFingerAndStylus: String
    val cdGhostUnderlay: String
    val cdExpandRef: String
    val cdCollapseRef: String
    val cdCollapseMoreColors: String
    val cdExpandMoreColors: String
    val cdShowRef: String
    val cdRefImage: String
    val cdAiMagic: String
    val cdParentLock: String
    val cdParentSettings: String
    val cdNewVersion: String
    val cdSnowflake: String
    val cdDog: String
    val cdPig: String
    val cdBunFace: String
    val cdLetters: String
    val cdMyWorks: String
    val cdStar: String
    val cdPrevPage: String
    val cdNextPage: String
    val cdNewColoringPage: String
    val cdBackspace: String

    fun tabTitle(id: String): String = tabTitles[id] ?: id

    fun difficultyLabel(d: String): String = when (d) {
        "easy" -> difficultyEasy
        "standard" -> difficultyStandard
        "ai" -> difficultyAi
        "imported" -> difficultyImported
        else -> d
    }

    fun brushSizeLabel(size: BrushSize): String = when (size) {
        BrushSize.THIN -> brushThin
        BrushSize.MEDIUM -> brushMedium
        BrushSize.THICK -> brushThick
    }

    fun einkModeLabel(mode: EinkMode): String = when (mode) {
        EinkMode.NORMAL -> einkModeNormal
        EinkMode.REGAL -> einkModeRegal
        EinkMode.FAST -> einkModeFast
        EinkMode.FULL -> einkModeFull
    }

    fun booxSdkModeLabel(mode: BooxSdkMode): String = when (mode) {
        BooxSdkMode.OFF -> booxSdkModeOffShort
        BooxSdkMode.EPD_ONLY -> booxSdkModeEpdOnlyShort
        BooxSdkMode.FULL -> booxSdkModeFullShort
    }

    fun promptDifficultyLabel(d: PromptBuilder.Difficulty): String = when (d) {
        PromptBuilder.Difficulty.EASY -> promptDifficultyEasy
        PromptBuilder.Difficulty.STANDARD -> promptDifficultyStandard
    }

    fun promptCharacterLabel(id: String): String = promptCharacters[id] ?: id
    fun promptSceneLabel(id: String): String = promptScenes[id] ?: id
    fun alphabetObject(index: Int): String = alphabetObjects.getOrElse(index) { "" }
    fun crayonName(id: String): String = crayons[id] ?: id

    fun pagerInfo(current: Int, pageCount: Int, total: Int): String =
        String.format(language.toLocale(), pagerInfoTemplate, current, pageCount, total)

    fun parentGatePrompt(question: String): String = String.format(language.toLocale(), parentGatePromptTemplate, question)
    fun canvasEyedropperPicked(hex: String): String = String.format(language.toLocale(), canvasEyedropperPickedTemplate, hex)
    fun canvasExportSuccess(where: String): String = String.format(language.toLocale(), canvasExportSuccessTemplate, where)
    fun exportFailed(msg: String?): String = String.format(language.toLocale(), exportFailedTemplate, msg.orEmpty())
    fun galleryTitle(count: Int): String = String.format(language.toLocale(), galleryTitleTemplate, count)
    fun galleryInProgress(pct: Int): String = String.format(language.toLocale(), galleryInProgressTemplate, pct)
    fun galleryExported(where: String): String = String.format(language.toLocale(), galleryExportedTemplate, where)
    fun aiTitleAlphabet(letter: Char): String = String.format(language.toLocale(), aiTitleAlphabetTemplate, "$letter${letter.lowercaseChar()}")
    fun aiTitleCombo(character: String, scene: String): String = String.format(language.toLocale(), aiTitleComboTemplate, character, scene)
    fun aiTitleFree(snippet: String): String = String.format(language.toLocale(), aiTitleFreeTemplate, snippet)
    fun aiGenerateFailed(msg: String?): String = String.format(language.toLocale(), aiGenerateFailedTemplate, msg.orEmpty())
    fun aiSaveFailed(msg: String?): String = String.format(language.toLocale(), aiSaveFailedTemplate, msg.orEmpty())
    fun aiModelLabel(model: String): String = String.format(language.toLocale(), aiModelLabelTemplate, model)
    fun aiPickLetterPrompt(obj: String): String = String.format(language.toLocale(), aiPickLetterPromptTemplate, obj)
    fun aiLineQcNote(regions: Int): String = String.format(language.toLocale(), aiLineQcNoteTemplate, regions)
    fun aiRefFailedSuffix(msg: String?): String = String.format(language.toLocale(), aiRefFailedSuffixTemplate, msg.orEmpty())
    fun aiImportNote(regions: Int): String = String.format(language.toLocale(), aiImportNoteTemplate, regions)
    fun geminiNoImageReturned(text: String): String = String.format(language.toLocale(), geminiNoImageReturnedTemplate, text)
    fun geminiNetworkFailed(msg: String?): String = String.format(language.toLocale(), geminiNetworkFailedTemplate, msg.orEmpty())
    fun geminiSafetyBlocked(reason: String): String = String.format(language.toLocale(), geminiSafetyBlockedTemplate, reason)
    fun geminiConnectSuccess(name: String): String = String.format(language.toLocale(), geminiConnectSuccessTemplate, name)
    fun geminiInvalidBaseUrl(url: String): String = String.format(language.toLocale(), geminiInvalidBaseUrlTemplate, url)
    fun settingsVersionEdition(version: String): String = String.format(language.toLocale(), settingsVersionEditionTemplate, version)
    fun settingsOnyxDevice(detail: String): String = String.format(language.toLocale(), settingsOnyxDeviceTemplate, detail)
    fun settingsImportTitle(suffix: String): String = String.format(language.toLocale(), settingsImportTitleTemplate, suffix)
    fun settingsImportFailed(msg: String?): String = String.format(language.toLocale(), settingsImportFailedTemplate, msg.orEmpty())
    fun settingsDiagDesc(capacity: Int, count: Int): String = String.format(language.toLocale(), settingsDiagDescTemplate, capacity, count)
    fun settingsDiagExportOk(entries: Int, location: String): String = String.format(language.toLocale(), settingsDiagExportOkTemplate, entries, location)
    fun settingsDiagExportFailed(msg: String?): String = String.format(language.toLocale(), settingsDiagExportFailedTemplate, msg.orEmpty())
    fun settingsDiagShareOk(entries: Int, location: String): String = String.format(language.toLocale(), settingsDiagShareOkTemplate, entries, location)
    fun settingsDiagShareLaunchFailed(msg: String?): String = String.format(language.toLocale(), settingsDiagShareLaunchFailedTemplate, msg.orEmpty())
    fun settingsDiagShareFailed(msg: String?): String = String.format(language.toLocale(), settingsDiagShareFailedTemplate, msg.orEmpty())
    fun booxStatusNotBoox(manufacturer: String, model: String): String =
        String.format(language.toLocale(), booxStatusNotBooxTemplate, manufacturer, model)
    fun booxStatusSdkDisabled(model: String): String = String.format(language.toLocale(), booxStatusSdkDisabledTemplate, model)
    fun booxStatusSdkFailed(reason: String): String = String.format(language.toLocale(), booxStatusSdkFailedTemplate, reason)
    fun booxStatusEpdTouchHelper(model: String, sdkVersions: String): String =
        String.format(language.toLocale(), booxStatusEpdTouchHelperTemplate, model, sdkVersions)
    fun booxStatusEpd(model: String, why: String): String = String.format(language.toLocale(), booxStatusEpdTemplate, model, why)
    fun booxTouchHelperMissedStrokes(maxMisses: Int): String = String.format(language.toLocale(), booxTouchHelperMissedTemplate, maxMisses)
    fun otaCurrentVersion(version: String): String = String.format(language.toLocale(), otaCurrentVersionTemplate, version)
    fun otaUpToDate(version: String): String = String.format(language.toLocale(), otaUpToDateTemplate, version)
    fun otaAvailable(tag: String, sizeSuffix: String): String = String.format(language.toLocale(), otaAvailableTemplate, tag, sizeSuffix)
    fun otaDownloadingPct(tag: String, pct: Int, dlMb: String, totMb: String, spd: String): String =
        String.format(language.toLocale(), otaDownloadingPctTemplate, tag, pct, dlMb, totMb, spd)
    fun otaDownloadingNoPct(tag: String, dlMb: String, totMb: String, spd: String): String =
        String.format(language.toLocale(), otaDownloadingNoPctTemplate, tag, dlMb, totMb, spd)
    fun otaReady(tag: String): String = String.format(language.toLocale(), otaReadyTemplate, tag)
    fun otaInstallNow(tag: String): String = String.format(language.toLocale(), otaInstallNowTemplate, tag)
    fun updateApkPackageMismatch(pkg: String?): String = String.format(language.toLocale(), updateApkPackageMismatchTemplate, pkg.orEmpty())
    fun updateApkDowngrade(archiveCode: Long, installedCode: Long): String =
        String.format(language.toLocale(), updateApkDowngradeTemplate, archiveCode, installedCode)
    fun updateFoundNoAsset(tag: String): String = String.format(language.toLocale(), updateFoundNoAssetTemplate, tag)

    /** Flattens every string entry (properties, maps, lists) into a keyed map for parity & leak tests. */
    fun allEntries(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val getters = AppStrings::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .sortedBy { it.name }
        for (m in getters) {
            val propName = m.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            when (val v = m.invoke(this)) {
                is String -> out[propName] = v
                is Map<*, *> -> v.forEach { (k, mv) -> if (mv is String) out["$propName.$k"] = mv }
                is List<*> -> v.forEachIndexed { i, lv -> if (lv is String) out["$propName[$i]"] = lv }
            }
        }
        return out
    }

    companion object {
        fun forLanguage(lang: ResolvedLanguage): AppStrings = when (lang) {
            ResolvedLanguage.ZH -> ZhStrings
            ResolvedLanguage.EN -> EnStrings
            ResolvedLanguage.JA -> JaStrings
        }

        val ALL: List<AppStrings> = listOf(ZhStrings, EnStrings, JaStrings)
    }
}

object ZhStrings : AppStrings {
override val language = ResolvedLanguage.ZH

    override val appName = "小诺画板"
    override val back = "返回"
    override val backArrow = "← 返回"
    override val close = "关闭"
    override val cancel = "取消"
    override val ok = "确定"
    override val delete = "删除"
    override val save = "保存"
    override val useLabel = "使用"
    override val show = "显示"
    override val hide = "隐藏"

    override val homeSubtitle = "选一张喜欢的画开始涂色吧"
    override val homeAiWorkshop = "AI 画坊"
    override val homeUpdateBadge = "新版"
    override val pagerPrev = "上一页"
    override val pagerNext = "下一页"
    override val pagerInfoTemplate = "第 %1\$d / %2\$d 页 · 共 %3\$d 张"
    override val badgeColored = "已涂"
    override val badgeCompleted = "已完成"
    override val difficultyEasy = "幼儿简单"
    override val difficultyStandard = "进阶场景"
    override val difficultyAi = "AI 生成"
    override val difficultyImported = "本地导入"
    override val homeNewAiTitle = "生成新涂色页"
    override val homeNewAiSubtitle = "进入魔法画坊（家长锁）"
    override val homeNoWorks = "还没有作品，去选一张画开始涂色吧！"
    override val tabTitles = mapOf(
        "elsa" to "艾莎",
        "bluey" to "布鲁伊",
        "peppa" to "小猪佩奇",
        "anpanman" to "面包超人",
        "alphabet" to "字母",
        "ai" to "AI 魔法",
        "works" to "我的作品",
    )

    override val parentGateTitle = "家长验证"
    override val parentGatePromptTemplate = "请回答：%s"
    override val parentGateWrong = "答错啦，换一道题～"
    override val parentGateLongPress = "或 长按此处 2 秒"

    override val canvasLoadError = "这张画打不开了 😢"
    override val canvasLoading = "正在准备画纸…"
    override val canvasCelebration = "🎉 太棒啦！这幅画快涂完啦！"
    override val canvasEyedropperBlackLine = "那是黑线哦，点一下色块吸色吧"
    override val canvasEyedropperPickedTemplate = "已吸取参考色 %s"
    override val canvasNothingToUndo = "没有可以撤销的步骤啦"
    override val canvasExportingPng = "正在导出高清 PNG…"
    override val canvasExportSuccessTemplate = "✓ 已保存到相册 %s"
    override val exportFailedTemplate = "导出失败：%s"
    override val canvasConfirmReset = "确认清空?"
    override val canvasRefExpandedTitle = "参考图 · 点任意位置吸色"
    override val canvasStylusOnlyOn = "仅笔作画(防误触)：手指只能缩放和拖动"
    override val canvasStylusOnlyOff = "手笔皆可涂色"
    override val brushThin = "细"
    override val brushMedium = "中"
    override val brushThick = "粗"

    override val galleryTitleTemplate = "我的作品画廊（%d 幅）"
    override val galleryDatePattern = "MM-dd HH:mm"
    override val galleryInProgressTemplate = "涂色中 %d%%"
    override val galleryStatusExported = " · 已导出相册"
    override val galleryStatusAutosaved = " · 自动保存"
    override val galleryContinue = "继续涂"
    override val galleryFreshCopy = "新开重涂"
    override val galleryExportPng = "导出 PNG"
    override val galleryExporting = "导出中…"
    override val galleryMissingFile = "作品文件缺失"
    override val galleryExportedTemplate = "已导出：%s"
    override val galleryConfirmDelete = "确认删除?"

    override val aiTabCombo = "🧩 快捷组合"
    override val aiTabFree = "⌨️ 自由输入"
    override val aiTabPhoto = "📷 照片转线稿"
    override val aiPhotoReadError = "无法读取这张照片"
    override val aiNeedApiKey = "请先在 ⚙️ 家长设置 中填写 Gemini API Key"
    override val aiTitleAlphabetTemplate = "AI · 字母 %s"
    override val aiTitleComboTemplate = "AI · %1\$s%2\$s"
    override val aiTitleFreeTemplate = "AI · %s"
    override val aiTitlePhoto = "AI · 照片线稿"
    override val aiNeedFreeText = "请先输入想画的内容"
    override val aiNeedPhoto = "请先选择一张照片"
    override val aiPreparing = "准备中…"
    override val aiQcPassedPrefix = "✓ 已通过封闭连通域质检 · "
    override val aiQcWarningPrefix = "⚠️ 质检提示：区域数量偏离预期 · "
    override val aiGenerateFailedTemplate = "生成失败：%s"
    override val aiSavedToLibrary = "✓ 已保存到「AI 魔法」离线图库"
    override val aiSaveFailedTemplate = "保存失败：%s"
    override val aiHeaderTitle = "✨ AI 魔法画坊（家长模式）"
    override val aiModelLabelTemplate = "模型：%s"
    override val aiComboCharacterScene = "角色 × 场景"
    override val aiComboAlphabet = "🔤 字母 A–Z"
    override val aiStepCharacter = "1. 主角"
    override val aiStepScene = "2. 场景"
    override val aiPickLetterPromptTemplate = "选一个字母（含可爱主题物：%s）"
    override val aiFreePlaceholder = "例如：小兔子在月亮上荡秋千"
    override val aiPickPhoto = "📷 选择照片"
    override val aiChangePhoto = "📷 换一张"
    override val aiPhotoHint = "孩子 / 宠物 / 玩具照片都可以"
    override val aiStepDifficulty = "3. 线条难度："
    override val aiGenerating = "⏳ 生成中…"
    override val aiGenerateButton = "🪄 生成双图（线稿 + 参考图）"
    override val aiPreviewLineArt = "涂色前 · 1-bit 线稿"
    override val aiPreviewRef = "涂色后 · 参考彩图"
    override val aiSaving = "⏳ 保存中…"
    override val aiSaveAndColor = "✓ 保存并立即开始涂色"
    override val aiSaveOnly = "仅保存"
    override val aiNoRef = "（无参考图）"
    override val promptDifficultyEasy = "幼儿大色块"
    override val promptDifficultyStandard = "进阶细线"
    override val promptCharacters = mapOf(
        "winter_princess" to "冰雪公主",
        "puppy" to "小狗狗",
        "piglet" to "小猪猪",
        "bread_hero" to "面包小英雄",
        "dinosaur" to "小恐龙",
        "kitten" to "小猫咪",
    )
    override val promptScenes = mapOf(
        "snowman" to "雪地堆雪人",
        "sandcastle" to "海边建城堡",
        "birthday_cake" to "生日大蛋糕",
        "hot_air_balloon" to "彩虹热气球",
        "space_rocket" to "太空火箭",
        "garden_picnic" to "花园野餐",
    )
    override val alphabetObjects = listOf(
        "苹果", "小熊", "小猫", "小狗", "彩蛋", "小鱼", "葡萄", "房子", "冰淇淋", "水母", "风筝", "狮子",
        "月亮", "鸟窝", "章鱼", "企鹅", "皇冠", "彩虹", "太阳", "大树", "雨伞", "火山", "鲸鱼", "木琴",
        "悠悠球", "斑马",
    )

    override val aiStep1GenerateLine = "① 正在生成黑白封闭线稿…"
    override val aiStep2Sanitize = "② 本地二值化去噪 + 闭运算封口质检…"
    override val aiStep3Colorize = "③ 正在生成像素对齐的参考涂色图…"
    override val aiDecodeLineError = "无法解码模型返回的线稿图片"
    override val aiLineQcNoteTemplate = "线稿质检：%d 块可填色区域"
    override val aiRefFailedSuffixTemplate = "（参考图生成失败：%s）"
    override val aiImportNoteTemplate = "导入线稿：%d 块可填色区域"
    override val geminiDefaultSuffix = "（默认）"
    override val geminiNoImageReturnedTemplate = "模型未返回图片：%s"
    override val geminiMissingApiKeySettings = "请先在家长设置中填写 Gemini API Key"
    override val geminiMissingApiKeyShort = "请先填写 API Key"
    override val geminiNetworkFailedTemplate = "网络连接失败：%s"
    override val geminiParseFailed = "无法解析模型响应"
    override val geminiSafetyBlockedTemplate = "请求被安全策略拦截：%s"
    override val geminiMissingCandidates = "模型响应缺少 candidates"
    override val geminiCorruptImage = "模型返回的图片数据损坏"
    override val geminiEmptyImage = "模型返回的图片数据为空"
    override val geminiConnectSuccessTemplate = "连接成功 ✓ %s"
    override val geminiInvalidBaseUrlTemplate = "Base URL 格式错误（需以 https:// 开头）：%s"
    override val geminiInvalidApiKeyChars = "API Key 含有非法字符"
    override val geminiHttp400 = "请求参数错误"
    override val geminiHttp401_403 = "API Key 无效或无权限"
    override val geminiHttp404 = "模型不存在或 Base URL 错误"
    override val geminiHttp429 = "调用过于频繁或配额不足"
    override val geminiHttp5xx = "服务暂时不可用"
    override val geminiHttpOther = "请求失败"

    override val settingsTitle = "⚙️ 家长设置"
    override val settingsVersionEditionTemplate = "v%s · BOOX 优化版"
    override val settingsLanguageTitle = "界面语言"
    override val settingsLanguageDesc = "选择界面显示语言，切换后立即生效。"
    override val languageSystem = "跟随系统"
    override val settingsGeminiTitle = "Gemini AI 生图配置"
    override val settingsApiKeyLabel = "API Key（仅保存在本机）"
    override val settingsBaseUrlLabel = "自定义 Base URL 代理（选填）"
    override val settingsModelLabel = "模型"
    override val settingsCustomModelPlaceholder = "自定义模型名，如 gemini-xxx-image"
    override val settingsSaved = "✓ 已保存"
    override val settingsTestConnectivity = "测试连通性"
    override val settingsTesting = "测试中…"
    override val settingsBooxCardTitle = "BOOX 墨水屏、交互与诊断"
    override val settingsOnyxDeviceTemplate = "Onyx 设备：%s"
    override val settingsBooxSdkModeTitle = "BOOX SDK 模式"
    override val settingsBooxSdkOff = "关闭 SDK（标准）"
    override val settingsBooxSdkEpdOnly = "仅墨水屏快刷"
    override val settingsBooxSdkFull = "完整（直绘）"
    override val settingsBooxSdkDesc = "「关闭 SDK」与未集成 SDK 时完全一致；「仅墨水屏快刷」实时显示真实颜色并保留不涂出线；「完整（直绘）」启用硬件手写层。"
    override val settingsRefreshModeTitle = "默认刷新模式"
    override val settingsRefreshRegalRec = "REGAL 清晰（推荐）"
    override val settingsSoundTitle = "涂色音效与完成夸奖"
    override val settingsSoundOff = "静音（默认）"
    override val settingsSoundOn = "轻柔音效"
    override val settingsPalmRejectionTitle = "防误触"
    override val settingsInputBoth = "👆 手笔皆可"
    override val settingsInputStylusOnly = "✍️ 仅笔作画"
    override val settingsImportTitle = "本地导入"
    override val settingsImportDesc = "从相册 / 文件选择线稿图片，自动二值化、去噪并封口，加入「AI 魔法」图库。"
    override val settingsImportButton = "📂 从本地相册 / 文件导入"
    override val settingsImportBusy = "正在导入并转换为封闭线稿…"
    override val settingsImportDecodeError = "无法读取图片"
    override val settingsImportTitleTemplate = "导入 · %s"
    override val settingsImportFailedTemplate = "导入失败：%s"
    override val settingsDiagTitle = "诊断"
    override val settingsDiagDescTemplate = "记录笔迹延迟、刷新与 BOOX 手写层状态（最近 %1\$d 条，当前 %2\$d 条，不含画作内容）。遇到卡顿时先画几笔，再导出日志发给开发者。"
    override val settingsDiagExport = "导出诊断日志"
    override val settingsDiagExporting = "正在导出…"
    override val settingsDiagExportOkTemplate = "✓ 已保存 %1\$d 条日志到 %2\$s"
    override val settingsDiagExportFailedTemplate = "✗ 导出失败：%s"
    override val settingsDiagShare = "分享日志"
    override val settingsDiagShareOkTemplate = "已打开分享（%1\$d 条 · %2\$s）"
    override val settingsDiagShareLaunchFailedTemplate = "✗ 无法分享：%s"
    override val settingsDiagShareFailedTemplate = "✗ 分享失败：%s"
    override val diagCreateFileFailed = "无法创建下载文件"
    override val diagWriteFailed = "无法写入下载目录"
    override val diagShareChooserTitle = "分享诊断日志"
    override val galleryCreateFileFailed = "无法创建相册文件"
    override val galleryWriteFailed = "无法写入相册"

    override val einkModeNormal = "标准"
    override val einkModeRegal = "REGAL 清晰"
    override val einkModeFast = "快速"
    override val einkModeFull = "全刷"
    override val booxSdkModeOffShort = "关闭 SDK"
    override val booxSdkModeEpdOnlyShort = "仅快刷"
    override val booxSdkModeFullShort = "完整（直绘）"
    override val booxStatusNotBooxTemplate = "未检测到 BOOX 设备（%1\$s %2\$s）· 标准 Android 刷新模式"
    override val booxStatusSdkDisabledTemplate = "已检测到 BOOX %s · 已关闭 BOOX SDK（标准 Android 刷新模式）"
    override val booxStatusSdkFailedTemplate = "检测到 BOOX 设备，但 Onyx SDK 初始化失败（%s）· 已降级为标准刷新"
    override val booxStatusUninitialized = "未初始化"
    override val booxStatusEpdTouchHelperTemplate = "已检测到 BOOX %1\$s ✓ EPD 局部刷新 + TouchHelper 硬件直绘（%2\$s）"
    override val booxStatusEpdOnlyReason = "当前为「仅墨水屏快刷」模式"
    override val booxStatusNoStylusReason = "未检测到手写笔或非 arm64 设备"
    override val booxStatusEpdTemplate = "已检测到 BOOX %1\$s ✓ EPD 局部刷新 · 硬件直绘未启用（%2\$s）"
    override val booxTouchHelperMissedTemplate = "TouchHelper 连续 %d 笔未回传笔迹"

    override val otaTitle = "软件更新（GitHub OTA）"
    override val otaCurrentVersionTemplate = "当前 v%s"
    override val otaIdleDesc = "自动检测 GitHub Releases 新版本，支持 SHA-256 与签名校验后一键安装。"
    override val otaCheckButton = "🔄 检查更新"
    override val otaChecking = "正在连接 GitHub 检查新版本…"
    override val otaUpToDateTemplate = "✓ 已是最新版本（v%s）"
    override val otaRecheckButton = "🔄 重新检查"
    override val otaAvailableTemplate = "🎉 发现新版本：%1\$s%2\$s"
    override val otaDownloadInstallButton = "⬇️ 下载并安装"
    override val otaReleasePageButton = "🌐 发布页"
    override val otaDownloadingPctTemplate = "正在下载 %1\$s：%2\$d%%（%3\$s %4\$s%5\$s）"
    override val otaDownloadingNoPctTemplate = "正在下载 %1\$s：%2\$s %3\$s%4\$s"
    override val otaCancelDownloadButton = "取消下载"
    override val otaVerifying = "正在校验安装包完整性与签名…"
    override val otaReadyTemplate = "✓ %s 已下载并通过签名校验"
    override val otaInstallNowTemplate = "📦 立即安装 %s"
    override val otaRetryButton = "🔄 重试"
    override val otaInstallerLaunched = "已拉起系统安装器"
    override val otaUnknownSourcesHint = "请允许「安装未知应用」权限，返回后将自动安装"
    override val updateApkReadInstalledFailed = "无法读取已安装应用信息"
    override val updateApkParseFailed = "APK 解析失败，文件可能已损坏"
    override val updateApkPackageMismatchTemplate = "APK 包名不匹配 (%s)"
    override val updateApkDowngradeTemplate = "新包版本号 (%1\$d) 低于当前版本 (%2\$d)"
    override val updateApkSignatureMismatch = "APK 签名与当前安装版本不一致，已拦截安装"
    override val updateFoundNoAssetTemplate = "发现 %s，但暂未附带 APK 文件"
    override val updateCheckFailed = "检查更新失败"
    override val updateDownloadFailed = "下载失败"
    override val updateNoVerifiedApk = "还没有已校验的安装包"
    override val updateCachedApkCleared = "缓存的安装包已被清理，请重新检查更新"
    override val updateApkMissing = "安装包不存在，请重新下载"
    override val updateLaunchInstallerFailed = "无法拉起系统安装器"
    override val updateErrRateLimited = "GitHub 请求过于频繁，请稍后再试"
    override val updateErrVerifyFailed = "安装包签名或校验失败"
    override val updateErrInsecureUrl = "拦截了不安全的更新地址"
    override val updateErrTimeout = "连接 GitHub 超时，请检查网络"
    override val updateErrNotFound = "GitHub 暂未发布新版本"
    override val updateErrNoAsset = "新版本暂未附带 APK 文件"
    override val updateErrCorruptDownload = "安装包下载不完整或损坏"
    override val updateErrBadJson = "无法解析 GitHub 版本信息"
    override val updateErrCancelled = "已取消更新"
    override val updateErrNetwork = "无法连接 GitHub"
    override val updateErrCannotReachGithub = "无法连接 GitHub（请检查网络）"

    override val crayons = mapOf(
        "red" to "中国红",
        "orange" to "活力橙",
        "yellow" to "柠檬黄",
        "apple_green" to "苹果绿",
        "forest_green" to "森林绿",
        "sky_blue" to "天青蓝",
        "ice_blue" to "冰雪蓝",
        "royal_blue" to "宝蓝",
        "lavender" to "薰衣草紫",
        "magenta" to "玫红",
        "peppa_pink" to "佩奇粉",
        "peach" to "蜜桃肤",
        "caramel" to "焦糖棕",
        "warm_gray" to "暖灰",
        "black" to "纯黑",
        "white" to "橡皮白",
    )

    override val cdBack = "返回"
    override val cdCrayon = "蜡笔"
    override val cdPaintBucket = "油漆桶"
    override val cdEraser = "橡皮"
    override val cdClipLocked = "已锁定不出线"
    override val cdClipUnlocked = "自由涂鸦"
    override val cdUndo = "撤销"
    override val cdClearGhosting = "清残影"
    override val cdSaveExport = "保存导出"
    override val cdResetCanvas = "重置画布"
    override val cdStylusOnly = "仅笔作画"
    override val cdFingerAndStylus = "手笔皆可"
    override val cdGhostUnderlay = "参考底稿"
    override val cdExpandRef = "放大参考图"
    override val cdCollapseRef = "收起参考图"
    override val cdCollapseMoreColors = "收起更多颜色"
    override val cdExpandMoreColors = "展开更多颜色"
    override val cdShowRef = "显示参考图"
    override val cdRefImage = "参考涂色图"
    override val cdAiMagic = "AI 魔法"
    override val cdParentLock = "家长锁"
    override val cdParentSettings = "家长设置"
    override val cdNewVersion = "新版本"
    override val cdSnowflake = "雪花"
    override val cdDog = "小狗"
    override val cdPig = "小猪"
    override val cdBunFace = "面包脸"
    override val cdLetters = "字母"
    override val cdMyWorks = "我的作品"
    override val cdStar = "星星"
    override val cdPrevPage = "上一页"
    override val cdNextPage = "下一页"
    override val cdNewColoringPage = "生成新涂色页"
    override val cdBackspace = "删除"
}

object EnStrings : AppStrings {
override val language = ResolvedLanguage.EN

    override val appName = "XiaonuoBoard"
    override val back = "Back"
    override val backArrow = "← Back"
    override val close = "Close"
    override val cancel = "Cancel"
    override val ok = "OK"
    override val delete = "Delete"
    override val save = "Save"
    override val useLabel = "Use"
    override val show = "Show"
    override val hide = "Hide"

    override val homeSubtitle = "Pick a favorite picture to start coloring"
    override val homeAiWorkshop = "AI Studio"
    override val homeUpdateBadge = "Update"
    override val pagerPrev = "Prev"
    override val pagerNext = "Next"
    override val pagerInfoTemplate = "Page %1\$d / %2\$d · %3\$d total"
    override val badgeColored = "Colored"
    override val badgeCompleted = "Done"
    override val difficultyEasy = "Easy"
    override val difficultyStandard = "Standard"
    override val difficultyAi = "AI Generated"
    override val difficultyImported = "Imported"
    override val homeNewAiTitle = "New Coloring Page"
    override val homeNewAiSubtitle = "Open AI Studio (Parent Lock)"
    override val homeNoWorks = "No artworks yet. Pick a picture and start coloring!"
    override val tabTitles = mapOf(
        "elsa" to "Elsa",
        "bluey" to "Bluey",
        "peppa" to "Peppa Pig",
        "anpanman" to "Anpanman",
        "alphabet" to "ABC",
        "ai" to "AI Magic",
        "works" to "My Works",
    )

    override val parentGateTitle = "Parent Verification"
    override val parentGatePromptTemplate = "Answer: %s"
    override val parentGateWrong = "Wrong answer, try a new one"
    override val parentGateLongPress = "Or press & hold here for 2s"

    override val canvasLoadError = "Could not open this page 😢"
    override val canvasLoading = "Preparing paper…"
    override val canvasCelebration = "🎉 Awesome! Almost finished!"
    override val canvasEyedropperBlackLine = "That's a black outline — tap a colored area to pick a color"
    override val canvasEyedropperPickedTemplate = "Picked color %s"
    override val canvasNothingToUndo = "Nothing to undo"
    override val canvasExportingPng = "Exporting HD PNG…"
    override val canvasExportSuccessTemplate = "✓ Saved to Gallery %s"
    override val exportFailedTemplate = "Export failed: %s"
    override val canvasConfirmReset = "Clear all?"
    override val canvasRefExpandedTitle = "Reference · Tap anywhere to pick color"
    override val canvasStylusOnlyOn = "Stylus only (palm rejection): fingers zoom & pan only"
    override val canvasStylusOnlyOff = "Finger & stylus coloring enabled"
    override val brushThin = "Thin"
    override val brushMedium = "Medium"
    override val brushThick = "Thick"

    override val galleryTitleTemplate = "My Artwork Gallery (%d)"
    override val galleryDatePattern = "MMM d HH:mm"
    override val galleryInProgressTemplate = "Coloring %d%%"
    override val galleryStatusExported = " · Exported"
    override val galleryStatusAutosaved = " · Auto-saved"
    override val galleryContinue = "Continue"
    override val galleryFreshCopy = "Start Fresh"
    override val galleryExportPng = "Export PNG"
    override val galleryExporting = "Exporting…"
    override val galleryMissingFile = "Artwork file missing"
    override val galleryExportedTemplate = "Exported: %s"
    override val galleryConfirmDelete = "Confirm?"

    override val aiTabCombo = "🧩 Quick Combo"
    override val aiTabFree = "⌨️ Free Prompt"
    override val aiTabPhoto = "📷 Photo to Line"
    override val aiPhotoReadError = "Unable to read this photo"
    override val aiNeedApiKey = "Please set your Gemini API Key in ⚙️ Parent Settings first"
    override val aiTitleAlphabetTemplate = "AI · Letter %s"
    override val aiTitleComboTemplate = "AI · %1\$s · %2\$s"
    override val aiTitleFreeTemplate = "AI · %s"
    override val aiTitlePhoto = "AI · Photo Line Art"
    override val aiNeedFreeText = "Please enter what you want to draw"
    override val aiNeedPhoto = "Please choose a photo first"
    override val aiPreparing = "Preparing…"
    override val aiQcPassedPrefix = "✓ Closed-region QC passed · "
    override val aiQcWarningPrefix = "⚠️ QC warning: unexpected region count · "
    override val aiGenerateFailedTemplate = "Generation failed: %s"
    override val aiSavedToLibrary = "✓ Saved to offline AI Magic library"
    override val aiSaveFailedTemplate = "Save failed: %s"
    override val aiHeaderTitle = "✨ AI Magic Studio (Parent)"
    override val aiModelLabelTemplate = "Model: %s"
    override val aiComboCharacterScene = "Character × Scene"
    override val aiComboAlphabet = "🔤 Letters A–Z"
    override val aiStepCharacter = "1. Character"
    override val aiStepScene = "2. Scene"
    override val aiPickLetterPromptTemplate = "Pick a letter (with cute object: %s)"
    override val aiFreePlaceholder = "e.g., A little bunny swinging on the moon"
    override val aiPickPhoto = "📷 Pick Photo"
    override val aiChangePhoto = "📷 Change"
    override val aiPhotoHint = "Kids, pets, or toy photos work great"
    override val aiStepDifficulty = "3. Line Detail:"
    override val aiGenerating = "⏳ Generating…"
    override val aiGenerateButton = "🪄 Generate Pair (Line Art + Reference)"
    override val aiPreviewLineArt = "Before · 1-bit Line Art"
    override val aiPreviewRef = "After · Colored Reference"
    override val aiSaving = "⏳ Saving…"
    override val aiSaveAndColor = "✓ Save & Color Now"
    override val aiSaveOnly = "Save Only"
    override val aiNoRef = "(No reference)"
    override val promptDifficultyEasy = "Large Regions"
    override val promptDifficultyStandard = "Detailed Lines"
    override val promptCharacters = mapOf(
        "winter_princess" to "Winter Princess",
        "puppy" to "Happy Puppy",
        "piglet" to "Cute Piglet",
        "bread_hero" to "Bread Hero",
        "dinosaur" to "Baby Dino",
        "kitten" to "Little Kitten",
    )
    override val promptScenes = mapOf(
        "snowman" to "Building a Snowman",
        "sandcastle" to "Beach Sandcastle",
        "birthday_cake" to "Birthday Cake",
        "hot_air_balloon" to "Rainbow Balloon",
        "space_rocket" to "Space Rocket",
        "garden_picnic" to "Garden Picnic",
    )
    override val alphabetObjects = listOf(
        "Apple", "Bear", "Cat", "Dog", "Egg", "Fish", "Grapes", "House", "Ice cream", "Jellyfish", "Kite", "Lion",
        "Moon", "Nest", "Octopus", "Penguin", "Queen crown", "Rainbow", "Sun", "Tree", "Umbrella", "Volcano",
        "Whale", "Xylophone", "Yo-yo", "Zebra",
    )

    override val aiStep1GenerateLine = "① Generating closed black-and-white line art…"
    override val aiStep2Sanitize = "② Local binarization, despeckling & gap-closing QC…"
    override val aiStep3Colorize = "③ Generating pixel-aligned colored reference…"
    override val aiDecodeLineError = "Failed to decode line art image returned by model"
    override val aiLineQcNoteTemplate = "Line QC: %d fillable regions"
    override val aiRefFailedSuffixTemplate = " (reference failed: %s)"
    override val aiImportNoteTemplate = "Imported line art: %d fillable regions"
    override val geminiDefaultSuffix = " (Default)"
    override val geminiNoImageReturnedTemplate = "Model returned no image: %s"
    override val geminiMissingApiKeySettings = "Please enter your Gemini API Key in Parent Settings first"
    override val geminiMissingApiKeyShort = "Please enter an API Key first"
    override val geminiNetworkFailedTemplate = "Network error: %s"
    override val geminiParseFailed = "Failed to parse model response"
    override val geminiSafetyBlockedTemplate = "Request blocked by safety policy: %s"
    override val geminiMissingCandidates = "Model response missing candidates"
    override val geminiCorruptImage = "Corrupted image data returned by model"
    override val geminiEmptyImage = "Empty image data returned by model"
    override val geminiConnectSuccessTemplate = "Connected ✓ %s"
    override val geminiInvalidBaseUrlTemplate = "Invalid Base URL (must start with https://): %s"
    override val geminiInvalidApiKeyChars = "API Key contains invalid characters"
    override val geminiHttp400 = "Bad request"
    override val geminiHttp401_403 = "Invalid or unauthorized API Key"
    override val geminiHttp404 = "Model not found or invalid Base URL"
    override val geminiHttp429 = "Rate limited or quota exceeded"
    override val geminiHttp5xx = "Service temporarily unavailable"
    override val geminiHttpOther = "Request failed"

    override val settingsTitle = "⚙️ Parent Settings"
    override val settingsVersionEditionTemplate = "v%s · BOOX Edition"
    override val settingsLanguageTitle = "Language"
    override val settingsLanguageDesc = "Choose the app display language. Changes apply immediately."
    override val languageSystem = "System Default"
    override val settingsGeminiTitle = "Gemini AI Image Generation"
    override val settingsApiKeyLabel = "API Key (stored locally only)"
    override val settingsBaseUrlLabel = "Custom Base URL Proxy (optional)"
    override val settingsModelLabel = "Model"
    override val settingsCustomModelPlaceholder = "Custom model ID, e.g. gemini-xxx-image"
    override val settingsSaved = "✓ Saved"
    override val settingsTestConnectivity = "Test Connection"
    override val settingsTesting = "Testing…"
    override val settingsBooxCardTitle = "BOOX E-Ink, Input & Diagnostics"
    override val settingsOnyxDeviceTemplate = "Onyx device: %s"
    override val settingsBooxSdkModeTitle = "BOOX SDK Mode"
    override val settingsBooxSdkOff = "SDK Off (Standard)"
    override val settingsBooxSdkEpdOnly = "EPD Fast Refresh"
    override val settingsBooxSdkFull = "Full (Raw Pen)"
    override val settingsBooxSdkDesc = "\"SDK Off\" uses standard Android drawing; \"EPD Fast Refresh\" keeps real colors and stay-inside-lines clipping; \"Full (Raw Pen)\" enables the hardware pen overlay."
    override val settingsRefreshModeTitle = "Default Refresh Mode"
    override val settingsRefreshRegalRec = "REGAL Crisp (Rec.)"
    override val settingsSoundTitle = "Coloring & Celebration Sounds"
    override val settingsSoundOff = "Silent (Default)"
    override val settingsSoundOn = "Gentle Sounds"
    override val settingsPalmRejectionTitle = "Palm Rejection"
    override val settingsInputBoth = "👆 Finger & Stylus"
    override val settingsInputStylusOnly = "✍️ Stylus Only"
    override val settingsImportTitle = "Local Import"
    override val settingsImportDesc = "Pick a line-art image from gallery or files to binarize, clean, close gaps, and add to AI Magic."
    override val settingsImportButton = "📂 Import from Gallery / Files"
    override val settingsImportBusy = "Importing and converting to closed line art…"
    override val settingsImportDecodeError = "Unable to read image"
    override val settingsImportTitleTemplate = "Imported · %s"
    override val settingsImportFailedTemplate = "Import failed: %s"
    override val settingsDiagTitle = "Diagnostics"
    override val settingsDiagDescTemplate = "Records pen latency, refreshes, and BOOX pen layer state (last %1\$d entries, %2\$d buffered, no artwork content). Draw a few strokes when lag occurs, then export."
    override val settingsDiagExport = "Export Diag Log"
    override val settingsDiagExporting = "Exporting…"
    override val settingsDiagExportOkTemplate = "✓ Saved %1\$d entries to %2\$s"
    override val settingsDiagExportFailedTemplate = "✗ Export failed: %s"
    override val settingsDiagShare = "Share Log"
    override val settingsDiagShareOkTemplate = "Share opened (%1\$d entries · %2\$s)"
    override val settingsDiagShareLaunchFailedTemplate = "✗ Could not share: %s"
    override val settingsDiagShareFailedTemplate = "✗ Share failed: %s"
    override val diagCreateFileFailed = "Unable to create download file"
    override val diagWriteFailed = "Unable to write to Downloads folder"
    override val diagShareChooserTitle = "Share diagnostic log"
    override val galleryCreateFileFailed = "Unable to create gallery image file"
    override val galleryWriteFailed = "Unable to write to gallery"

    override val einkModeNormal = "Normal"
    override val einkModeRegal = "REGAL Crisp"
    override val einkModeFast = "Fast"
    override val einkModeFull = "Full"
    override val booxSdkModeOffShort = "SDK Off"
    override val booxSdkModeEpdOnlyShort = "EPD Only"
    override val booxSdkModeFullShort = "Full (Raw)"
    override val booxStatusNotBooxTemplate = "No BOOX device detected (%1\$s %2\$s) · Standard Android refresh"
    override val booxStatusSdkDisabledTemplate = "BOOX %s detected · BOOX SDK disabled (Standard Android refresh)"
    override val booxStatusSdkFailedTemplate = "BOOX device detected, but Onyx SDK init failed (%s) · Standard refresh fallback"
    override val booxStatusUninitialized = "not initialized"
    override val booxStatusEpdTouchHelperTemplate = "BOOX %1\$s detected ✓ EPD partial refresh + TouchHelper raw pen (%2\$s)"
    override val booxStatusEpdOnlyReason = "EPD Fast Refresh mode"
    override val booxStatusNoStylusReason = "no stylus or non-arm64 device"
    override val booxStatusEpdTemplate = "BOOX %1\$s detected ✓ EPD partial refresh · Raw pen off (%2\$s)"
    override val booxTouchHelperMissedTemplate = "TouchHelper missed %d consecutive pen strokes"

    override val otaTitle = "Software Update (GitHub OTA)"
    override val otaCurrentVersionTemplate = "Current v%s"
    override val otaIdleDesc = "Automatically checks GitHub Releases with SHA-256 and signature verification for one-tap updates."
    override val otaCheckButton = "🔄 Check for Updates"
    override val otaChecking = "Connecting to GitHub to check for updates…"
    override val otaUpToDateTemplate = "✓ Up to date (v%s)"
    override val otaRecheckButton = "🔄 Check Again"
    override val otaAvailableTemplate = "🎉 Update available: %1\$s%2\$s"
    override val otaDownloadInstallButton = "⬇️ Download & Install"
    override val otaReleasePageButton = "🌐 Release"
    override val otaDownloadingPctTemplate = "Downloading %1\$s: %2\$d%% (%3\$s %4\$s%5\$s)"
    override val otaDownloadingNoPctTemplate = "Downloading %1\$s: %2\$s %3\$s%4\$s"
    override val otaCancelDownloadButton = "Cancel Download"
    override val otaVerifying = "Verifying package integrity and signature…"
    override val otaReadyTemplate = "✓ %s downloaded and signature verified"
    override val otaInstallNowTemplate = "📦 Install %s Now"
    override val otaRetryButton = "🔄 Retry"
    override val otaInstallerLaunched = "System installer launched"
    override val otaUnknownSourcesHint = "Please allow \"Install unknown apps\" permission; install will resume on return"
    override val updateApkReadInstalledFailed = "Unable to read installed app info"
    override val updateApkParseFailed = "Failed to parse APK; file may be corrupted"
    override val updateApkPackageMismatchTemplate = "APK package name mismatch (%s)"
    override val updateApkDowngradeTemplate = "New versionCode (%1\$d) is lower than installed (%2\$d)"
    override val updateApkSignatureMismatch = "APK signature does not match installed app; installation blocked"
    override val updateFoundNoAssetTemplate = "Found %s, but no APK asset is attached yet"
    override val updateCheckFailed = "Update check failed"
    override val updateDownloadFailed = "Download failed"
    override val updateNoVerifiedApk = "No verified installer package ready"
    override val updateCachedApkCleared = "Cached installer package was cleared; please check again"
    override val updateApkMissing = "Installer package missing; please download again"
    override val updateLaunchInstallerFailed = "Unable to launch system installer"
    override val updateErrRateLimited = "GitHub API rate limit reached; please try again later"
    override val updateErrVerifyFailed = "Package signature or checksum verification failed"
    override val updateErrInsecureUrl = "Blocked insecure update URL"
    override val updateErrTimeout = "Connection to GitHub timed out; please check your network"
    override val updateErrNotFound = "No release published on GitHub yet"
    override val updateErrNoAsset = "No APK asset attached to this release yet"
    override val updateErrCorruptDownload = "Downloaded package is incomplete or corrupted"
    override val updateErrBadJson = "Unable to parse GitHub release metadata"
    override val updateErrCancelled = "Update cancelled"
    override val updateErrNetwork = "Unable to connect to GitHub"
    override val updateErrCannotReachGithub = "Unable to reach GitHub (please check network)"

    override val crayons = mapOf(
        "red" to "Crimson Red",
        "orange" to "Bright Orange",
        "yellow" to "Lemon Yellow",
        "apple_green" to "Apple Green",
        "forest_green" to "Forest Green",
        "sky_blue" to "Sky Blue",
        "ice_blue" to "Ice Blue",
        "royal_blue" to "Royal Blue",
        "lavender" to "Lavender",
        "magenta" to "Magenta",
        "peppa_pink" to "Bubblegum Pink",
        "peach" to "Peach",
        "caramel" to "Caramel Brown",
        "warm_gray" to "Warm Gray",
        "black" to "Jet Black",
        "white" to "Paper White",
    )

    override val cdBack = "Back"
    override val cdCrayon = "Crayon"
    override val cdPaintBucket = "Paint Bucket"
    override val cdEraser = "Eraser"
    override val cdClipLocked = "Stay inside lines locked"
    override val cdClipUnlocked = "Free coloring"
    override val cdUndo = "Undo"
    override val cdClearGhosting = "Clear ghosting"
    override val cdSaveExport = "Save & export"
    override val cdResetCanvas = "Reset canvas"
    override val cdStylusOnly = "Stylus only"
    override val cdFingerAndStylus = "Finger and stylus"
    override val cdGhostUnderlay = "Reference underlay"
    override val cdExpandRef = "Expand reference"
    override val cdCollapseRef = "Collapse reference"
    override val cdCollapseMoreColors = "Hide more colors"
    override val cdExpandMoreColors = "Show more colors"
    override val cdShowRef = "Show reference"
    override val cdRefImage = "Reference coloring image"
    override val cdAiMagic = "AI Magic"
    override val cdParentLock = "Parent lock"
    override val cdParentSettings = "Parent settings"
    override val cdNewVersion = "New version"
    override val cdSnowflake = "Snowflake"
    override val cdDog = "Puppy"
    override val cdPig = "Piggy"
    override val cdBunFace = "Bun Hero"
    override val cdLetters = "Letters"
    override val cdMyWorks = "My Works"
    override val cdStar = "Star"
    override val cdPrevPage = "Previous page"
    override val cdNextPage = "Next page"
    override val cdNewColoringPage = "New coloring page"
    override val cdBackspace = "Backspace"
}

object JaStrings : AppStrings {
override val language = ResolvedLanguage.JA

    override val appName = "シャオヌオお絵かき"
    override val back = "戻る"
    override val backArrow = "← 戻る"
    override val close = "閉じる"
    override val cancel = "キャンセル"
    override val ok = "決定"
    override val delete = "削除"
    override val save = "保存"
    override val useLabel = "適用"
    override val show = "表示"
    override val hide = "隠す"

    override val homeSubtitle = "すきなぬりえをえらんであそぼう"
    override val homeAiWorkshop = "AI アトリエ"
    override val homeUpdateBadge = "更新"
    override val pagerPrev = "まえへ"
    override val pagerNext = "つぎへ"
    override val pagerInfoTemplate = "%1\$d / %2\$d ページ · 全 %3\$d 枚"
    override val badgeColored = "ぬり済"
    override val badgeCompleted = "かんせい"
    override val difficultyEasy = "かんたん"
    override val difficultyStandard = "チャレンジ"
    override val difficultyAi = "AI 生成"
    override val difficultyImported = "インポート"
    override val homeNewAiTitle = "新しいぬりえを作る"
    override val homeNewAiSubtitle = "AI アトリエへ（保護者ロック）"
    override val homeNoWorks = "まだ作品がありません。すきな絵をえらんでぬってみよう！"
    override val tabTitles = mapOf(
        "elsa" to "エルサ",
        "bluey" to "ブルーイ",
        "peppa" to "ペッパピッグ",
        "anpanman" to "アンパンマン",
        "alphabet" to "アルファベット",
        "ai" to "AIマジック",
        "works" to "作品",
    )

    override val parentGateTitle = "保護者確認"
    override val parentGatePromptTemplate = "こたえてね：%s"
    override val parentGateWrong = "ちがうよ、べつのもんだいにチャレンジ！"
    override val parentGateLongPress = "または ここを 2 秒間長押し"

    override val canvasLoadError = "この絵を開けませんでした 😢"
    override val canvasLoading = "画用紙をじゅんび中…"
    override val canvasCelebration = "🎉 すごい！もうすぐかんせいだよ！"
    override val canvasEyedropperBlackLine = "そこは黒い線だよ。色のついたところをタップしてね"
    override val canvasEyedropperPickedTemplate = "色をとりました %s"
    override val canvasNothingToUndo = "もどせる操作がありません"
    override val canvasExportingPng = "高画質 PNG を書き出し中…"
    override val canvasExportSuccessTemplate = "✓ ギャラリーに保存しました %s"
    override val exportFailedTemplate = "書き出し失敗：%s"
    override val canvasConfirmReset = "全部けす？"
    override val canvasRefExpandedTitle = "見本 · タップした場所の色をとれます"
    override val canvasStylusOnlyOn = "ペン専用（誤タッチ防止）：指は拡大・移動のみ"
    override val canvasStylusOnlyOff = "指とペンの両方でぬれます"
    override val brushThin = "細"
    override val brushMedium = "中"
    override val brushThick = "太"

    override val galleryTitleTemplate = "作品ギャラリー（%d 枚）"
    override val galleryDatePattern = "MM/dd HH:mm"
    override val galleryInProgressTemplate = "ぬり中 %d%%"
    override val galleryStatusExported = " · 書き出し済"
    override val galleryStatusAutosaved = " · 自動保存"
    override val galleryContinue = "つづきから"
    override val galleryFreshCopy = "新しくぬる"
    override val galleryExportPng = "PNG 書出"
    override val galleryExporting = "書き出し中…"
    override val galleryMissingFile = "作品ファイルが見つかりません"
    override val galleryExportedTemplate = "書き出し完了：%s"
    override val galleryConfirmDelete = "削除する？"

    override val aiTabCombo = "🧩 かんたん組合せ"
    override val aiTabFree = "⌨️ 自由入力"
    override val aiTabPhoto = "📷 写真から線画"
    override val aiPhotoReadError = "この写真を読み込めませんでした"
    override val aiNeedApiKey = "先に ⚙️ 保護者設定 で Gemini API Key を設定してください"
    override val aiTitleAlphabetTemplate = "AI · 文字 %s"
    override val aiTitleComboTemplate = "AI · %1\$s・%2\$s"
    override val aiTitleFreeTemplate = "AI · %s"
    override val aiTitlePhoto = "AI · 写真の線画"
    override val aiNeedFreeText = "描きたい内容を入力してください"
    override val aiNeedPhoto = "先に写真を選択してください"
    override val aiPreparing = "準備中…"
    override val aiQcPassedPrefix = "✓ 閉領域チェック合格 · "
    override val aiQcWarningPrefix = "⚠️ 品質注意：領域数が想定外です · "
    override val aiGenerateFailedTemplate = "生成に失敗しました：%s"
    override val aiSavedToLibrary = "✓ 「AIマジック」ライブラリに保存しました"
    override val aiSaveFailedTemplate = "保存に失敗しました：%s"
    override val aiHeaderTitle = "✨ AI 魔法アトリエ（保護者モード）"
    override val aiModelLabelTemplate = "モデル：%s"
    override val aiComboCharacterScene = "キャラ × シーン"
    override val aiComboAlphabet = "🔤 アルファベット A–Z"
    override val aiStepCharacter = "1. 主役"
    override val aiStepScene = "2. シーン"
    override val aiPickLetterPromptTemplate = "文字をえらぶ（モチーフ：%s）"
    override val aiFreePlaceholder = "例：月の上でブランコに乗るうさぎ"
    override val aiPickPhoto = "📷 写真を選ぶ"
    override val aiChangePhoto = "📷 変更する"
    override val aiPhotoHint = "子ども・ペット・おもちゃの写真が使えます"
    override val aiStepDifficulty = "3. 線の細かさ："
    override val aiGenerating = "⏳ 生成中…"
    override val aiGenerateButton = "🪄 線画＋見本カラーを生成"
    override val aiPreviewLineArt = "ぬる前 · 1-bit 線画"
    override val aiPreviewRef = "ぬった後 · カラー見本"
    override val aiSaving = "⏳ 保存中…"
    override val aiSaveAndColor = "✓ 保存してすぐぬる"
    override val aiSaveOnly = "保存のみ"
    override val aiNoRef = "（見本なし）"
    override val promptDifficultyEasy = "大きめパーツ"
    override val promptDifficultyStandard = "こまかめ線画"
    override val promptCharacters = mapOf(
        "winter_princess" to "氷のプリンセス",
        "puppy" to "こいぬ",
        "piglet" to "こぶた",
        "bread_hero" to "パンのヒーロー",
        "dinosaur" to "ちび恐竜",
        "kitten" to "こねこ",
    )
    override val promptScenes = mapOf(
        "snowman" to "雪だるま作り",
        "sandcastle" to "砂のおしろ",
        "birthday_cake" to "お誕生日ケーキ",
        "hot_air_balloon" to "虹の気球",
        "space_rocket" to "宇宙ロケット",
        "garden_picnic" to "お花畑ピクニック",
    )
    override val alphabetObjects = listOf(
        "りんご", "くま", "ねこ", "いぬ", "たまご", "さかな", "ぶどう", "いえ", "アイスクリーム", "くらげ", "カイト", "ライオン",
        "つき", "とりのす", "タコ", "ペンギン", "おうかん", "にじ", "たいよう", "き", "かさ", "かざん", "くじら", "もっきん",
        "ヨーヨー", "しまうま",
    )

    override val aiStep1GenerateLine = "① 白黒の閉じた線画を生成しています…"
    override val aiStep2Sanitize = "② ローカル二値化・ノイズ除去・線閉じ検査中…"
    override val aiStep3Colorize = "③ ピクセル一致のカラー見本を生成しています…"
    override val aiDecodeLineError = "モデルが返した線画画像をデコードできませんでした"
    override val aiLineQcNoteTemplate = "線画検査：塗り分け可能 %d 領域"
    override val aiRefFailedSuffixTemplate = "（見本生成失敗：%s）"
    override val aiImportNoteTemplate = "インポート線画：塗り分け可能 %d 領域"
    override val geminiDefaultSuffix = "（デフォルト）"
    override val geminiNoImageReturnedTemplate = "モデルから画像が返されませんでした：%s"
    override val geminiMissingApiKeySettings = "先に保護者設定で Gemini API Key を入力してください"
    override val geminiMissingApiKeyShort = "先に API Key を入力してください"
    override val geminiNetworkFailedTemplate = "ネットワークエラー：%s"
    override val geminiParseFailed = "モデルの応答を解析できませんでした"
    override val geminiSafetyBlockedTemplate = "安全ポリシーによりブロックされました：%s"
    override val geminiMissingCandidates = "モデル応答に candidates がありません"
    override val geminiCorruptImage = "モデルの画像データが破損しています"
    override val geminiEmptyImage = "モデルの画像データが空です"
    override val geminiConnectSuccessTemplate = "接続成功 ✓ %s"
    override val geminiInvalidBaseUrlTemplate = "Base URL の形式が正しくありません（https:// で開始）：%s"
    override val geminiInvalidApiKeyChars = "API Key に無効な文字が含まれています"
    override val geminiHttp400 = "リクエストパラメータ不正"
    override val geminiHttp401_403 = "API Key が無効または権限不足です"
    override val geminiHttp404 = "モデルが存在しないか Base URL が誤っています"
    override val geminiHttp429 = "リクエスト過多またはクォータ不足です"
    override val geminiHttp5xx = "サービスが一時的に利用できません"
    override val geminiHttpOther = "リクエストに失敗しました"

    override val settingsTitle = "⚙️ 保護者設定"
    override val settingsVersionEditionTemplate = "v%s · BOOX 最適化版"
    override val settingsLanguageTitle = "表示言語"
    override val settingsLanguageDesc = "アプリの表示言語を選択します。変更はすぐに反映されます。"
    override val languageSystem = "システムに従う"
    override val settingsGeminiTitle = "Gemini AI 画像生成設定"
    override val settingsApiKeyLabel = "API Key（端末内のみに保存）"
    override val settingsBaseUrlLabel = "カスタム Base URL プロキシ（任意）"
    override val settingsModelLabel = "モデル"
    override val settingsCustomModelPlaceholder = "カスタムモデル名（例：gemini-xxx-image）"
    override val settingsSaved = "✓ 保存しました"
    override val settingsTestConnectivity = "接続テスト"
    override val settingsTesting = "テスト中…"
    override val settingsBooxCardTitle = "BOOX 電子ペーパー・操作・診断"
    override val settingsOnyxDeviceTemplate = "Onyx デバイス：%s"
    override val settingsBooxSdkModeTitle = "BOOX SDK モード"
    override val settingsBooxSdkOff = "SDK オフ（標準）"
    override val settingsBooxSdkEpdOnly = "EPD 高速更新のみ"
    override val settingsBooxSdkFull = "フル（直描き）"
    override val settingsBooxSdkDesc = "「SDK オフ」は標準 Android 描画と同一；「EPD 高速更新のみ」は実色表示とはみ出し防止を維持；「フル（直描き）」はハードウェアペン層を有効にします。"
    override val settingsRefreshModeTitle = "デフォルト更新モード"
    override val settingsRefreshRegalRec = "REGAL くっきり（推奨）"
    override val settingsSoundTitle = "効果音とお祝いサウンド"
    override val settingsSoundOff = "消音（デフォルト）"
    override val settingsSoundOn = "やさしい効果音"
    override val settingsPalmRejectionTitle = "誤タッチ防止"
    override val settingsInputBoth = "👆 指・ペン両用"
    override val settingsInputStylusOnly = "✍️ ペン専用"
    override val settingsImportTitle = "ローカルから取り込み"
    override val settingsImportDesc = "ギャラリーやファイルから線画画像を選び、自動で二値化・ノイズ除去・線閉じして「AIマジック」へ追加します。"
    override val settingsImportButton = "📂 ギャラリー / ファイルから取り込む"
    override val settingsImportBusy = "取り込みと閉線画への変換中…"
    override val settingsImportDecodeError = "画像を読み込めませんでした"
    override val settingsImportTitleTemplate = "インポート · %s"
    override val settingsImportFailedTemplate = "取り込み失敗：%s"
    override val settingsDiagTitle = "診断"
    override val settingsDiagDescTemplate = "ペンの遅延、画面更新、BOOX ペン層の状態を記録します（直近 %1\$d 件、現在 %2\$d 件、絵の内容は含みません）。動作が重い時に数本描いてから書き出してください。"
    override val settingsDiagExport = "診断ログを保存"
    override val settingsDiagExporting = "書き出し中…"
    override val settingsDiagExportOkTemplate = "✓ %1\$d 件のログを %2\$s に保存しました"
    override val settingsDiagExportFailedTemplate = "✗ 保存失敗：%s"
    override val settingsDiagShare = "ログを共有"
    override val settingsDiagShareOkTemplate = "共有を開きました（%1\$d 件 · %2\$s）"
    override val settingsDiagShareLaunchFailedTemplate = "✗ 共有できませんでした：%s"
    override val settingsDiagShareFailedTemplate = "✗ 共有失敗：%s"
    override val diagCreateFileFailed = "ダウンロードファイルを作成できませんでした"
    override val diagWriteFailed = "ダウンロードフォルダに書き込めませんでした"
    override val diagShareChooserTitle = "診断ログを共有"
    override val galleryCreateFileFailed = "ギャラリーファイルを作成できませんでした"
    override val galleryWriteFailed = "ギャラリーに書き込めませんでした"

    override val einkModeNormal = "標準"
    override val einkModeRegal = "REGAL くっきり"
    override val einkModeFast = "高速"
    override val einkModeFull = "全画面更新"
    override val booxSdkModeOffShort = "SDK オフ"
    override val booxSdkModeEpdOnlyShort = "高速更新のみ"
    override val booxSdkModeFullShort = "フル（直描き）"
    override val booxStatusNotBooxTemplate = "BOOX 端末未検出（%1\$s %2\$s）· 標準 Android 更新モード"
    override val booxStatusSdkDisabledTemplate = "BOOX %s を検出 · BOOX SDK オフ（標準 Android 更新モード）"
    override val booxStatusSdkFailedTemplate = "BOOX 端末を検出しましたが Onyx SDK の初期化に失敗しました（%s）· 標準更新に切替"
    override val booxStatusUninitialized = "未初期化"
    override val booxStatusEpdTouchHelperTemplate = "BOOX %1\$s を検出 ✓ EPD 部分更新 + TouchHelper ハードウェア直描き（%2\$s）"
    override val booxStatusEpdOnlyReason = "「EPD 高速更新のみ」モード"
    override val booxStatusNoStylusReason = "スタイラス未検出または非 arm64 端末"
    override val booxStatusEpdTemplate = "BOOX %1\$s を検出 ✓ EPD 部分更新 · ハードウェア直描き無効（%2\$s）"
    override val booxTouchHelperMissedTemplate = "TouchHelper が %d ストローク連続で入力を返しませんでした"

    override val otaTitle = "ソフトウェア更新（GitHub OTA）"
    override val otaCurrentVersionTemplate = "現在 v%s"
    override val otaIdleDesc = "GitHub Releases の新バージョンを自動確認し、SHA-256 と署名検証後にワンタップで更新します。"
    override val otaCheckButton = "🔄 更新を確認"
    override val otaChecking = "GitHub に接続して更新を確認中…"
    override val otaUpToDateTemplate = "✓ 最新バージョンです（v%s）"
    override val otaRecheckButton = "🔄 再確認"
    override val otaAvailableTemplate = "🎉 新バージョンあり：%1\$s%2\$s"
    override val otaDownloadInstallButton = "⬇️ ダウンロードして更新"
    override val otaReleasePageButton = "🌐 リリース"
    override val otaDownloadingPctTemplate = "%1\$s をダウンロード中：%2\$d%%（%3\$s %4\$s%5\$s）"
    override val otaDownloadingNoPctTemplate = "%1\$s をダウンロード中：%2\$s %3\$s%4\$s"
    override val otaCancelDownloadButton = "ダウンロードを中止"
    override val otaVerifying = "パッケージの整合性と署名を検証中…"
    override val otaReadyTemplate = "✓ %s のダウンロードと署名検証が完了しました"
    override val otaInstallNowTemplate = "📦 %s を今すぐインストール"
    override val otaRetryButton = "🔄 再試行"
    override val otaInstallerLaunched = "システムインストーラーを起動しました"
    override val otaUnknownSourcesHint = "「提供元不明のアプリ」を許可してください。戻ると自動でインストールを開始します"
    override val updateApkReadInstalledFailed = "インストール済みアプリの情報を読み取れません"
    override val updateApkParseFailed = "APK の解析に失敗しました。ファイルが破損している可能性があります"
    override val updateApkPackageMismatchTemplate = "APK のパッケージ名が一致しません (%s)"
    override val updateApkDowngradeTemplate = "新パッケージのバージョンコード (%1\$d) が現在 (%2\$d) より古いため中止しました"
    override val updateApkSignatureMismatch = "APK の署名が現在のアプリと一致しないためインストールを中止しました"
    override val updateFoundNoAssetTemplate = "%s が見つかりましたが APK ファイルがまだ添付されていません"
    override val updateCheckFailed = "更新の確認に失敗しました"
    override val updateDownloadFailed = "ダウンロードに失敗しました"
    override val updateNoVerifiedApk = "検証済みのインストーラーがありません"
    override val updateCachedApkCleared = "キャッシュされた APK が削除されました。再度更新を確認してください"
    override val updateApkMissing = "APK ファイルが見つかりません。再度ダウンロードしてください"
    override val updateLaunchInstallerFailed = "システムインストーラーを起動できませんでした"
    override val updateErrRateLimited = "GitHub のリクエスト制限に達しました。しばらくしてから再試行してください"
    override val updateErrVerifyFailed = "パッケージの署名またはチェックサム検証に失敗しました"
    override val updateErrInsecureUrl = "安全でない更新 URL をブロックしました"
    override val updateErrTimeout = "GitHub への接続がタイムアウトしました。ネットワークを確認してください"
    override val updateErrNotFound = "GitHub に公開済みリリースがまだありません"
    override val updateErrNoAsset = "このリリースには APK ファイルが添付されていません"
    override val updateErrCorruptDownload = "ダウンロードしたパッケージが不完全または破損しています"
    override val updateErrBadJson = "GitHub のリリース情報を解析できませんでした"
    override val updateErrCancelled = "更新をキャンセルしました"
    override val updateErrNetwork = "GitHub に接続できませんでした"
    override val updateErrCannotReachGithub = "GitHub に接続できません（ネットワークを確認してください）"

    override val crayons = mapOf(
        "red" to "あか",
        "orange" to "オレンジ",
        "yellow" to "きいろ",
        "apple_green" to "きみどり",
        "forest_green" to "みどり",
        "sky_blue" to "みずいろ",
        "ice_blue" to "あお",
        "royal_blue" to "こんいろ",
        "lavender" to "むらさき",
        "magenta" to "マゼンタ",
        "peppa_pink" to "ピンク",
        "peach" to "うすだいだい",
        "caramel" to "ちゃいろ",
        "warm_gray" to "グレー",
        "black" to "くろ",
        "white" to "しろ",
    )

    override val cdBack = "戻る"
    override val cdCrayon = "クレヨン"
    override val cdPaintBucket = "バケツ塗り"
    override val cdEraser = "消しゴム"
    override val cdClipLocked = "はみ出し防止オン"
    override val cdClipUnlocked = "自由塗り"
    override val cdUndo = "元に戻す"
    override val cdClearGhosting = "残像クリア"
    override val cdSaveExport = "保存して書き出し"
    override val cdResetCanvas = "キャンバスをリセット"
    override val cdStylusOnly = "ペン専用"
    override val cdFingerAndStylus = "指とペン両用"
    override val cdGhostUnderlay = "下絵ガイド"
    override val cdExpandRef = "見本を拡大"
    override val cdCollapseRef = "見本を折りたたむ"
    override val cdCollapseMoreColors = "カラーパレットを閉じる"
    override val cdExpandMoreColors = "もっと色を見る"
    override val cdShowRef = "見本を表示"
    override val cdRefImage = "カラー見本画像"
    override val cdAiMagic = "AIマジック"
    override val cdParentLock = "保護者ロック"
    override val cdParentSettings = "保護者設定"
    override val cdNewVersion = "新バージョン"
    override val cdSnowflake = "雪の結晶"
    override val cdDog = "こいぬ"
    override val cdPig = "こぶた"
    override val cdBunFace = "パンの顔"
    override val cdLetters = "アルファベット"
    override val cdMyWorks = "作品"
    override val cdStar = "ほし"
    override val cdPrevPage = "前のページ"
    override val cdNextPage = "次のページ"
    override val cdNewColoringPage = "新しいぬりえを作る"
    override val cdBackspace = "1文字消す"
}
