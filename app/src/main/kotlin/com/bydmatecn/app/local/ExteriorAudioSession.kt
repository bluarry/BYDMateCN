package com.bydmatecn.app.local

import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.byd.spi.ipc.cursor.BinderCursor
import com.bydmatecn.app.cluster.decodeResult
import com.bydmatecn.app.cluster.decodeStatus

/** Firmware DiCarServer/CarSearch agree on transactions 23/24; never guess an amp FID.
 * A switch readback is not proof of audible output. ESS source/mode is deliberately untouched.
 */
internal class ExteriorAudioSession(private val context: Context,
    private val binderLookup: () -> IBinder? = { amplifierBinder(context) },
) {
    private var restore: (() -> Unit)? = null
    private val log get() = LocalFileLog.get(context)

    @Suppress("TooGenericExceptionCaught") // Rollback must also cover unexpected vendor Parcel/provider failures.
    fun acquire() = synchronized(ownershipLock) {
        check(owner == null) { "其他车外音频会话尚未结束或功放恢复失败" }
        check(restore == null) { "车外音频会话已占用" }
        val binder = binderLookup() ?: error("车外功放服务未返回 Binder")
        check(binder.interfaceDescriptor == DESCRIPTOR) { "车外功放 Binder 类型不匹配" }
        val previous = read(binder)
        log.record("exterior/session", "before=$previous descriptor=${binder.interfaceDescriptor}")
        owner = this
        // Arm rollback BEFORE the write: a failed acknowledgement may still have applied.
        if (!previous) {
            restore = { write(binder, previous); check(read(binder) == previous) { "功放恢复未确认" } }
            try {
                write(binder, true)
                check(read(binder)) { "车外扬声器开关没有确认开启" }
            } catch (error: Exception) { release(); throw error }
        } else restore = { }
        log.record("exterior/session", "switch confirmed; audible route still requires car test")
    }

    @Suppress("TooGenericExceptionCaught") // Restore failure is logged and retained; never masks playback failure.
    fun release() = synchronized(ownershipLock) {
        val rollback = restore ?: return@synchronized
        try {
            rollback()
            restore = null
            owner = null
            log.record("exterior/session", "restored original switch")
        } catch (error: Exception) {
            // Retain the rollback so a later stop retries; do not mask the playback error.
            log.record("exterior/restore-error", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun read(binder: IBinder): Boolean = transact(binder, 23, null) { reply ->
        val result = decodeResult(reply) ?: error("功放状态为空")
        check(result.code == 0 && result.value is Boolean) { "功放读取拒绝 code=${result.code}: ${result.message}" }
        result.value as Boolean
    }
    private fun write(binder: IBinder, enabled: Boolean) = transact(binder, 24, enabled) { reply ->
        val result = decodeStatus(reply) ?: error("功放写入状态为空")
        check(result.code == 0) { "功放写入拒绝 code=${result.code}: ${result.message}" }
    }
    private fun <T> transact(binder: IBinder, transaction: Int, enabled: Boolean?, decode: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            enabled?.let { data.writeInt(if (it) 1 else 0) }
            check(binder.transact(transaction, data, reply, 0)) { "固件不支持功放 transaction=$transaction" }
            decode(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    companion object {
        private const val DESCRIPTOR = "com.byd.car.feature.amplifier.ICarAmplifierService"
        private val ownershipLock = Any()
        private var owner: ExteriorAudioSession? = null
    }
}

/** Kept separate so transaction and rollback tests do not depend on a real vehicle provider. */
private fun amplifierBinder(context: Context): IBinder? = context.contentResolver.query(
    Uri.parse("content://com.byd.car.server.provider.CarServiceProvider/sync_binder"),
    null, null, arrayOf("com.byd.car.feature.amplifier.ICarAmplifierService"), null,
)?.use { cursor ->
    cursor.extras.classLoader = BinderCursor.BinderParcelable::class.java.classLoader
    @Suppress("DEPRECATION")
    (cursor.extras.getParcelable<Parcelable>("binder") as? BinderCursor.BinderParcelable)?.binder
}
