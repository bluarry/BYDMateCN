package com.bydmatecn.app.local

import android.content.Context
import android.media.AudioAttributes
import java.lang.reflect.InvocationTargetException

/** Contexts observed in the supplied DiLink 5.0 framework. No guessed FID or stream. */
enum class FirmwareAudioRoute(val field: String, val contextId: Int, val title: String) {
    EXTERIOR_VOICE("AUDIO_CONTEXT_EXTERNAL_VOICE", 11002, "车外语音"),
    LOCK_EVENT("AUDIO_CONTEXT_CAR_LOCK_SOUND", 11001, "自动锁车事件路由"),
    LOCK_PREVIEW("AUDIO_CONTEXT_CAR_LOCK_SOUND_PREVIEW", 11003, "锁车音效试听"),
}
internal fun firmwareAudioAllowed(gear: Int?, speed: Int?, resumed: Boolean): Boolean =
    resumed && gear == 1 && speed == 0

/** Resolves the car's boot-loaded implementation; never bundles framework.jar. */
class FirmwareAudioBridge(private val context: Context) {
    fun attributes(route: FirmwareAudioRoute): AudioAttributes {
        try {
            val type = Class.forName("android.media.BydAudioManager")
            val actual = type.getField(route.field).getInt(null)
            check(actual == route.contextId) { "固件 context 不匹配：${route.field}=$actual" }
            val manager = type.getConstructor(Context::class.java).newInstance(context)
            return type.getMethod("getAudioAttributes", Int::class.javaPrimitiveType)
                .invoke(manager, actual) as? AudioAttributes
                ?: error("固件未返回专用属性；不降级为普通媒体")
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("固件接口拒绝：${error.targetException}", error.targetException)
        }
    }
    fun probe(): String = FirmwareAudioRoute.entries.joinToString("\n") { route ->
        runCatching { "${route.title}：context=${route.contextId}；${attributes(route)}" }
            .getOrElse { "${route.title}不可用：${it.javaClass.simpleName}：${it.message}" }
    } + "\n只读取属性；不代表 HAL 已允许外扬声器，没有执行播放。"
}
