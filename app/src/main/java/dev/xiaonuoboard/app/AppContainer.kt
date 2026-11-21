package dev.xiaonuoboard.app

import android.content.Context
import dev.xiaonuoboard.app.ai.AiPipeline
import dev.xiaonuoboard.app.ai.GeminiImageClient
import dev.xiaonuoboard.app.data.ArtworkRepository
import dev.xiaonuoboard.app.data.PageRepository
import dev.xiaonuoboard.app.data.SettingsStore
import dev.xiaonuoboard.app.data.SoundFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI container (keeps the APK lean; no DI framework needed for a single-activity app). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsStore(appContext)
    val pages = PageRepository(appContext)
    val artworks = ArtworkRepository(appContext)
    val gemini = GeminiImageClient({ settings.current.geminiConfig() })
    val aiPipeline = AiPipeline(gemini)
    val sound = SoundFeedback { settings.current.soundEnabled }
    /** Outlives screens so auto-saves / exports finish even after navigating away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
