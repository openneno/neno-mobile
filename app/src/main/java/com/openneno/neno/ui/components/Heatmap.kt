package com.openneno.neno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openneno.neno.ui.theme.NenoColors
import java.time.LocalDate

/** 每日记录热力图的数据: weeks 列, 每列 7 天 */
object HeatmapData {
    const val WEEKS = 12

    /** 与网页端 GreenMap 相同的排列: 每列是一周(周日在上), 最后一列包含今天 */
    fun grid(today: LocalDate = LocalDate.now(), weeks: Int = WEEKS): List<List<LocalDate>> {
        val weekDay = today.dayOfWeek.value % 7 // 周日 = 0
        var start = weeks * 7 - (7 - weekDay + 1)
        return List(weeks) {
            List(7) {
                val date = today.minusDays(start.toLong())
                start--
                date
            }
        }
    }

    fun level(count: Int) = when {
        count <= 0 -> 0
        count <= 2 -> 1
        count <= 5 -> 2
        else -> 3
    }

    fun color(c: NenoColors, count: Int): Color = when (level(count)) {
        0 -> c.surface3
        1 -> if (c.dark) c.primary.p900 else c.primary.p200
        2 -> if (c.dark) c.primary.p700 else c.primary.p400
        else -> if (c.dark) c.primary.p500 else c.primary.p600
    }
}

@Composable
fun Heatmap(counts: Map<String, Int>, colors: NenoColors, onDayClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        HeatmapData.grid(today).forEach { week ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { date ->
                    val key = date.toString()
                    val count = counts[key] ?: 0
                    val future = date.isAfter(today)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                            .background(if (future) Color.Transparent else HeatmapData.color(colors, count))
                            .then(if (date == today) Modifier.border(1.dp, colors.accent, RoundedCornerShape(3.dp)) else Modifier)
                            .then(if (!future) Modifier.clickable { onDayClick(key) } else Modifier)
                    )
                }
            }
        }
    }
}
