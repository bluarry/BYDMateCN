package com.bydmatecn.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
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

private val RecorderAccent = Color(0xFF4E9DF8)
private val RecorderPanel = Color(0xFF171E25)

@Composable
internal fun VideoStorageSummary(
    description: String,
    onBrowse: () -> Unit,
    onInput: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("录像存储", color = Color(0xFFF2F4F6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("新录制的视频直接写入所选目录；切换目录不会移动或删除旧文件。", color = Color(0xFF8B96A2), fontSize = 13.sp)
        Row(
            Modifier.fillMaxWidth().background(RecorderPanel, RoundedCornerShape(8.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, tint = RecorderAccent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("当前目录", color = Color(0xFFE8EDF2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color(0xFF9BA7B3), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecorderAction("浏览目录", onBrowse)
            RecorderAction("输入路径", onInput)
            RecorderAction("恢复默认", onReset, secondary = true)
        }
    }
}

@Composable
internal fun RecorderAngleSelector(
    selected: RecorderDirection,
    plans: Map<RecorderDirection, RecorderCapturePlan>,
    onSelect: (RecorderDirection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("360° 视角预览", color = Color(0xFFF2F4F6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("切换前、后、左、右 AVM 画面。各视角使用已标定的固件通道，不合成全景视频。", color = Color(0xFF8B96A2), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecorderDirection.entries.forEach { direction ->
                val active = direction == selected
                val plan = plans.getValue(direction)
                Column(
                    Modifier.weight(1f)
                        .background(if (active) RecorderAccent else RecorderPanel, RoundedCornerShape(8.dp))
                        .clickable { onSelect(direction) }
                        .padding(horizontal = 14.dp, vertical = 15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(direction.displayName, color = if (active) Color.White else Color(0xFFE2E7EB), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(4.dp))
                    Text("通道 ${plan.surfaceIndex}", color = if (active) Color.White.copy(alpha = .82f) else Color(0xFF8996A3), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun RecorderAction(label: String, action: () -> Unit, secondary: Boolean = false) {
    Box(
        Modifier.background(if (secondary) Color(0xFF353B41) else RecorderAccent, RoundedCornerShape(6.dp))
            .clickable(onClick = action).padding(horizontal = 20.dp, vertical = 11.dp),
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
