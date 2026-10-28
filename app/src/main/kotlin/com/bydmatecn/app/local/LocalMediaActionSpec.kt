package com.bydmatecn.app.local

import org.json.JSONObject

/** Pure, shared validation for manual creation, execution and rule exchange. */
object LocalMediaActionSpec {
    val cameraCommands = setOf("snapshot", "record_start", "record_stop")
    val audioCommands = setOf("play", "stop", "exterior", "lock_preview")
    fun valid(kind: String, payload: String?): Boolean = when (kind) {
        "local_camera" -> payload in cameraCommands
        "local_audio" -> runCatching {
            val json = JSONObject(payload.orEmpty())
            val command = json.optString("command")
            val file = json.optString("file")
            command in audioCommands && (command == "stop" ||
                (file.matches(Regex("audio-[A-Za-z0-9._-]+")) &&
                    file.substringAfterLast('.') in LocalResourceStore.AUDIO_TYPES))
        }.getOrDefault(false)
        else -> false
    }
}
