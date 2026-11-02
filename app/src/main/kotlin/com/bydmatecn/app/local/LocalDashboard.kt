package com.bydmatecn.app.local

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
    BoxWithConstraints(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF0B1A2B), Color(0xFF081321))))
    ) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolHeader(ui)
            if (!wide && sections.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    sections.forEachIndexed { index, section ->
                        FilterChip(
                            selected = active == index,
                            onClick = { selected = index },
                            label = { Text(section.title, fontSize = 15.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF14263A),
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = active == index,
                                borderColor = Color(0xFF29425C),
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide && sections.size > 1) {
                    SectionRail(
                        sections = sections,
                        active = active,
                        onSelect = { selected = it },
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.status?.let { StatusBanner(it) }
                    Card(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
                    ) {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            key(active) { SectionControls(ui, sections, active) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolHeader(ui: LocalUiState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ui.onBack?.let { back ->
            IconButton(
                onClick = back,
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White) }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ui.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(ui.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionRail(
    sections: List<LocalSection>,
    active: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162032)),
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 12.dp, horizontal = 8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    "功能分类",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                sections.forEachIndexed { index, section ->
                    val selected = index == active
                    val foreground = if (selected) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                            .background(
                                if (selected) Color(0xFF4ADE80).copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).background(foreground, RoundedCornerShape(4.dp))
                        )
                        Text(
                            section.title,
                            Modifier.padding(start = 12.dp),
                            color = foreground,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A3A52))
            Text(
                "本地车机工具",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusBanner(status: LocalText) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF285267)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF123042)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp, max = 116.dp).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp).verticalScroll(rememberScrollState())) {
                Text("运行状态", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(status.text, fontSize = 15.sp, color = Color(0xFFD8E4EF), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SectionControls(ui: LocalUiState, sections: List<LocalSection>, active: Int) {
    val start = sections[active].start
    val end = sections.getOrNull(active + 1)?.start ?: ui.controls.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(sections[active].title, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
        for (index in start until end) {
            ui.controls[index]()
            if (index < end - 1) {
                androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A3A52).copy(alpha = 0.55f), thickness = 1.dp)
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}
