package com.bydmatecn.app.local

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DisplaySettings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PageBackground = Color(0xFF081321)
private val Panel = Color(0xFF101F31)
private val PanelRaised = Color(0xFF162A40)
private val MutedText = Color(0xFF8DA3BC)
private val Cyan = Color(0xFF19D3D6)
private val Orange = Color(0xFFFFA928)
private val Purple = Color(0xFFB788FF)
private val Blue = Color(0xFF4DA9FF)
private val toolAccents = listOf(Cyan, Orange, Purple, Blue)

private fun toolIcon(id: String): ImageVector = when (id) {
    "audio" -> Icons.AutoMirrored.Outlined.VolumeUp
    "recorder", "mirror" -> Icons.Outlined.Videocam
    "files" -> Icons.Outlined.Folder
    "diagnostics" -> Icons.Outlined.Info
    "automation" -> Icons.Outlined.AutoAwesome
    "cast" -> Icons.Outlined.DisplaySettings
    "desktop" -> Icons.Outlined.Dashboard
    else -> Icons.Outlined.Settings
}

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
    val audio = localTools.first { it.id == "audio" }
    val mirror = localTools.first { it.id == "mirror" }
    val remaining = localTools.filterNot { it.id == "audio" || it.id == "mirror" }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B1A2B), PageBackground))
        ).padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        val compact = maxWidth < 760.dp
        val columns = when {
            maxWidth >= 1180.dp -> 4
            maxWidth >= 820.dp -> 3
            maxWidth >= 520.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("车机工具", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("常用能力集中管理", color = MutedText, fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Text("BYDMateCN  ·  WIP", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FeaturedToolCard(audio, Cyan, Modifier.fillMaxWidth(), ::open)
                    FeaturedToolCard(mirror, Orange, Modifier.fillMaxWidth(), ::open)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeaturedToolCard(audio, Cyan, Modifier.weight(1.35f), ::open)
                    FeaturedToolCard(mirror, Orange, Modifier.weight(1f), ::open)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部功能", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text("${localTools.size} 项", color = MutedText, fontSize = 14.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(remaining, key = { it.id }) { tool ->
                    StandardToolCard(tool, toolAccents[tool.accent], ::open)
                }
            }
        }
    }
}

@Composable
private fun FeaturedToolCard(tool: LocalTool, accent: Color, modifier: Modifier, open: (LocalTool) -> Unit) {
    Card(
        onClick = { open(tool) },
        modifier = modifier.height(154.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .36f)),
        colors = CardDefaults.cardColors(containerColor = PanelRaised),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(accent.copy(alpha = .18f), Color.Transparent))
            ).padding(22.dp)
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(74.dp).background(accent.copy(alpha = .18f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(toolIcon(tool.id), contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                }
                Column(Modifier.weight(1f).padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(tool.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(tool.subtitle, color = MutedText, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (tool.id == "audio") "本地音频 · 车外播放 · 实时喊话" else "快速进入设置", color = accent, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "进入", tint = accent, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun StandardToolCard(tool: LocalTool, accent: Color, open: (LocalTool) -> Unit) {
    Card(
        onClick = { open(tool) },
        modifier = Modifier.fillMaxWidth().height(116.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF263D55)),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Row(Modifier.fillMaxSize().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(accent.copy(alpha = .15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(toolIcon(tool.id), contentDescription = null, tint = accent, modifier = Modifier.size(29.dp))
            }
            Column(Modifier.weight(1f).padding(start = 15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tool.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(tool.subtitle, color = MutedText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Color(0xFF647E99), modifier = Modifier.size(20.dp))
        }
    }
}
