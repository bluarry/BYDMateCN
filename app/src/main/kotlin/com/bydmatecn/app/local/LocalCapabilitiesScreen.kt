package com.bydmatecn.app.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val toolAccents = listOf(Color(0xFF00D4D8), Color(0xFFFFB12B), Color(0xFFD77DF3), Color(0xFF00BBFF))
private val toolGlyphs = listOf("♫", "ϟ", "▦", "♢", "◉", "◉", "▣", "ᛒ", "▤", "▣", "↗", "▦", "▤", "✥", "☝", "▣")

@Composable
fun LocalCapabilitiesScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    fun open(tool: LocalTool) {
        when {
            tool.route == "camera" -> context.startActivity(Intent(context, LocalCameraActivity::class.java))
            tool.route.startsWith("native:") -> context.startActivity(
                Intent(context, LocalToolsActivity::class.java).putExtra("tool", tool.id))
            else -> onNavigate(tool.route)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101A2D)).padding(20.dp)) {
        val columns = when { maxWidth >= 1000.dp -> 4; maxWidth >= 650.dp -> 3; maxWidth >= 420.dp -> 2; else -> 1 }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(28.dp).background(toolAccents[0], RoundedCornerShape(3.dp)))
                Text("工具", Modifier.padding(start = 12.dp), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("本地车机版", color = Color(0xFF91A6C3), fontSize = 14.sp)
            }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(localTools, key = { it.id }) { tool ->
                    val accent = toolAccents[tool.accent]
                    val glyph = when (tool.id) { "recorder" -> "◉"; "diagnostics" -> "⚙"; else -> toolGlyphs[localTools.filterNot { it.id in setOf("recorder", "diagnostics") }.indexOf(tool)] }
                    Card(onClick = { open(tool) }, modifier = Modifier.fillMaxWidth().height(120.dp),
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF7398C5)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF203653))) {
                        Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(50.dp).background(accent.copy(alpha = .24f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Text(glyph, color = accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tool.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(tool.subtitle, color = Color(0xFFD1DCEF), fontSize = 12.sp)
                            }
                            Text("➜", color = accent, fontSize = 24.sp)
                        }
                    }
                }
            }
            Text("权限/设备接口未验证时明确提示；红绿灯实时数据和云端能力不纳入本地版。", color = Color(0xFF91A6C3), fontSize = 12.sp)
        }
    }
}
