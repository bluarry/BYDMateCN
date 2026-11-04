package com.bydmatecn.app.local

/** Explicit routes: unsupported vendor/plugins must never masquerade as completed functions. */
data class LocalTool(val id: String, val title: String, val subtitle: String, val route: String, val accent: Int)

val localTools = listOf(
    LocalTool("audio", "车外语音", "本地音效 · 车外喊话 · 锁车音效 · 文字转语音", "native:audio", 0),
    LocalTool("mirror", "电子后视", "转向联动 · 显示位置", "blind_spot", 1),
    LocalTool("automation", "自动化", "触发条件 · 场景联动 · 执行记录", "automation", 2),
    LocalTool("dashcam", "行车记录仪", "行驶自动录像 · 循环分段 · 事件保护", "camera:dashcam", 3),
    LocalTool("sentry", "驻车哨兵", "锁车监控 · 开门与震动事件保护", "camera:sentry", 1),
    LocalTool("voice", "语音助手", "语音控制 · 对话 · 执行记录", "tool_settings/VOICE", 2),
    LocalTool("widget", "桌面组件", "悬浮组件 · 显示内容", "tool_settings/WIDGET", 0),
    LocalTool("display", "车辆显示", "仪表显示 · 电子后视相关显示", "tool_settings/DISPLAY", 1),
    LocalTool("split", "分屏", "分屏布局与行为", "tool_settings/SPLIT", 3),
    LocalTool("battery", "电池与充电", "续航计算 · 电价时段", "tool_settings/BATTERY", 2),
    LocalTool("integrations", "地图与集成", "地图源 · 外部应用联动", "tool_settings/INTEGRATIONS", 0),
    LocalTool("service", "车辆服务", "后台服务 · 车辆数据采集", "tool_settings/SERVICE", 1),
    LocalTool("smart_home", "智能家居", "车辆与家庭场景联动", "tool_settings/SMART_HOME", 2),
    LocalTool("files", "本地媒体", "目录选择 · 扫描媒体 · 相册备份", "native:files", 0),
    LocalTool("diagnostics", "固件与权限", "只读检测 · 适配报告导出", "native:diagnostics", 1),
)
