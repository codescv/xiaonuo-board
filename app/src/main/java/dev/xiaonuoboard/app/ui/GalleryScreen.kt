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
import androidx.compose.foundation.layout.size
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
import dev.xiaonuoboard.app.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun GalleryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onContinue: (Artwork) -> Unit,
    onFreshCopy: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    val works by container.artworks.works.collectAsState()
    var pageIndex by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkIconTextButton(s.back, leading = { tint -> KidIcons.BackArrow(tint, Modifier.size(28.dp)) }) { onBack() }
            KidIcons.GalleryFrame(Eink.Ink, Modifier.size(32.dp))
            Text(s.galleryTitle(works.size), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
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

/** Gallery card: thumbnail, title #copy, status, continue / fresh copy / export PNG / delete. */
@Composable
fun ArtworkCard(
    container: AppContainer,
    art: Artwork,
    modifier: Modifier,
    onOpen: () -> Unit,
    onFresh: () -> Unit,
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember(art.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(art.id) { mutableStateOf(false) }
    val fmt = remember(s.language) { SimpleDateFormat(s.galleryDatePattern, s.language.toLocale()) }
    // Built-in page titles localize dynamically; user-saved AI/imported artwork titles remain as created.
    val displayTitle = remember(art.pageId, art.pageTitle, s.language) {
        container.pages.findBuiltinPage(art.pageId)?.localizedTitle(s.language) ?: art.pageTitle
    }

    Column(
        modifier.background(Eink.White, Eink.Shape).border(Eink.Border, Eink.Ink, Eink.Shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().einkClick(onClick = onOpen)) {
            FileThumb(container.artworks.thumbFile(art.id), art.updatedAt, Modifier.fillMaxSize())
            if (art.completed) {
                StarBadge(s.badgeCompleted, Modifier.align(Alignment.TopStart), fontSize = 13)
            } else {
                Box(Modifier.align(Alignment.TopStart).background(Eink.Ink, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(s.galleryInProgress((art.progress * 100).toInt()), color = Eink.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Text("$displayTitle #${art.copyNo}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            message ?: (fmt.format(Date(art.updatedAt)) + if (art.exported) s.galleryStatusExported else s.galleryStatusAutosaved),
            fontSize = 13.sp, color = Eink.Muted, maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EinkButton(s.galleryContinue, modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) { onOpen() }
            EinkButton(s.galleryFreshCopy, modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) { onFresh() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EinkButton(s.galleryExportPng, modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) {
                val currentStrings = s
                scope.launch {
                    message = currentStrings.galleryExporting
                    message = withContext(Dispatchers.IO) {
                        var bmp: Bitmap? = null
                        try {
                            bmp = renderArtwork(container, art) ?: error(currentStrings.galleryMissingFile)
                            val where = GalleryExporter.exportPng(context, bmp, "${displayTitle}_${art.copyNo}_${art.updatedAt}", currentStrings)
                            container.artworks.markExported(art.id)
                            currentStrings.galleryExported(where)
                        } catch (e: Exception) {
                            currentStrings.exportFailed(e.message)
                        } finally {
                            bmp?.recycle()
                        }
                    }
                }
            }
            EinkButton(if (confirmDelete) s.galleryConfirmDelete else s.delete, selected = confirmDelete, modifier = Modifier.weight(1f), fontSize = 14, minHeight = 48.dp) {
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
