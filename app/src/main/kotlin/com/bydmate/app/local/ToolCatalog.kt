package com.bydmate.app.local

/** Explicit routes: unsupported vendor/plugins must never masquerade as completed functions. */
data class LocalTool(val id: String, val title: String, val subtitle: String, val route: String, val accent: Int)

val localTools = listOf(
    LocalTool("audio", "音频", "本地音频 · 输出设备", "native:audio", 0),
    LocalTool("automation", "自动", "场景联动", "automation", 1),
    LocalTool("split", "分屏", "多窗口", "tool_settings/SPLIT", 2),
    LocalTool("front", "前车提醒", "插件协议待补齐", "native:front", 1),
    LocalTool("traffic", "红绿灯领航", "本地版不提供实时信号源", "native:traffic", 3),
    LocalTool("sentry", "BYDMate 哨兵", "原厂哨兵开关 · 手动录像", "native:sentry", 1),
    LocalTool("mirror", "电子后视", "车载影像 · 驻车预览", "camera", 0),
    LocalTool("bluetooth", "米篓宝", "蓝牙设备 · 专用协议待确认", "native:bluetooth", 0),
    LocalTool("advanced", "高级投屏", "中控屏与仪表屏编排", "tool_settings/DISPLAY", 1),
    LocalTool("cast", "投屏", "仪表输出", "tool_settings/DISPLAY", 3),
    LocalTool("files", "文件快传", "本地导入 · 导出 · 系统分享", "native:files", 0),
    LocalTool("desktop", "桌面", "桌面卡片与媒体显示", "tool_settings/WIDGET", 0),
    LocalTool("status", "状态栏", "实时车辆信息", "native:status", 2),
    LocalTool("wheel", "方控", "方向盘控制 · 现有显示设置", "tool_settings/DISPLAY", 1),
    LocalTool("touch", "触控板", "仪表输入接口待补齐", "native:touch", 3),
    LocalTool("guard", "熄火守护", "12V 状态 · 原厂哨兵入口", "native:guard", 1),
)
