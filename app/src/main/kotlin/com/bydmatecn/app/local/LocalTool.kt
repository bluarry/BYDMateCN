package com.bydmatecn.app.local

/** Explicit routes: unsupported vendor/plugins must never masquerade as completed functions. */
data class LocalTool(val id: String, val title: String, val subtitle: String, val route: String, val accent: Int)

val localTools = listOf(
    LocalTool("audio", "音频", "喊话 · 锁车音效 · 录音 · 本地语音", "native:audio", 0),
    LocalTool("mirror", "电子后视", "转向联动 · 显示位置", "blind_spot", 1),
    LocalTool("automation", "自动化", "触发条件 · 场景联动 · 执行记录", "automation", 2),
    LocalTool("voice", "语音助手", "语音按键 · 对话模型 · 播报设置", "tool_settings/VOICE", 3),
    LocalTool("battery", "电池与能耗", "电池参数 · 费用周期 · 充电设置", "tool_settings/BATTERY", 0),
    LocalTool("integrations", "服务集成", "第三方平台 · 消息通知 · 数据上报", "tool_settings/INTEGRATIONS", 1),
    LocalTool("split", "分屏", "多窗口 · 快捷按键 · 应用组合", "tool_settings/SPLIT", 2),
    LocalTool("cast", "投屏", "仪表输出 · HUD · 显示联动", "tool_settings/DISPLAY", 3),
    LocalTool("desktop", "桌面", "桌面卡片 · 悬浮组件 · 媒体显示", "tool_settings/WIDGET", 0),
    LocalTool("service", "车辆服务", "后台服务 · 备份恢复 · 诊断维护", "tool_settings/SERVICE", 1),
    LocalTool("recorder", "行车记录仪与驻车哨兵", "自动启停 · 循环录像 · 事件保护", "camera", 3),
    LocalTool("files", "本地媒体", "目录选择 · 扫描媒体 · 相册备份", "native:files", 0),
    LocalTool("diagnostics", "固件与权限", "只读检测 · 适配报告导出", "native:diagnostics", 1),
    LocalTool("app_settings", "应用设置", "语言 · 日志 · 更新 · 应用信息", "tool_settings/APP", 2),
)
