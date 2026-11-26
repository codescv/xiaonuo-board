package dev.xiaonuoboard.app

import android.content.SharedPreferences
import dev.xiaonuoboard.app.ai.PromptBuilder
import dev.xiaonuoboard.app.data.AppSettings
import dev.xiaonuoboard.app.data.BuiltinManifest
import dev.xiaonuoboard.app.data.ColoringPage
import dev.xiaonuoboard.app.data.DiagLogExporter
import dev.xiaonuoboard.app.data.HomeTabs
import dev.xiaonuoboard.app.data.PageSource
import dev.xiaonuoboard.app.data.SettingsStore
import dev.xiaonuoboard.app.eink.BooxSdkMode
import dev.xiaonuoboard.app.eink.EinkMode
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.engine.BrushSize
import dev.xiaonuoboard.app.engine.KaleidoPalette
import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppLanguage
import dev.xiaonuoboard.app.i18n.AppStrings
import dev.xiaonuoboard.app.i18n.EnStrings
import dev.xiaonuoboard.app.i18n.JaStrings
import dev.xiaonuoboard.app.i18n.ResolvedLanguage
import dev.xiaonuoboard.app.i18n.ZhStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class I18nTest {

    private val cjkRegex = Regex("[\u4E00-\u9FFF]")
    private val kanaRegex = Regex("[\u3040-\u30FF]")

    /** Technical proper nouns / identifiers permitted alongside CJK/Kana in localized UI strings. */
    private val allowedAsciiTokens = setOf(
        "AI", "BOOX", "Onyx", "REGAL", "EPD", "TouchHelper", "Gemini", "GitHub", "OTA",
        "PNG", "APK", "SDK", "API", "Key", "Base", "URL", "SHA-256", "SHA", "HTTP",
        "Android", "Releases", "1-bit", "bit", "arm64", "https", "candidates",
        "gemini-xxx-image", "gemini", "xxx", "image", "v", "A", "Z", "Aa", "Bb", "Cc", "Dd", "Ee",
        "Ff", "Gg", "Hh", "Ii", "Jj", "Kk", "Ll", "Mm", "Nn", "Oo", "Pp", "Qq", "Rr", "Ss", "Tt",
        "Uu", "Vv", "Ww", "Xx", "Yy", "Zz", "UFO", "MB", "KB", "s", "d", "MM", "dd", "HH", "mm",
    )

    private val assetsRoot: File = listOf("src/main/assets", "app/src/main/assets")
        .map { File(it, BuiltinManifest.ASSET_DIR) }
        .firstOrNull { File(it, "manifest.json").exists() }
        ?: error("builtin_packs/manifest.json not found")

    private val resRoot: File = listOf("src/main/res", "app/src/main/res")
        .map { File(it) }
        .firstOrNull { File(it, "values/strings.xml").exists() }
        ?: error("res directory not found")

    @After
    fun tearDown() {
        AppI18n.resetForTest()
    }

    @Test
    fun systemLanguageResolutionMapsZhJaAndOtherToEn() {
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.TRADITIONAL_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.forLanguageTag("zh-SG")))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPANESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPAN))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.UK))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.FRANCE))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.GERMANY))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.KOREA))

        // Explicit selections ignore system locale.
        assertEquals(ResolvedLanguage.ZH, AppLanguage.ZH.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.EN.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.JA.resolve(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun settingsStorePersistsLanguageRoundTripAndDefaultsToSystem() {
        val prefs = InMemorySharedPreferences()
        val store1 = SettingsStore(prefs)
        assertEquals(AppLanguage.SYSTEM, store1.current.language)

        for (lang in listOf(AppLanguage.EN, AppLanguage.JA, AppLanguage.ZH, AppLanguage.SYSTEM)) {
            store1.update { it.copy(language = lang) }
            assertEquals(lang, store1.current.language)
            val reloaded = SettingsStore(prefs)
            assertEquals("persisted $lang", lang, reloaded.current.language)
        }

        // Invalid / unknown stored value degrades gracefully to SYSTEM.
        prefs.edit().putString("ui_language", "xx-invalid").apply()
        assertEquals(AppLanguage.SYSTEM, SettingsStore(prefs).current.language)
    }

    @Test
    fun allLanguagesHaveFullKeyParityAndNonEmptyStrings() {
        val zh = ZhStrings.allEntries()
        val en = EnStrings.allEntries()
        val ja = JaStrings.allEntries()

        assertTrue("expected substantial string table", zh.size >= 150)
        assertEquals("ZH vs EN key parity", zh.keys, en.keys)
        assertEquals("ZH vs JA key parity", zh.keys, ja.keys)

        for (pack in AppStrings.ALL) {
            val entries = pack.allEntries()
            for ((k, v) in entries) {
                assertTrue("${pack.language.code}.$k must not be blank", v.isNotBlank())
            }
            assertEquals("${pack.language.code} alphabetObjects count", 26, pack.alphabetObjects.size)
            assertEquals(
                "${pack.language.code} tabTitles keys",
                HomeTabs.ALL.map { it.id }.toSet(),
                pack.tabTitles.keys,
            )
            assertEquals(
                "${pack.language.code} promptCharacters keys",
                PromptBuilder.CHARACTERS.map { it.id }.toSet(),
                pack.promptCharacters.keys,
            )
            assertEquals(
                "${pack.language.code} promptScenes keys",
                PromptBuilder.SCENES.map { it.id }.toSet(),
                pack.promptScenes.keys,
            )
            assertEquals(
                "${pack.language.code} crayons keys",
                KaleidoPalette.CRAYONS.map { it.id }.toSet(),
                pack.crayons.keys,
            )

            // Verify all enum helpers & format templates render without error.
            BrushSize.entries.forEach { assertTrue(it.label(pack).isNotBlank()) }
            EinkMode.entries.forEach { assertTrue(it.label(pack).isNotBlank()) }
            BooxSdkMode.entries.forEach { assertTrue(it.label(pack).isNotBlank()) }
            PromptBuilder.Difficulty.entries.forEach { assertTrue(it.label(pack).isNotBlank()) }
            assertTrue(pack.pagerInfo(1, 3, 10).isNotBlank())
            assertTrue(pack.parentGatePrompt("3 × 4 = ?").isNotBlank())
            assertTrue(pack.galleryTitle(5).isNotBlank())
            assertTrue(pack.galleryInProgress(55).isNotBlank())
            assertTrue(pack.settingsDiagDesc(500, 12).isNotBlank())
            assertTrue(pack.otaDownloadingPct("v1.1.9", 42, "5.2", "/ 12.3 MB", " · 512 KB/s").isNotBlank())
            assertTrue(pack.otaDownloadingNoPct("v1.1.9", "5.2", "MB", "").isNotBlank())
            assertTrue(pack.updateApkDowngrade(10L, 11L).isNotBlank())
            assertTrue(OnyxEinkBridge.statusText(pack).contains("BOOX"))
        }
    }

    @Test
    fun zeroBilingualLeaksAcrossStringsAndDiagHeader() {
        // English pack must contain ZERO CJK or Japanese Kana characters.
        for ((k, v) in EnStrings.allEntries()) {
            assertFalse("EN.$k leaked CJK: $v", cjkRegex.containsMatchIn(v))
            assertFalse("EN.$k leaked Kana: $v", kanaRegex.containsMatchIn(v))
        }

        // Chinese pack must contain ZERO Japanese Kana and no non-technical English words.
        for ((k, v) in ZhStrings.allEntries()) {
            assertFalse("ZH.$k leaked Kana: $v", kanaRegex.containsMatchIn(v))
            if (k != "galleryDatePattern") assertNoUnexpectedEnglishWords("ZH.$k", v)
        }

        // Japanese pack must contain no non-technical English words.
        for ((k, v) in JaStrings.allEntries()) {
            if (k != "galleryDatePattern") assertNoUnexpectedEnglishWords("JA.$k", v)
        }

        // Date patterns must be valid SimpleDateFormat patterns for their respective locales.
        for (pack in AppStrings.ALL) {
            val formatted = java.text.SimpleDateFormat(pack.galleryDatePattern, pack.language.toLocale()).format(java.util.Date(0L))
            assertTrue("${pack.language.code} galleryDatePattern formatted", formatted.isNotBlank())
        }

        // Diagnostic report header must not be bilingual CN+EN.
        val report = DiagLogExporter.buildReport(AppSettings(language = AppLanguage.EN), nowMillis = 0L)
        val firstLine = report.lineSequence().first()
        assertEquals("=== XiaonuoBoard diagnostic log ===", firstLine)
        assertFalse("diag header leaked CJK: $firstLine", cjkRegex.containsMatchIn(firstLine))
    }

    @Test
    fun manifestLocalizesAllCategoriesAndSixtySixPagesWithoutBilingualLeaks() {
        val manifest = BuiltinManifest.parse(File(assetsRoot, "manifest.json").readText())
        assertEquals(5, manifest.categories.size)
        assertEquals(66, manifest.pages.size)

        for (cat in manifest.categories) {
            assertTrue("${cat.id} title_zh", cat.titleZh.isNotBlank())
            assertTrue("${cat.id} title_en", cat.titleEn.isNotBlank())
            assertTrue("${cat.id} title_ja", cat.titleJa.isNotBlank())
            assertFalse("${cat.id} title_en CJK", cjkRegex.containsMatchIn(cat.titleEn))
            assertFalse("${cat.id} title_en Kana", kanaRegex.containsMatchIn(cat.titleEn))
            assertFalse("${cat.id} title_zh Kana", kanaRegex.containsMatchIn(cat.titleZh))
        }

        for (p in manifest.pages) {
            assertTrue("${p.id} title_zh", p.titleZh.isNotBlank())
            assertTrue("${p.id} title_en", p.titleEn.isNotBlank())
            assertTrue("${p.id} title_ja", p.titleJa.isNotBlank())
            assertFalse("${p.id} title_en CJK: ${p.titleEn}", cjkRegex.containsMatchIn(p.titleEn))
            assertFalse("${p.id} title_en Kana: ${p.titleEn}", kanaRegex.containsMatchIn(p.titleEn))
            assertNoUnexpectedEnglishWords("${p.id}.title_zh", p.titleZh)
            assertNoUnexpectedEnglishWords("${p.id}.title_ja", p.titleJa)

            if (p.category == "alphabet") {
                val up = 'A' + (p.index - 1)
                val pair = "$up${up.lowercaseChar()}"
                assertTrue("${p.id} zh starts with $pair: ${p.titleZh}", p.titleZh.startsWith("$pair "))
                assertTrue("${p.id} en starts with $pair: ${p.titleEn}", p.titleEn.startsWith("$pair "))
                assertTrue("${p.id} ja starts with $pair: ${p.titleJa}", p.titleJa.startsWith("$pair "))
                // Verify bilingual "Aa 苹果 Apple" format is gone: after the letter pair, zh has no Latin letters.
                val zhRest = p.titleZh.removePrefix("$pair ")
                assertFalse("${p.id} zh leaked English word: ${p.titleZh}", Regex("[A-Za-z]").containsMatchIn(zhRest))
                val jaRest = p.titleJa.removePrefix("$pair ")
                assertFalse("${p.id} ja leaked English word: ${p.titleJa}", Regex("[A-Za-z]").containsMatchIn(jaRest))
            }
        }

        // ColoringPage.localizedTitle localizes BUILTIN pages but keeps AI/IMPORTED titles as created.
        val builtin = ColoringPage(
            id = "alphabet_01", category = "alphabet", title = "Aa 苹果",
            titleZh = "Aa 苹果", titleEn = "Aa Apple", titleJa = "Aa りんご",
            difficulty = "easy", linePath = "l", refPath = "r", source = PageSource.BUILTIN,
        )
        assertEquals("Aa 苹果", builtin.localizedTitle(ResolvedLanguage.ZH))
        assertEquals("Aa Apple", builtin.localizedTitle(ResolvedLanguage.EN))
        assertEquals("Aa りんご", builtin.localizedTitle(ResolvedLanguage.JA))

        val userAi = ColoringPage(
            id = "ai_1", category = "ai", title = "My Custom Dragon",
            titleZh = "My Custom Dragon", titleEn = "My Custom Dragon", titleJa = "My Custom Dragon",
            difficulty = "ai", linePath = "l", refPath = "r", source = PageSource.AI,
        )
        for (lang in ResolvedLanguage.entries) {
            assertEquals("My Custom Dragon", userAi.localizedTitle(lang))
        }
    }

    @Test
    fun promptBuilderModelPromptsRemainEnglishAcrossAllUiLanguages() {
        for (lang in ResolvedLanguage.entries) {
            AppI18n.setResolved(lang)

            assertFalse(cjkRegex.containsMatchIn(PromptBuilder.COLORIZE))
            assertFalse(kanaRegex.containsMatchIn(PromptBuilder.COLORIZE))

            for (obj in PromptBuilder.ALPHABET_OBJECTS) {
                assertFalse("ALPHABET_OBJECTS '$obj' must stay English", cjkRegex.containsMatchIn(obj))
                assertFalse("ALPHABET_OBJECTS '$obj' must stay English", kanaRegex.containsMatchIn(obj))
            }
            for (c in PromptBuilder.CHARACTERS) {
                assertFalse("CHARACTER '${c.id}' english prompt", cjkRegex.containsMatchIn(c.english))
                assertTrue("CHARACTER '${c.id}' localized label", c.label().isNotBlank())
            }
            for (sc in PromptBuilder.SCENES) {
                assertFalse("SCENE '${sc.id}' english prompt", cjkRegex.containsMatchIn(sc.english))
                assertTrue("SCENE '${sc.id}' localized label", sc.label().isNotBlank())
            }
            val comboPrompt = PromptBuilder.combo(PromptBuilder.CHARACTERS[0], PromptBuilder.SCENES[0], PromptBuilder.Difficulty.EASY)
            assertFalse("combo prompt must remain English in $lang", cjkRegex.containsMatchIn(comboPrompt))
            assertFalse("combo prompt must remain English in $lang", kanaRegex.containsMatchIn(comboPrompt))
            assertTrue(comboPrompt.contains("Black and white children's coloring book page"))

            val alphaPrompt = PromptBuilder.alphabet('A', PromptBuilder.ALPHABET_OBJECTS[0], PromptBuilder.Difficulty.STANDARD)
            assertFalse("alphabet prompt must remain English in $lang", cjkRegex.containsMatchIn(alphaPrompt))
            assertTrue(alphaPrompt.contains("apple"))

            val photoPrompt = PromptBuilder.photoToLineArt(PromptBuilder.Difficulty.EASY)
            assertFalse("photo prompt must remain English in $lang", cjkRegex.containsMatchIn(photoPrompt))
        }
    }

    @Test
    fun androidStringResourcesDefineLocalizedAppNames() {
        val enXml = File(resRoot, "values/strings.xml").readText()
        val zhXml = File(resRoot, "values-zh-rCN/strings.xml").readText()
        val jaXml = File(resRoot, "values-ja/strings.xml").readText()

        assertTrue(enXml.contains("<string name=\"app_name\">XiaonuoBoard</string>"))
        assertTrue(zhXml.contains("<string name=\"app_name\">小诺画板</string>"))
        assertTrue(jaXml.contains("<string name=\"app_name\">シャオヌオお絵かき</string>"))
    }

    private fun assertNoUnexpectedEnglishWords(key: String, value: String) {
        // Strip printf placeholders like %1$s, %s, %d before scanning ASCII words.
        val stripped = value.replace(Regex("%(\\d+\\$)?[sd]"), " ")
        val words = Regex("[A-Za-z][A-Za-z0-9-]*").findAll(stripped).map { it.value }.toList()
        val unexpected = words.filterNot { it in allowedAsciiTokens }
        assertTrue("$key has unexpected non-technical English words $unexpected in '$value'", unexpected.isEmpty())
    }

    /** Minimal pure-JVM in-memory SharedPreferences for testing [SettingsStore] persistence. */
    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
