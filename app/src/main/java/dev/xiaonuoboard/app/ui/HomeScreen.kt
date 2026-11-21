package dev.xiaonuoboard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaonuoboard.app.AppContainer
import dev.xiaonuoboard.app.data.Artwork
import dev.xiaonuoboard.app.data.ColoringPage
import dev.xiaonuoboard.app.data.HomeTabs
import dev.xiaonuoboard.app.update.UpdateUiState

private const val PAGE_SIZE = 4 // 2×2 paged grid: no scrolling → no E-Ink smearing

@Composable
fun HomeScreen(
    container: AppContainer,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenArtwork: (Artwork) -> Unit,
    onFreshCopy: (String) -> Unit,
    onOpenAi: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val works by container.artworks.works.collectAsState()
    val updateState by container.updater.state.collectAsState()
    val hasUpdate = updateState is UpdateUiState.Available || updateState is UpdateUiState.Ready
    val tab = HomeTabs.ALL[selectedTab.coerceIn(0, HomeTabs.ALL.lastIndex)]
    var pageIndex by remember(tab.id) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("🎨 小诺画板", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("选一张喜欢的画开始涂色吧", fontSize = 16.sp, color = Eink.Muted)
            }
            EinkButton("✨ AI 画坊 🔒") { onOpenAi() }
            EinkButton(if (hasUpdate) "⬆️ 新版 🔒" else "⚙️ 🔒", selected = hasUpdate) { onOpenSettings() }
        }
        // Tabs (two rows so every tab stays ≥ 56dp tall and readable)
        val rows = listOf(HomeTabs.ALL.take(4), HomeTabs.ALL.drop(4))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    val idx = HomeTabs.ALL.indexOf(t)
                    EinkButton("${t.emoji} ${t.title}", selected = idx == selectedTab, modifier = Modifier.weight(1f), fontSize = 17) {
                        onSelectTab(idx)
                    }
                }
            }
        }

        when (tab.id) {
            HomeTabs.WORKS -> {
                WorksGrid(container, works, pageIndex, { pageIndex = it }, onOpenArtwork, onFreshCopy, Modifier.weight(1f))
            }
            else -> {
                val pages = remember(tab.id, works.size) { container.pages.pagesFor(tab.id) }
                val items: List<ColoringPage?> = if (tab.id == HomeTabs.AI) listOf<ColoringPage?>(null) + pages else pages
                val pageCount = maxOf(1, (items.size + PAGE_SIZE - 1) / PAGE_SIZE)
                val current = pageIndex.coerceIn(0, pageCount - 1)
                val slice = items.drop(current * PAGE_SIZE).take(PAGE_SIZE)
                Box(Modifier.weight(1f)) {
                    TwoByTwo(slice) { item, mod ->
                        if (item == null) {
                            NewAiCard(mod, onOpenAi)
                        } else {
                            PageCard(item, done = works.any { it.pageId == item.id }, modifier = mod) { onOpenPage(item.id) }
                        }
                    }
                }
                Pager(current, pageCount, items.count { it != null }) { pageIndex = it }
            }
        }
    }
}

@Composable
private fun <T> TwoByTwo(items: List<T>, cell: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (r in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    if (i < items.size) cell(items[i], Modifier.weight(1f).fillMaxSize()) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun Pager(current: Int, pageCount: Int, total: Int, onPage: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EinkButton("◀ 上一页", enabled = current > 0, modifier = Modifier.weight(1f)) { onPage(current - 1) }
        Text("第 ${current + 1} / $pageCount 页 · 共 $total 张", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        EinkButton("下一页 ▶", enabled = current < pageCount - 1, modifier = Modifier.weight(1f)) { onPage(current + 1) }
    }
}

@Composable
private fun PageCard(page: ColoringPage, done: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(Eink.White, Eink.Shape)
            .border(Eink.Border, Eink.Ink, Eink.Shape)
            .einkClick(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            PageThumb(page, ref = false, modifier = Modifier.fillMaxSize().aspectRatio(0.75f, matchHeightConstraintsFirst = true))
            if (page.refPath != null) {
                // Small colored reference badge (bottom-right).
                PageThumb(
                    page, ref = true,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(width = 54.dp, height = 72.dp)
                        .border(Eink.Border, Eink.Ink, RoundedCornerShape(6.dp)),
                )
            }
            if (done) {
                Box(
                    Modifier.align(Alignment.TopStart).background(Eink.Ink, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("★ 已涂", color = Eink.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
        Text(page.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(difficultyLabel(page.difficulty), fontSize = 14.sp, color = Eink.Muted)
    }
}

fun difficultyLabel(d: String) = when (d) {
    "easy" -> "幼儿简单"
    "standard" -> "进阶场景"
    "ai" -> "AI 生成"
    "imported" -> "本地导入"
    else -> d
}

@Composable
private fun NewAiCard(modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(Eink.Paper, Eink.Shape)
            .border(Eink.Border, Eink.Ink, Eink.Shape)
            .einkClick(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✨➕", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text("生成新涂色页", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("进入魔法画坊（家长锁）", fontSize = 14.sp, color = Eink.Muted)
    }
}

@Composable
private fun WorksGrid(
    container: AppContainer,
    works: List<Artwork>,
    pageIndex: Int,
    onPage: (Int) -> Unit,
    onOpen: (Artwork) -> Unit,
    onFresh: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (works.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有作品，去选一张画开始涂色吧！", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            return@Column
        }
        val pageCount = maxOf(1, (works.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val current = pageIndex.coerceIn(0, pageCount - 1)
        Box(Modifier.weight(1f)) {
            TwoByTwo(works.drop(current * PAGE_SIZE).take(PAGE_SIZE)) { art, mod ->
                ArtworkCard(container, art, mod, onOpen = { onOpen(art) }, onFresh = { onFresh(art.pageId) })
            }
        }
        Pager(current, pageCount, works.size, onPage)
    }
}
