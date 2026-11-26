package dev.xiaonuoboard.app.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.data.ColoringPage
import dev.xiaonuoboard.app.data.ParentGate
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Small LRU cache of decoded thumbnails (≈ 300×400) to keep paging instant and memory bounded. */
object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, b: Bitmap) { cache.put(key, b) }
    fun invalidate(prefix: String) {
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}

@Composable
fun PageThumb(page: ColoringPage, ref: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = "page:${page.id}:$ref"
    val bmp by produceState<Bitmap?>(ThumbCache.get(key), key) {
        val cached = ThumbCache.get(key)
        if (cached != null) {
            value = cached
        } else {
            value = null
            value = withContext(Dispatchers.IO) {
                BitmapIO.loadPageBitmap(context, page, ref, sample = if (ref) 8 else 4)?.also { ThumbCache.put(key, it) }
            }
        }
    }
    ThumbImage(bmp, modifier)
}

@Composable
fun FileThumb(file: File, version: Long, modifier: Modifier = Modifier) {
    val key = "file:${file.absolutePath}:$version"
    val bmp by produceState<Bitmap?>(ThumbCache.get(key), key) {
        val cached = ThumbCache.get(key)
        if (cached != null) {
            value = cached
        } else {
            value = null
            value = withContext(Dispatchers.IO) {
                if (file.exists()) BitmapIO.decode(file.inputStream())?.also { ThumbCache.put(key, it) } else null
            }
        }
    }
    ThumbImage(bmp, modifier)
}

@Composable
private fun ThumbImage(bmp: Bitmap?, modifier: Modifier) {
    Box(modifier.background(Eink.White), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("…", color = Eink.Muted)
        }
    }
}

/**
 * Parent lock: answer a single-digit multiplication, or press-and-hold the lock area for 2 seconds.
 * Big on-screen number pad (no soft keyboard → no E-Ink keyboard flashing).
 */
@Composable
fun ParentGateDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val s = LocalAppStrings.current
    val gate = remember { ParentGate() }
    var question by remember { mutableStateOf(gate.current) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(440.dp)
                .background(Eink.White, Eink.Shape)
                .border(3.dp, Eink.Ink, Eink.Shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KidIcons.ParentLock(Eink.Ink, Modifier.size(30.dp))
                Text(s.parentGateTitle, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(s.parentGatePrompt(question.text), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Box(
                Modifier.fillMaxWidth().height(60.dp).border(Eink.Border, Eink.Ink, Eink.Shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (input.isEmpty()) " " else input, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (error) Text(s.parentGateWrong, color = Eink.Ink, fontSize = 16.sp)
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("BS", "0", "OK"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        val onKey: () -> Unit = {
                            when (key) {
                                "BS" -> input = input.dropLast(1)
                                "OK" -> if (gate.check(input)) onUnlocked() else {
                                    error = true; input = ""; question = gate.current
                                }
                                else -> if (input.length < 3) input += key
                            }
                        }
                        if (key == "BS") {
                            EinkIconButton(Modifier.weight(1f), onClick = onKey) { tint -> KidIcons.Backspace(tint, Modifier.size(34.dp)) }
                        } else {
                            val label = if (key == "OK") s.ok else key
                            EinkButton(label, modifier = Modifier.weight(1f), selected = key == "OK", fontSize = 22, onClick = onKey)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(Eink.Border, Eink.Ink, Eink.Shape)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            // Timeout (null) ⇔ the finger was still down after LONG_PRESS_MILLIS (monotonic clock).
                            val released = withTimeoutOrNull(ParentGate.LONG_PRESS_MILLIS) { tryAwaitRelease() }
                            if (released == null) onUnlocked()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(s.parentGateLongPress, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            EinkButton(s.cancel, modifier = Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}
