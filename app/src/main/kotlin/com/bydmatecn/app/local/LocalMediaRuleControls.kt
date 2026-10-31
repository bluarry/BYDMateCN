package com.bydmatecn.app.local

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal fun LocalToolsActivity.mediaRuleControls() {
    val actions = arrayOf("截图", "开始MP4", "停止MP4", "选择音频播放", "停止音频", "车外试听", "锁车试听")
    LocalDialogBuilder(this).setTitle("本地媒体动作（对应页面必须可见）").setItems(actions) { _, selected ->
        val command = listOf(
            "snapshot", "record_start", "record_stop", "play", "stop", "exterior", "lock_preview",
        )[selected]
        if (selected in setOf(3, 5, 6)) {
            showAudioPicker("为“${actions[selected]}”选择音频") { file ->
                promptMediaRule(actions[selected], selected, command, file.path)
            }
        } else promptMediaRule(actions[selected], selected, command, null)
    }.show()
}

private fun LocalToolsActivity.promptMediaRule(
    title: String,
    selected: Int,
    command: String,
    filePath: String?,
) {
    val key = LocalInput("方控Android keyCode（留空=按钮触发）")
    LocalDialogBuilder(this).setTitle(title).setView(key).setNegativeButton("取消", null)
        .setPositiveButton("创建停用规则") { _, _ ->
            lifecycleScope.launch {
                val code = key.text.toString().toIntOrNull()
                if (key.text.isNotBlank() && (code == null || code !in 1..1000)) {
                    status.text = "keyCode无效"
                    return@launch
                }
                val trigger = com.bydmatecn.app.data.local.entity.TriggerDef(
                    if (code == null) "button_press" else "steering_key", "本地触发", "==", code?.toString() ?: "",
                    "${title}快捷操作", if (code == null) "button_press" else "steering_key")
                val actionValue = if (selected < 3) command else org.json.JSONObject()
                    .put("command", command).apply { if (filePath != null) put("file", filePath) }.toString()
                val action = com.bydmatecn.app.data.local.entity.ActionDef(
                    "", title, if (selected < 3) "local_camera" else "local_audio", actionValue)
                val id = rules.insert(com.bydmatecn.app.data.local.entity.RuleEntity(
                    name = "本地：$title", enabled = false,
                    triggers = com.bydmatecn.app.data.local.entity.TriggerDef.listToJson(listOf(trigger)),
                    actions = com.bydmatecn.app.data.local.entity.ActionDef.listToJson(listOf(action)),
                    requirePark = true, cooldownSeconds = 5))
                status.text = "规则${id}已创建且停用，请在自动化页检查并启用；后台不能偷偷打开相机/音频页面。"
            }
        }.show()
}
