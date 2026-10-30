package com.bydmatecn.app.local

import android.content.Context
import android.os.Binder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExteriorAudioSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun enableAndRestoreOriginalState() {
        val binder = AmplifierBinder(false)
        val session = ExteriorAudioSession(context) { binder }
        try { session.acquire(); assertTrue(binder.enabled) } finally { session.release() }
        assertFalse(binder.enabled)
        assertEquals(listOf(true, false), binder.writes)
    }

    @Test fun existingEnabledStateIsNotChanged() {
        val binder = AmplifierBinder(true)
        val session = ExteriorAudioSession(context) { binder }
        try { session.acquire() } finally { session.release() }
        assertTrue(binder.enabled)
        assertTrue(binder.writes.isEmpty())
    }

    @Test fun rejectedAcknowledgementStillRollsBackAppliedWrite() {
        val binder = AmplifierBinder(false).apply { rejectEnable = true }
        val session = ExteriorAudioSession(context) { binder }
        try { assertThrows(IllegalStateException::class.java) { session.acquire() } }
        finally { session.release() }
        assertFalse(binder.enabled)
        assertEquals(listOf(true, false), binder.writes)
    }

    @Test fun restorationFailureBlocksOtherSessionsUntilRetry() {
        val binder = AmplifierBinder(false)
        val first = ExteriorAudioSession(context) { binder }
        val second = ExteriorAudioSession(context) { binder }
        try {
            first.acquire()
            binder.rejectDisable = true
            first.release()
            assertThrows(IllegalStateException::class.java) { second.acquire() }
            binder.rejectDisable = false
            first.release()
            second.acquire()
        } finally {
            binder.rejectDisable = false
            first.release()
            second.release()
        }
        assertFalse(binder.enabled)
    }

    private class AmplifierBinder(var enabled: Boolean) : Binder() {
        val writes = mutableListOf<Boolean>()
        var rejectEnable = false
        var rejectDisable = false
        init { attachInterface(null, DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(DESCRIPTOR)
            val response = requireNotNull(reply)
            response.writeNoException()
            response.writeInt(1)
            when (code) {
                23 -> {
                    response.writeInt(0)
                    response.writeString("ok")
                    response.writeString("java.lang.Boolean")
                    response.writeInt(if (enabled) 1 else 0)
                }
                24 -> {
                    val requested = data.readInt() != 0
                    writes.add(requested)
                    val rejected = if (requested) rejectEnable else rejectDisable
                    if (requested || !rejected) enabled = requested
                    response.writeInt(if (rejected) 20004 else 0)
                    response.writeString(if (rejected) "denied" else "ok")
                }
                else -> return false
            }
            return true
        }
    }
    private companion object {
        const val DESCRIPTOR = "com.byd.car.feature.amplifier.ICarAmplifierService"
    }
}
