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
import dev.xiaonuoboard.app.i18n.AppI18n
import dev.xiaonuoboard.app.i18n.AppStrings
import dev.xiaonuoboard.app.i18n.LocalAppStrings
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
    val s = LocalAppStrings.current
    val works by container.artworks.works.collectAsState()
    val updateState by container.updater.state.collectAsState()
    val hasUpdate = updateState is UpdateUiState.Available || updateState is UpdateUiState.Ready
    val tab = HomeTabs.ALL[selectedTab.coerceIn(0, HomeTabs.ALL.lastIndex)]
    var pageIndex by remember(tab.id) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header (custom vector icons only — zero emoji)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KidIcons.PaletteLogo(Eink.Ink, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(s.appName, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.homeSubtitle, fontSize = 16.sp, color = Eink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            EinkIconTextButton(
                s.homeAiWorkshop,
                leading = { tint -> KidIcons.MagicWand(tint, Modifier.size(28.dp)) },
                trailing = { tint -> KidIcons.ParentLock(tint) },
            ) { onOpenAi() }
            EinkIconTextButton(
                if (hasUpdate) s.homeUpdateBadge else "",
                selected = hasUpdate,
                leading = { tint ->
                    if (hasUpdate) KidIcons.UpdateArrow(tint, Modifier.size(28.dp)) else KidIcons.Gear(tint, Modifier.size(30.dp))
                },
                trailing = { tint -> KidIcons.ParentLock(tint) },
            ) { onOpenSettings() }
        }
        // Tabs (two rows so every tab stays ≥ 56dp tall and readable)
        val rows = listOf(HomeTabs.ALL.take(4), HomeTabs.ALL.drop(4))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    val idx = HomeTabs.ALL.indexOf(t)
                    EinkIconTextButton(
                        t.title(s),
                        selected = idx == selectedTab,
                        modifier = Modifier.weight(1f),
                        fontSize = 17,
                        leading = { tint -> KidIcons.TabGlyph(t.icon, tint, Modifier.size(28.dp)) },
                    ) { onSelectTab(idx) }
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
    val s = LocalAppStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EinkIconTextButton(
            s.pagerPrev,
            enabled = current > 0,
            modifier = Modifier.weight(1f),
            leading = { tint -> KidIcons.Chevron(pointsLeft = true, tint = tint) },
        ) { onPage(current - 1) }
        Text(s.pagerInfo(current + 1, pageCount, total), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        EinkIconTextButton(
            s.pagerNext,
            enabled = current < pageCount - 1,
            modifier = Modifier.weight(1f),
            trailing = { tint -> KidIcons.Chevron(pointsLeft = false, tint = tint) },
        ) { onPage(current + 1) }
    }
}

@Composable
private fun PageCard(page: ColoringPage, done: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = LocalAppStrings.current
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
                StarBadge(s.badgeColored, Modifier.align(Alignment.TopStart))
            }
        }
        Text(page.localizedTitle(s.language), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(difficultyLabel(page.difficulty, s), fontSize = 14.sp, color = Eink.Muted)
    }
}

/** Inverted ink badge with a vector star — replaces the "★" glyph. */
@Composable
fun StarBadge(text: String, modifier: Modifier = Modifier, fontSize: Int = 14) {
    Row(
        modifier.background(Eink.Ink, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KidIcons.Star(Eink.White, Modifier.size((fontSize + 2).dp))
        Text(text, color = Eink.White, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold)
    }
}

fun difficultyLabel(d: String, strings: AppStrings = AppI18n.strings()) = strings.difficultyLabel(d)

@Composable
private fun NewAiCard(modifier: Modifier, onClick: () -> Unit) {
    val s = LocalAppStrings.current
    Column(
        modifier
            .background(Eink.Paper, Eink.Shape)
            .border(Eink.Border, Eink.Ink, Eink.Shape)
            .einkClick(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KidIcons.NewPage(Eink.Ink, Modifier.size(72.dp))
        Spacer(Modifier.height(8.dp))
        Text(s.homeNewAiTitle, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(s.homeNewAiSubtitle, fontSize = 14.sp, color = Eink.Muted)
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
    val s = LocalAppStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (works.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    KidIcons.GalleryFrame(Eink.Ink, Modifier.size(96.dp))
                    KidIcons.Crayon(Eink.Ink, Modifier.size(64.dp))
                }
                Text(s.homeNoWorks, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
