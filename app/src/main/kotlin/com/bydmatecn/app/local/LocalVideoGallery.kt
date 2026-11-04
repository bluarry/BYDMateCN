package com.bydmatecn.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
internal fun VideoLibraryWorkspace(
    files: List<File>,
    selected: File?,
    protected: (File) -> Boolean,
    preview: @Composable () -> Unit,
    onPlay: (File) -> Unit,
    onExport: (File) -> Unit,
    onDelete: (File) -> Unit,
    onProtect: (File) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("已录制视频", color = Color(0xFFF2F4F6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("点击视频卡片即可在应用内直接预览。", color = Color(0xFF8B96A2), fontSize = 13.sp)
            }
            Box(
                Modifier.background(Color(0xFF4E9DF8), RoundedCornerShape(6.dp)).clickable(onClick = onRefresh)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("刷新", color = Color.White, modifier = Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (selected != null) {
            Box(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp))) { preview() }
        }
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF171E25), RoundedCornerShape(8.dp)).padding(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Videocam, null, tint = Color(0xFF64717E), modifier = Modifier.size(40.dp))
                Text("暂无已录视频", color = Color(0xFF9BA7B3), modifier = Modifier.padding(top = 10.dp))
            }
        } else {
            files.forEach { file ->
                VideoFileCard(file, file == selected, protected(file), onPlay, onExport, onDelete, onProtect)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun VideoFileCard(
    file: File,
    selected: Boolean,
    protected: Boolean,
    onPlay: (File) -> Unit,
    onExport: (File) -> Unit,
    onDelete: (File) -> Unit,
    onProtect: (File) -> Unit,
) {
    val background = if (selected) Color(0xFF203E5C) else Color(0xFF171E25)
    Row(
        Modifier.fillMaxWidth().background(background, RoundedCornerShape(8.dp)).clickable { onPlay(file) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(Color(0xFF29445F), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, "预览", tint = Color(0xFF76B7FF), modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(file.name, color = Color(0xFFE8EDF2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(file.lastModified()))
            Text("$date · ${formatBytes(file.length())}${if (protected) " · 已保护" else ""}", color = if (protected) Color(0xFFFFC766) else Color(0xFF8D9AA7), fontSize = 12.sp)
        }
        VideoIconAction(Icons.Outlined.Download, "导出") { onExport(file) }
        VideoIconAction(Icons.Outlined.Videocam, if (protected) "解除保护" else "保护") { onProtect(file) }
        VideoIconAction(Icons.Outlined.Delete, "删除", danger = true) { onDelete(file) }
    }
}

@Composable
private fun VideoIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    danger: Boolean = false,
    action: () -> Unit,
) {
    Box(Modifier.size(42.dp).clickable(onClick = action), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (danger) Color(0xFFFF7676) else Color(0xFF9CB7D1), modifier = Modifier.size(21.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
    else -> "${bytes / 1024} KiB"
}
