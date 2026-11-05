package com.bydmatecn.app.local

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val GallerySurface = Color(0xFF111923)
private val GalleryCard = Color(0xFF1A2634)
private val GallerySelected = Color(0xFF21486C)
private val GalleryAccent = Color(0xFF4E9DF8)
private val GalleryMuted = Color(0xFF91A0AE)

/** Car-DVR style library: large selected preview followed by a dense recording thumbnail grid. */
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
        GalleryHeader(files.size, onRefresh)
        if (selected != null) {
            SelectedVideoPanel(
                file = selected,
                protected = protected(selected),
                preview = preview,
                onExport = { onExport(selected) },
                onDelete = { onDelete(selected) },
                onProtect = { onProtect(selected) },
            )
        }
        if (files.isEmpty()) {
            EmptyVideoLibrary()
        } else {
            Text("录像列表", color = Color(0xFFE7EDF3), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 1000.dp -> 4
                    maxWidth >= 700.dp -> 3
                    else -> 2
                }
                val gap = 12.dp
                val cardWidth = (maxWidth - gap * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    files.chunked(columns).forEach { rowFiles ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            rowFiles.forEach { file ->
                                VideoThumbnailCard(
                                    file = file,
                                    selected = file == selected,
                                    protected = protected(file),
                                    onPlay = onPlay,
                                    modifier = Modifier.width(cardWidth),
                                )
                            }
                            repeat(columns - rowFiles.size) { Spacer(Modifier.width(cardWidth)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun GalleryHeader(count: Int, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("录像回放", color = Color(0xFFF2F4F6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("共 $count 段录像 · 点击缩略图开始预览", color = GalleryMuted, fontSize = 13.sp)
        }
        GalleryAction(Icons.Outlined.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun SelectedVideoPanel(
    file: File,
    protected: Boolean,
    preview: @Composable () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onProtect: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(GallerySurface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(videoMeta(file, protected), color = GalleryMuted, fontSize = 12.sp)
            }
            GalleryAction(Icons.Outlined.Download, "导出", onExport)
            Spacer(Modifier.width(8.dp))
            GalleryAction(Icons.Outlined.Shield, if (protected) "解除保护" else "保护", onProtect)
            Spacer(Modifier.width(8.dp))
            GalleryAction(Icons.Outlined.Delete, "删除", onDelete, danger = true)
        }
    }
}

@Composable
private fun VideoThumbnailCard(
    file: File,
    selected: Boolean,
    protected: Boolean,
    onPlay: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(if (selected) GallerySelected else GalleryCard, RoundedCornerShape(10.dp))
            .clickable { onPlay(file) }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B1016)),
            contentAlignment = Alignment.Center,
        ) {
            VideoThumbnail(file)
            Box(Modifier.size(36.dp).background(Color.Black.copy(alpha = .52f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(25.dp))
            }
            if (protected) {
                Icon(
                    Icons.Outlined.Shield,
                    "已保护",
                    tint = Color(0xFFFFC766),
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(19.dp),
                )
            }
        }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(file.lastModified())),
            color = Color(0xFFF0F3F6),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(formatBytes(file.length()), color = GalleryMuted, fontSize = 11.sp)
    }
}

@Composable
private fun VideoThumbnail(file: File) {
    var frame by remember(file.absolutePath, file.lastModified()) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        frame = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }
    val thumbnail = frame
    if (thumbnail != null) {
        androidx.compose.foundation.Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    } else {
        Icon(Icons.Outlined.Videocam, null, tint = Color(0xFF63778B), modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun EmptyVideoLibrary() {
    Column(
        Modifier.fillMaxWidth().background(GalleryCard, RoundedCornerShape(10.dp)).padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Videocam, null, tint = Color(0xFF64717E), modifier = Modifier.size(42.dp))
        Text("暂无已录视频", color = Color(0xFF9BA7B3), modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun GalleryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    action: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        Modifier.background(if (danger) Color(0xFF542B31) else Color(0xFF26394C), RoundedCornerShape(7.dp))
            .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (danger) Color(0xFFFF8585) else GalleryAccent, modifier = Modifier.size(18.dp))
        Text(label, color = if (danger) Color(0xFFFFB0B0) else Color(0xFFDDE8F2), fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

private fun videoMeta(file: File, protected: Boolean): String =
    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(file.lastModified()))} · " +
        "${formatBytes(file.length())}${if (protected) " · 已保护" else ""}"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
    else -> "${bytes / 1024} KiB"
}
