package com.cleo.cleos.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

private data class DiaryCard(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val excerpt: String,
    val cover: File?,
    val imageCount: Int,
)

private sealed interface DiaryRow {
    data class Month(val month: YearMonth) : DiaryRow
    data class Entry(val card: DiaryCard) : DiaryRow
}

@Composable
fun DiaryTab(bottomInset: Dp, onOpenEntry: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val rows by remember {
        c.db.diary().observeAll()
            .map { entries -> toRows(entries) { c.images.file(it) } }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(null)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val count = rows?.count { it is DiaryRow.Entry } ?: 0

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "日记",
                subtitle = if (count > 0) "$count 篇" else null,
                backdrop = page,
                trailing = { GlassIconButton(Icons.Rounded.Settings, "设置", onOpenSettings, page) },
            )
            GlassIconButton(
                Icons.Rounded.EditNote,
                "写日记",
                { onOpenEntry(0L) },
                page,
                style = palette.accentSurface,
                tint = Color.White,
                size = 58.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = bottomInset + 4.dp),
            )
        },
    ) {
        val list = rows
        if (list != null && list.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有日记", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(6.dp))
                Text("点右下角的笔，写第一篇", color = palette.contentSecondary, fontSize = 14.sp)
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight, bottom = bottomInset - 10.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 6.dp, bottom = bottomInset + 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list.orEmpty(), key = {
                when (it) {
                    is DiaryRow.Month -> "m${it.month}"
                    is DiaryRow.Entry -> it.card.id
                }
            }) { row ->
                when (row) {
                    is DiaryRow.Month -> MonthHeader(row.month)
                    is DiaryRow.Entry -> DiaryCardView(row.card) { onOpenEntry(row.card.id) }
                }
            }
        }
    }
}

private fun toRows(entries: List<DiaryEntryEntity>, file: (String) -> File): List<DiaryRow> {
    val rows = ArrayList<DiaryRow>(entries.size + 12)
    var month: YearMonth? = null
    for (e in entries) {
        val date = LocalDate.ofEpochDay(e.day)
        val ym = YearMonth.from(date)
        if (ym != month) {
            rows += DiaryRow.Month(ym)
            month = ym
        }
        val blocks = DiaryBlocks.decode(e.blocks)
        val images = DiaryBlocks.images(blocks)
        rows += DiaryRow.Entry(
            DiaryCard(
                id = e.id,
                date = date,
                title = e.title,
                excerpt = DiaryBlocks.plainText(blocks).replace(Regex("\\s+"), " ").take(160),
                cover = images.firstOrNull()?.let { file(it.file) },
                imageCount = images.size,
            ),
        )
    }
    return rows
}

@Composable
private fun MonthHeader(month: YearMonth) {
    val palette = LocalGlassPalette.current
    Box(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)) {
        GlassSurface(style = palette.bar, shape = GlassShape.Capsule, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) {
            Text(Dates.yearMonth(month.atDay(1)), color = palette.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DiaryCardView(card: DiaryCard, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onClick),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${card.date.dayOfMonth}", color = palette.accentContent, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text(Dates.weekday(card.date).replace("星期", "周"), color = palette.contentSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (card.title.isNotBlank()) {
                    Text(card.title, color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(3.dp))
                }
                when {
                    card.excerpt.isNotBlank() -> Text(
                        card.excerpt,
                        color = palette.content.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = if (card.title.isBlank()) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.title.isBlank() -> Text("${card.imageCount} 张图片", color = palette.contentSecondary, fontSize = 14.sp)
                }
            }
            card.cover?.let { cover ->
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }
    }
}
