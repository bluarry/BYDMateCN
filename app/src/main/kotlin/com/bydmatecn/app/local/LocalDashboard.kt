package com.bydmatecn.app.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (wide && sections.size > 1) {
                    SectionRail(sections, active, onSelect = { selected = it })
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.status?.let { StatusBanner(it) }
                    Card(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFF263E57)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101F31)),
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                            val columns = dashboardColumns(maxWidth.value.toInt())
                            key(active) { SectionControls(ui, sections, active, columns) }
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
                modifier = Modifier.size(52.dp).background(Color(0xFF15283D), RoundedCornerShape(16.dp)),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White) }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ui.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(ui.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Text("BYDMateCN", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionRail(sections: List<LocalSection>, active: Int, onSelect: (Int) -> Unit) {
    Card(
        Modifier.width(224.dp).fillMaxHeight(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1D2D)),
        border = BorderStroke(1.dp, Color(0xFF20374E)),
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("功能分类", color = Color(0xFF7890AA), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            sections.forEachIndexed { index, section ->
                val chosen = active == index
                Card(
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (chosen) Color(0xFF1C4A5D) else Color.Transparent),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(if (chosen) MaterialTheme.colorScheme.primary else Color(0xFF405B73), RoundedCornerShape(4.dp))
                        )
                        Text(section.title, Modifier.weight(1f).padding(start = 12.dp), color = if (chosen) Color.White else Color(0xFFB5C4D5), fontSize = 16.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                        if (chosen) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
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
private fun SectionControls(ui: LocalUiState, sections: List<LocalSection>, active: Int, columns: Int) {
    val start = sections[active].start
    val end = sections.getOrNull(active + 1)?.start ?: ui.controls.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text(sections[active].title, fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Box(Modifier.width(44.dp).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        var index = start
        while (index < end) {
            val first = index
            if (columns == 2 && pairedActions(ui, first, end)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) { ui.controls[first]() }
                    Column(Modifier.weight(1f)) { ui.controls[first + 1]() }
                }
                index += 2
            } else {
                ui.controls[first](); index++
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

private fun pairedActions(ui: LocalUiState, index: Int, end: Int): Boolean =
    index + 1 < end && index in ui.actionIndices && index + 1 in ui.actionIndices
