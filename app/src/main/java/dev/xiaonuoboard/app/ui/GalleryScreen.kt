package dev.xiaonuoboard.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.data.Artwork
import dev.xiaonuoboard.app.data.BitmapIO
import dev.xiaonuoboard.app.data.GalleryExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onContinue: (Artwork) -> Unit,
    onFreshCopy: (String) -> Unit,
) {
    val works by container.artworks.works.collectAsState()
    var pageIndex by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton("← 返回") { onBack() }
            Text("🖼️ 我的作品画廊（${works.size} 幅）", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        val pageCount = maxOf(1, (works.size + 3) / 4)
        val current = pageIndex.coerceIn(0, pageCount - 1)
        val slice = works.drop(current * 4).take(4)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (r in 0 until 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in 0 until 2) {
                    val art = slice.getOrNull(r * 2 + c)
                    if (art != null) {
                        ArtworkCard(container, art, Modifier.weight(1f).fillMaxSize(), { onContinue(art) }, { onFreshCopy(art.pageId) })
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
        Pager(current, pageCount, works.size) { pageIndex = it }
    }
}

/** Gallery card: thumbnail, title #copy, status, 继续涂 / 新开重涂 / 导出PNG / 删除. */
@Composable
fun ArtworkCard(
    container: AppContainer,
    art: Artwork,
    modifier: Modifier,
    onOpen: () -> Unit,
    onFresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember(art.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(art.id) { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Column(
        modifier.background(Eink.White, Eink.Shape).border(Eink.Border, Eink.Ink, Eink.Shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().einkClick(onClick = onOpen)) {
            FileThumb(container.artworks.thumbFile(art.id), art.updatedAt, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).background(Eink.Ink, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(
                    if (art.completed) "★ 已完成" else "涂色中 ${(art.progress * 100).toInt()}%",
                    color = Eink.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Text("${art.pageTitle} #${art.copyNo}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            message ?: (fmt.format(Date(art.updatedAt)) + if (art.exported) " · 已导出相册" else " · 自动保存"),
            fontSize = 13.sp, color = Eink.Muted, maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EinkButton("继续涂", modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) { onOpen() }
            EinkButton("新开重涂", modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) { onFresh() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EinkButton("导出PNG", modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) {
                scope.launch {
                    message = "导出中…"
                    message = withContext(Dispatchers.IO) {
                        var bmp: Bitmap? = null
                        try {
                            bmp = renderArtwork(container, art) ?: error("作品文件缺失")
                            val where = GalleryExporter.exportPng(context, bmp, "${art.pageTitle}_${art.copyNo}_${art.updatedAt}")
                            container.artworks.markExported(art.id)
                            "已导出：$where"
                        } catch (e: Exception) {
                            "导出失败：${e.message}"
                        } finally {
                            bmp?.recycle()
                        }
                    }
                }
            }
            EinkButton(if (confirmDelete) "确认删除?" else "删除", selected = confirmDelete, modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) {
                if (confirmDelete) {
                    container.artworks.delete(art.id)
                    ThumbCache.invalidate("file:${container.artworks.thumbFile(art.id).absolutePath}")
                } else {
                    confirmDelete = true
                }
            }
        }
    }
}

/** Full-resolution composite (white + paint + line art) of a saved artwork for export. */
fun renderArtwork(container: AppContainer, art: Artwork): Bitmap? {
    val page = container.pages.findPage(art.pageId) ?: return null
    val raw = BitmapIO.loadPageBitmap(container.appContext, page, ref = false) ?: return null
    val line = BitmapIO.fitToCanvas(raw)
    if (line !== raw) raw.recycle()
    val w = line.width
    val h = line.height
    val ink = BitmapIO.inkMask(line)
    line.recycle()
    val overlay = BitmapIO.inkOverlay(ink, w, h)
    val paint = container.artworks.loadPaint(art.id) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    return try {
        BitmapIO.composite(paint, overlay)
    } finally {
        paint.recycle()
        overlay.recycle()
    }
}
