package com.bydmatecn.app.local

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

private val PageBackground = Color(0xFF081321)
private val Panel = Color(0xFF101F31)
private val MutedText = Color(0xFF8DA3BC)
private val Cyan = Color(0xFF19D3D6)
private val Orange = Color(0xFFFFA928)
private val Purple = Color(0xFFB788FF)
private val Blue = Color(0xFF4DA9FF)
private val toolAccents = listOf(Cyan, Orange, Purple, Blue)
private const val TOOL_ORDER_PREFS = "local-tool-order"
private const val TOOL_ORDER_KEY = "ordered_ids"

private fun toolIcon(id: String): ImageVector = when (id) {
    "audio" -> Icons.AutoMirrored.Outlined.VolumeUp
    "dashcam", "sentry", "mirror" -> Icons.Outlined.Videocam
    "files" -> Icons.Outlined.Folder
    "diagnostics" -> Icons.Outlined.Info
    "automation", "voice" -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.Info
}

@Composable
fun LocalCapabilitiesScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val orderPrefs = remember(context) {
        context.getSharedPreferences(TOOL_ORDER_PREFS, Context.MODE_PRIVATE)
    }
    var orderedTools by remember {
        mutableStateOf(restoreToolOrder(orderPrefs.getString(TOOL_ORDER_KEY, null)))
    }
    val gridState = rememberLazyGridState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }

    fun finishDrag(commit: Boolean) {
        val sourceId = draggedId
        if (commit && sourceId != null) {
            val targetId = gridState.dragTarget(sourceId, dragX, dragY)
            if (targetId != null && targetId != sourceId) {
                orderedTools = orderedTools.move(sourceId, targetId)
                orderPrefs.edit().putString(TOOL_ORDER_KEY, orderedTools.joinToString(",") { it.id }).apply()
            }
        }
        draggedId = null
        dragX = 0f
        dragY = 0f
    }

    fun open(tool: LocalTool) {
        when {
            tool.route.startsWith("camera:") -> context.startActivity(
                Intent(context, LocalCameraActivity::class.java)
                    .putExtra(LocalCameraActivity.EXTRA_MODE, tool.route.substringAfter(":")),
            )
            tool.route.startsWith("native:") -> context.startActivity(
                Intent(context, LocalToolsActivity::class.java).putExtra("tool", tool.id))
            else -> onNavigate(tool.route)
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B1A2B), PageBackground))
        ).padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
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
                Text("BYDMateCN  ·  DiLink 车机工具", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部功能", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text("${orderedTools.size} 项 · 长按卡片拖拽排序", color = MutedText, fontSize = 14.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(orderedTools, key = { it.id }) { tool ->
                    val held = draggedId == tool.id
                    StandardToolCard(
                        tool = tool,
                        accent = toolAccents[tool.accent],
                        open = ::open,
                        modifier = Modifier
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                if (held) {
                                    translationX = dragX
                                    translationY = dragY
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                    shadowElevation = 16.dp.toPx()
                                }
                            }
                            .pointerInput(tool.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = tool.id
                                        dragX = 0f
                                        dragY = 0f
                                    },
                                    onDragCancel = { finishDrag(commit = false) },
                                    onDragEnd = { finishDrag(commit = true) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragX += amount.x
                                        dragY += amount.y
                                    },
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardToolCard(
    tool: LocalTool,
    accent: Color,
    open: (LocalTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { open(tool) },
        modifier = modifier.fillMaxWidth().height(116.dp),
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


private fun restoreToolOrder(saved: String?): List<LocalTool> {
    val byId = localTools.associateBy { it.id }
    val restored = saved.orEmpty().split(',').mapNotNull(byId::get).distinctBy { it.id }
    return restored + localTools.filterNot { candidate -> restored.any { it.id == candidate.id } }
}

private fun List<LocalTool>.move(sourceId: String, targetId: String): List<LocalTool> {
    val from = indexOfFirst { it.id == sourceId }
    val to = indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun LazyGridState.dragTarget(sourceId: String, deltaX: Float, deltaY: Float): String? {
    val visible = layoutInfo.visibleItemsInfo
    val source = visible.firstOrNull { it.key == sourceId } ?: return null
    val centreX = source.offset.x + source.size.width / 2f + deltaX
    val centreY = source.offset.y + source.size.height / 2f + deltaY
    return visible.minByOrNull { item ->
        val itemX = item.offset.x + item.size.width / 2f
        val itemY = item.offset.y + item.size.height / 2f
        val dx = itemX - centreX
        val dy = itemY - centreY
        dx * dx + dy * dy
    }?.key as? String
}
