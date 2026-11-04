package com.bydmatecn.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun LocalUiState.audioGalleryContent(content: @Composable () -> Unit) {
    controls.add(content)
}

/** Wide-screen local sound library: selected directories, playback actions and recognised files share one workspace. */
internal fun LocalToolsActivity.audioFileGallery() {
    refreshAudioGallery()
    ui.audioGalleryContent {
        AudioLibraryWorkspace(
            directories = recognizedAudioDirectories,
            files = recognizedAudioFiles,
            message = audioScanMessage,
            current = currentAudio,
            onManage = { openAudioDirectoryManager() },
            onRefresh = { refreshAudioGallery() },
            onPause = { localPlayer.togglePause() },
            onStop = { stopAudio(); status.text = "已停止" },
            onPlay = { file ->
                currentAudio = file
                playFirmwareAudio(FirmwareAudioRoute.EXTERIOR_VOICE, file)
            },
        )
    }
}

internal fun LocalToolsActivity.openAudioDirectoryManager() {
    if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        showAudioDirectoryPicker(File("/storage/emulated/0"))
    }
}

internal fun LocalToolsActivity.refreshAudioGallery() {
    lifecycleScope.launch {
        audioScanMessage = "正在扫描本地音频…"
        val result = withContext(Dispatchers.IO) {
            runCatching { audioLibrary.directories() to audioLibrary.files() }
        }
        result.onSuccess { (directories, files) ->
            recognizedAudioDirectories.clear()
            recognizedAudioDirectories.addAll(directories)
            recognizedAudioFiles.clear()
            recognizedAudioFiles.addAll(files)
            audioScanMessage = when {
                directories.isEmpty() -> "尚未添加音频目录，请先选择目录"
                files.isEmpty() -> "已选择的目录中没有识别到音频文件"
                else -> "已识别 ${files.size} 个音频文件，点击卡片即可车外试听"
            }
            status.text = audioScanMessage
        }.onFailure { error ->
            recognizedAudioDirectories.clear()
            recognizedAudioFiles.clear()
            audioScanMessage = "扫描失败：${error.message}"
            status.text = audioScanMessage
        }
    }
}

@Composable
private fun AudioLibraryWorkspace(
    directories: List<File>,
    files: List<File>,
    message: String,
    current: File?,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPlay: (File) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("本地音效", color = Color(0xFFF3F6F9), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("从车机存储目录读取音频。选择文件后将通过车外音频通道试听。", color = Color(0xFF8E9CAA), fontSize = 13.sp)
        DirectoryPanel(directories, files.size, onManage, onRefresh, onPause, onStop)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已识别音频", color = Color(0xFFE8EDF2), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("  ${files.size} 个", color = AudioAccent, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(message, color = Color(0xFF758493), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (files.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(116.dp).background(Color(0xFF182129), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(message, color = Color(0xFF8795A3), fontSize = 14.sp) }
        } else {
            AudioFileGrid(files, current, onPlay)
        }
    }
}

@Composable
private fun DirectoryPanel(
    directories: List<File>,
    count: Int,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1A232C), RoundedCornerShape(7.dp)).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("本地音频目录", color = Color(0xFFE6EBF0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                directories.joinToString("  ·  ") { it.path }.ifEmpty { "尚未选择目录" },
                color = Color(0xFF8D9AA7), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text("${directories.size} 个目录 · $count 个可用音频", color = Color(0xFF647484), fontSize = 11.sp)
        }
        CompactAction("管理目录", primary = true, onManage)
        CompactAction("刷新", onClick = onRefresh)
        CompactAction("暂停 / 继续", onClick = onPause)
        CompactAction("停止", onClick = onStop)
    }
}

@Composable
private fun CompactAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.background(if (primary) AudioAccent else Color(0xFF27323D), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Color(0xFF071522) else Color(0xFFC3CDD6), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AudioFileGrid(files: List<File>, current: File?, onPlay: (File) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 1_250.dp -> 7
            maxWidth >= 1_050.dp -> 6
            maxWidth >= 820.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            files.chunked(columns).forEach { rowFiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowFiles.forEach { file ->
                        AudioFileCard(file, current?.path == file.path, { onPlay(file) }, Modifier.weight(1f))
                    }
                    repeat(columns - rowFiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AudioFileCard(file: File, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.height(104.dp)
            .background(if (active) Color(0xFF203B52) else Color(0xFF1B252E), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(34.dp).background(if (active) AudioAccent.copy(alpha = 0.2f) else Color(0xFF26333E), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
            Icon(if (active) Icons.Outlined.PlayArrow else Icons.Outlined.AudioFile, contentDescription = "播放", tint = if (active) AudioAccent else Color(0xFF81909E), modifier = Modifier.size(20.dp))
        }
        Text(file.nameWithoutExtension, color = if (active) Color.White else Color(0xFFD4DBE1), fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) AudioAccent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}
