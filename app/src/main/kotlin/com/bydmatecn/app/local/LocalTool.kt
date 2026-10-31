package com.bydmatecn.app.local

/** Explicit routes: unsupported vendor/plugins must never masquerade as completed functions. */
data class LocalTool(val id: String, val title: String, val subtitle: String, val route: String, val accent: Int)

val localTools = listOf(
    LocalTool("audio", "音频", "喊话 · 锁车音效 · 录音 · 本地语音", "native:audio", 0),
    LocalTool("recorder", "驻车录像", "MP4 分段 · 事件保护 · 本地相册", "camera", 1),
    LocalTool("diagnostics", "固件与权限", "只读检测 · 适配报告导出", "native:diagnostics", 2),
    LocalTool("automation", "自动", "场景联动", "automation", 1),
    LocalTool("split", "分屏", "多窗口", "tool_settings/SPLIT", 2),
    LocalTool("mirror", "电子后视", "转向联动 · 显示位置", "blind_spot", 0),
    LocalTool("cast", "投屏", "仪表输出", "tool_settings/DISPLAY", 3),
    LocalTool("files", "本地媒体", "目录选择 · 扫描媒体 · 相册备份", "native:files", 0),
    LocalTool("desktop", "桌面", "桌面卡片与媒体显示", "tool_settings/WIDGET", 0),
)
