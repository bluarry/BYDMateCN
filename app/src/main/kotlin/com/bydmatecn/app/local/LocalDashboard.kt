package com.bydmatecn.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Actual available dp width, not physical inches, drives the car/tablet layout. */
internal fun dashboardColumns(widthDp: Int): Int = if (widthDp >= 620) 2 else 1

@Composable
internal fun LocalDashboard(ui: LocalUiState, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val sections = ui.sections.toList().ifEmpty { listOf(LocalSection("功能", 0)) }
    val active = selected.coerceIn(0, sections.lastIndex)
    if (ui.visualStyle == LocalVisualStyle.EXTERIOR_AUDIO) {
        ExteriorAudioDashboard(ui, sections, active, { selected = it }, modifier)
        return
    }
    BoxWithConstraints(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF0B1A2B), Color(0xFF081321))))
    ) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolHeader(ui)
            if ((!wide || ui.topTabs) && sections.size > 1) TopSectionTabs(sections, active, false, { selected = it })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide && !ui.topTabs && sections.size > 1) {
                    SectionRail(sections, active, { selected = it }, Modifier.width(260.dp).fillMaxHeight())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.status?.let { StatusBanner(it) }
                    Card(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
                    ) {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            key(active) { SectionControls(ui, sections, active, flat = false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExteriorAudioDashboard(
    ui: LocalUiState,
    sections: List<LocalSection>,
    active: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.background(Color(0xFF303132))) {
        val wide = maxWidth >= 920.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 34.dp else 18.dp, vertical = 14.dp)) {
            ToolHeader(ui, compact = true)
            Spacer(Modifier.size(12.dp))
            TopSectionTabs(sections, active, true, onSelect)
            Spacer(Modifier.size(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                key(active) { SectionControls(ui, sections, active, flat = true) }
            }
        }
    }
}

@Composable
private fun ToolHeader(ui: LocalUiState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ui.onBack?.let { back ->
            IconButton(onClick = back, modifier = Modifier.size(if (compact) 38.dp else 40.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = if (compact) Color(0xFFD9DDE2) else Color.White,
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ui.title, fontSize = if (compact) 21.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF4F7FA))
            if (!compact) Text(ui.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TopSectionTabs(
    sections: List<LocalSection>,
    active: Int,
    flat: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (flat) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEachIndexed { index, section ->
            val selected = active == index
            Box(
                Modifier
                    .background(
                        if (selected) AudioAccent else if (flat) Color(0xFF444648) else Color(0xFF14263A),
                        RoundedCornerShape(if (flat) 3.dp else 14.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = if (flat) 30.dp else 16.dp, vertical = if (flat) 12.dp else 9.dp),
            ) {
                Text(
                    section.title,
                    fontSize = if (flat) 15.sp else 15.sp,
                    color = if (selected) Color.White else Color(0xFFD0D3D6),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SectionRail(sections: List<LocalSection>, active: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF162032))) {
        Column(Modifier.fillMaxSize().padding(vertical = 12.dp, horizontal = 8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("功能分类", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                sections.forEachIndexed { index, section ->
                    val selected = index == active
                    val foreground = if (selected) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                            .background(if (selected) Color(0xFF4ADE80).copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelect(index) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(foreground, RoundedCornerShape(4.dp)))
                        Text(section.title, Modifier.padding(start = 12.dp), color = foreground, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A3A52))
            Text("本地车机工具", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun StatusBanner(status: LocalText) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF123042))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("运行状态", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(status.text, fontSize = 15.sp, color = Color(0xFFD8E4EF), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CompactStatus(status: LocalText) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF18222C), RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(AudioAccent, RoundedCornerShape(3.dp)))
        Text(status.text, color = Color(0xFF9DABBA), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SectionControls(ui: LocalUiState, sections: List<LocalSection>, active: Int, flat: Boolean) {
    val start = sections[active].start
    val end = sections.getOrNull(active + 1)?.start ?: ui.controls.size
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (flat) 10.dp else 12.dp),
    ) {
        if (!flat) Text(sections[active].title, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
        for (index in start until end) {
            ui.controls[index]()
            if (!flat && index < end - 1) androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A3A52).copy(alpha = 0.55f))
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

internal val AudioAccent = Color(0xFF4E9DF8)
