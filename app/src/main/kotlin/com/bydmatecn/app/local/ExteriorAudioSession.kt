package com.bydmatecn.app.local

import android.content.Context
import android.os.RemoteException
import android.os.SystemClock
import com.bydmatecn.app.data.vehicle.HelperClient
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Vehicle-specific exterior-audio routing observed in the working reference APK. */
@Suppress("LongParameterList")
enum class ExteriorControllerProfile(
    val preferenceValue: String,
    val title: String,
    val streamType: Int,
    internal val openSequence: List<ExteriorAudioCommand>,
    internal val closeSequence: List<ExteriorAudioCommand>,
    internal val endingSequence: List<ExteriorAudioCommand> = emptyList(),
    internal val closeDelayMs: Long = 0,
) {
    CONTROLLER_23(
        preferenceValue = "23",
        title = "23 控制器（车外广播流 15）",
        streamType = 15,
        openSequence = listOf(ExteriorAudioCommand(1000, -1442839934, 1)),
        closeSequence = listOf(ExteriorAudioCommand(1000, -1442839934, 0)),
    ),
    NON_23(
        preferenceValue = "non23",
        title = "非23控制器（锁车音效流 3）",
        streamType = 3,
        openSequence = listOf(ExteriorAudioCommand(1000, -1442839775, 1)),
        closeSequence = listOf(ExteriorAudioCommand(1000, -1442839775, 0)),
        endingSequence = listOf(ExteriorAudioCommand(1002, -1442839933, 1)),
        closeDelayMs = 100,
    );

    companion object {
        fun selected(context: Context): ExteriorControllerProfile {
            val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROFILE, CONTROLLER_23.preferenceValue)
            return entries.firstOrNull { it.preferenceValue == value } ?: CONTROLLER_23
        }

        fun select(context: Context, profile: ExteriorControllerProfile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, profile.preferenceValue).apply()
        }

        private const val PREFS = "local-exterior-audio"
        private const val KEY_PROFILE = "controller_profile"
    }
}

internal data class ExteriorAudioCommand(val device: Int, val featureId: Int, val value: Int)

/**
 * Opens the BYD exterior broadcast path through the boot-loaded `auto` service.
 * Process-wide reference counting prevents one owner from closing a route still used by another.
 */
internal class ExteriorAudioSession(
    private val context: Context,
    private val helper: HelperClient? = null,
    private val commandWriter: ((ExteriorAudioCommand) -> Unit)? = null,
) {
    private val log get() = LocalFileLog.get(context)
    private var acquired = false
    private var acquiredProfile: ExteriorControllerProfile? = null

    val profile: ExteriorControllerProfile
        get() = acquiredProfile ?: ExteriorControllerProfile.selected(context)
    val streamType: Int get() = profile.streamType

    fun acquire() = synchronized(lock) {
        check(!acquired) { "当前车外音频会话已经打开" }
        val requested = ExteriorControllerProfile.selected(context)
        val current = activeProfile
        check(current == null || current == requested) { "车外音频正在使用另一种控制器配置" }
        if (ownerCount == 0) {
            requested.openSequence.forEach(::write)
            activeProfile = requested
            log.record("exterior/open", "profile=${requested.preferenceValue}; stream=${requested.streamType}")
        }
        ownerCount++
        acquired = true
        acquiredProfile = requested
        log.record("exterior/owner", "acquire count=$ownerCount profile=${requested.preferenceValue}")
    }

    @Suppress("TooGenericExceptionCaught")
    fun release() = synchronized(lock) {
        if (!acquired) return@synchronized
        acquired = false
        val closing = acquiredProfile ?: activeProfile
        acquiredProfile = null
        ownerCount = (ownerCount - 1).coerceAtLeast(0)
        log.record("exterior/owner", "release count=$ownerCount profile=${closing?.preferenceValue}")
        if (ownerCount != 0 || closing == null) return@synchronized
        try {
            closing.closeSequence.forEach(::write)
            if (closing.closeDelayMs > 0 && closing.endingSequence.isNotEmpty()) {
                SystemClock.sleep(closing.closeDelayMs)
            }
            closing.endingSequence.forEach(::write)
            activeProfile = null
            log.record("exterior/close", "profile=${closing.preferenceValue}; ending=${closing.endingSequence.size}")
        } catch (error: Exception) {
            // Route ownership is cleared so the user can retry; every failed command is already logged.
            activeProfile = null
            log.record("exterior/close-error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun write(command: ExteriorAudioCommand) {
        log.record("exterior/setInt", "device=${command.device}; fid=${command.featureId}; value=${command.value}")
        try {
            val supplied = commandWriter
            val helperStatus = when {
                supplied != null -> { supplied(command); null }
                helper != null -> HelperServiceWriter.write(helper, command)
                else -> { AutoServiceWriter.write(context, command); null }
            }
            log.record(
                "exterior/setInt-ok",
                "device=${command.device}; fid=${command.featureId}; value=${command.value}; " +
                    "writer=${if (helper != null && supplied == null) "helper" else "app"}; " +
                    "status=${helperStatus ?: "n/a"}",
            )
        } catch (error: ReflectiveOperationException) {
            failWrite(error)
        } catch (error: RemoteException) {
            failWrite(error)
        } catch (error: SecurityException) {
            failWrite(error)
        } catch (error: IllegalStateException) {
            failWrite(error)
        }
    }

    private fun failWrite(error: Exception): Nothing {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val translated = when {
            error is InvocationTargetException ->
                IllegalStateException("车外通道指令被固件拒绝：${cause.message}", cause)
            error is ReflectiveOperationException ->
                IllegalStateException("固件没有可用的 auto.setInt 接口：${error.message}", error)
            error is IllegalStateException -> error
            else -> IllegalStateException("高权限 helper 写入车外通道失败：${error.message}", error)
        }
        log.record("exterior/setInt-error", "${cause.javaClass.simpleName}: ${cause.message}")
        throw translated
    }

    /**
     * A manifest declaration does not guarantee that the firmware granted BYDAUTO_AUDIO_SET to
     * this app UID. Send the exact, fixed exterior-audio commands through the already-bound shell
     * helper instead; status >= 0 is the autoservice accepted/no-op convention.
     */
    private object HelperServiceWriter {
        fun write(helper: HelperClient, command: ExteriorAudioCommand): Int {
            val status = runBlocking(Dispatchers.IO) {
                helper.writeStatus(command.device, command.featureId, command.value)
            } ?: error("高权限 helper 未连接或无响应")
            check(status >= 0) { "autoservice 拒绝车外通道指令，status=$status" }
            return status
        }
    }

    private object AutoServiceWriter {
        @Volatile private var cachedType: Class<*>? = null
        @Volatile private var cachedMethod: java.lang.reflect.Method? = null

        fun write(context: Context, command: ExteriorAudioCommand) {
            val manager = context.getSystemService("auto") ?: error("固件未注册 auto 系统服务")
            var method = cachedMethod
            if (method == null || cachedType != manager.javaClass) {
                method = manager.javaClass.getMethod(
                    "setInt",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                cachedType = manager.javaClass
                cachedMethod = method
            }
            method.invoke(manager, command.device, command.featureId, command.value)
        }
    }

    companion object {
        private val lock = Any()
        private var ownerCount = 0
        private var activeProfile: ExteriorControllerProfile? = null
    }
}
