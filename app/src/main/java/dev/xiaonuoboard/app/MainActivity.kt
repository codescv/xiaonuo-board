package dev.xiaonuoboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import dev.xiaonuoboard.app.eink.OnyxEinkBridge
import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import dev.xiaonuoboard.app.ui.AiWorkshopScreen
import dev.xiaonuoboard.app.ui.CanvasScreen
import dev.xiaonuoboard.app.ui.Eink
import dev.xiaonuoboard.app.ui.GalleryScreen
import dev.xiaonuoboard.app.ui.HomeScreen
import dev.xiaonuoboard.app.ui.ParentGateDialog
import dev.xiaonuoboard.app.ui.SettingsScreen
import dev.xiaonuoboard.app.ui.XiaonuoTheme
import java.util.Locale

/** Simple, animation-free navigation (no transitions → no E-Ink ghosting). */
sealed interface Screen {
    data object Home : Screen
    data class Canvas(val pageId: String, val artworkId: String?, val fresh: Boolean = false) : Screen
    data object Gallery : Screen
    data object AiWorkshop : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sync saved UI language & BOOX SDK mode before init/canvas creation.
        AppI18n.setLanguage(container.settings.current.language)
        OnyxEinkBridge.setSdkMode(container.settings.current.booxSdkMode)
        // BOOX only (no-op elsewhere): hidden-API exemption + Onyx SDK init + EPD / stylus probe,
        // before any canvas creates a TouchHelper.
        OnyxEinkBridge.init(this)
        container.updater.checkOnStartupIfDue()
        setContent {
            XiaonuoTheme { XiaonuoApp(container) }
        }
    }

    override fun onResume() {
        super.onResume()
        container.updater.onHostResume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The container (and its ToneGenerator) is owned by this activity instance.
        container.sound.release()
    }
}

@Composable
fun XiaonuoApp(container: AppContainer) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var homeTab by rememberSaveable { mutableStateOf(0) }
    var gateTarget by remember { mutableStateOf<Screen?>(null) }
    val settings by container.settings.settings.collectAsState()
    val config = LocalConfiguration.current
    val systemLocale = remember(config) {
        val list = config.locales
        if (!list.isEmpty) list[0] else Locale.getDefault()
    }
    val resolvedLanguage = remember(settings.language, systemLocale) {
        settings.language.resolve(systemLocale)
    }
    val strings = remember(resolvedLanguage) { AppStrings.forLanguage(resolvedLanguage) }
    SideEffect { AppI18n.setResolved(resolvedLanguage) }

    val screen = stack.last()

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replaceTop(s: Screen) { stack = stack.dropLast(1) + s }

    val view = LocalView.current
    // A canvas keeps its identity when its first auto-save assigns an artworkId (Screen.Canvas is
    // replaced in place): no root refresh then, it could land mid-stroke under the BOOX pen layer.
    val screenKey: Any = if (screen is Screen.Canvas) "canvas:${screen.pageId}" else screen
    LaunchedEffect(screenKey, settings.einkMode, settings.booxSdkMode) {
        OnyxEinkBridge.setSdkMode(settings.booxSdkMode)
        // Browsing screens use the parent-selected clear mode; each screen switch gets one clean refresh.
        OnyxEinkBridge.setViewDefaultMode(view, settings.einkMode)
        OnyxEinkBridge.refresh(view, settings.einkMode)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    CompositionLocalProvider(LocalAppStrings provides strings) {
        Box(Modifier.fillMaxSize().background(Eink.Paper).safeDrawingPadding()) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    container = container,
                    selectedTab = homeTab,
                    onSelectTab = { homeTab = it },
                    onOpenPage = { pageId -> push(Screen.Canvas(pageId, container.artworks.latestFor(pageId)?.id)) },
                    onOpenArtwork = { art -> push(Screen.Canvas(art.pageId, art.id)) },
                    onFreshCopy = { pageId -> push(Screen.Canvas(pageId, null, fresh = true)) },
                    onOpenAi = { gateTarget = Screen.AiWorkshop },
                    onOpenSettings = { gateTarget = Screen.Settings },
                )
                is Screen.Canvas -> CanvasScreen(
                    container = container,
                    pageId = screen.pageId,
                    artworkId = screen.artworkId,
                    onArtworkCreated = { id -> replaceTop(screen.copy(artworkId = id, fresh = false)) },
                    onBack = { pop() },
                )
                Screen.Gallery -> GalleryScreen(
                    container = container,
                    onBack = { pop() },
                    onContinue = { art -> push(Screen.Canvas(art.pageId, art.id)) },
                    onFreshCopy = { pageId -> push(Screen.Canvas(pageId, null, fresh = true)) },
                )
                Screen.AiWorkshop -> AiWorkshopScreen(
                    container = container,
                    onBack = { pop() },
                    onColorNow = { pageId -> replaceTop(Screen.Canvas(pageId, null, fresh = true)) },
                )
                Screen.Settings -> SettingsScreen(
                    container = container,
                    onBack = { pop() },
                    onImported = { pageId -> replaceTop(Screen.Canvas(pageId, null, fresh = true)) },
                )
            }
            gateTarget?.let { target ->
                ParentGateDialog(
                    onDismiss = { gateTarget = null },
                    onUnlocked = { gateTarget = null; push(target) },
                )
            }
        }
    }
}
