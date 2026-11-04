package com.bydmatecn.app.local

/** Explicit routes: unsupported vendor/plugins must never masquerade as completed functions. */
data class LocalTool(val id: String, val title: String, val subtitle: String, val route: String, val accent: Int)

val localTools = listOf(
    LocalTool("audio", "音频", "喊话 · 锁车音效 · 录音 · 本地语音", "native:audio", 0),
    LocalTool("mirror", "电子后视", "转向联动 · 显示位置", "blind_spot", 1),
    LocalTool("automation", "自动化", "触发条件 · 场景联动 · 执行记录", "automation", 2),
    LocalTool("recorder", "行车记录仪与驻车哨兵", "自动启停 · 循环录像 · 事件保护", "camera", 3),
    LocalTool("files", "本地媒体", "目录选择 · 扫描媒体 · 相册备份", "native:files", 0),
    LocalTool("diagnostics", "固件与权限", "只读检测 · 适配报告导出", "native:diagnostics", 1),
)
