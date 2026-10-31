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
                (validAudioReference(file) &&
                    file.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in LocalResourceStore.AUDIO_TYPES))
        }.getOrDefault(false)
        else -> false
    }
    private fun validAudioReference(path: String): Boolean {
        val parts = path.split('/')
        val absolute = path.startsWith('/') && parts.none { it == ".." || it == "." } &&
            path.none { it.isISOControl() }
        // Retain parsing of existing rules; execution still requires a selected readable directory.
        return absolute || path.matches(Regex("audio-[A-Za-z0-9._-]+"))
    }
}
