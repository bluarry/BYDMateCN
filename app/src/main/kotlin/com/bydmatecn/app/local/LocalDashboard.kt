package com.bydmatecn.app.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Actual available dp width, not physical inches, drives the car/tablet layout. */
internal fun dashboardColumns(widthDp: Int): Int = if (widthDp >= 600) 2 else 1

@Composable
internal fun LocalDashboard(ui: LocalUiState, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val sections = ui.sections.toList().ifEmpty { listOf(LocalSection("功能", 0)) }
    val active = selected.coerceIn(0, sections.lastIndex)
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(ui.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("BYDMateCN · 本地车机工具", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                ui.onBack?.let { back -> TextButton(onClick = back) { Text("返回", fontSize = 18.sp) } }
            }
            if (!wide && sections.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    sections.forEachIndexed { index, section ->
                        FilterChip(selected = active == index, onClick = { selected = index }, label = { Text(section.title) })
                    }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (wide && sections.size > 1) {
                    Column(Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        sections.forEachIndexed { index, section ->
                            Card(onClick = { selected = index }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = if (active == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Text(section.title, Modifier.padding(20.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ui.status?.let { status ->
                        Card(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                                Text("运行状态", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                Text(status.text, fontSize = 16.sp)
                            }
                        }
                    }
                    Card(modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
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
private fun SectionControls(ui: LocalUiState, sections: List<LocalSection>, active: Int, columns: Int) {
    val start = sections[active].start
    val end = sections.getOrNull(active + 1)?.start ?: ui.controls.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(sections[active].title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        var index = start
        while (index < end) {
            val first = index
            if (columns == 2 && pairedActions(ui, first, end)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) { ui.controls[first]() }
                    Column(Modifier.weight(1f)) { ui.controls[first + 1]() }
                }
                index += 2
            } else { ui.controls[first](); index++ }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

private fun pairedActions(ui: LocalUiState, index: Int, end: Int): Boolean =
    index + 1 < end && index in ui.actionIndices && index + 1 in ui.actionIndices
