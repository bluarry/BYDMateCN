package com.bydmatecn.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmatecn.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExteriorAudioSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun controller23UsesBroadcastPositionSequence() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.CONTROLLER_23)
        val commands = mutableListOf<ExteriorAudioCommand>()
        val session = ExteriorAudioSession(context) { commands += it }
        try {
            session.acquire()
            assertEquals(15, session.streamType)
        } finally { session.release() }
        assertEquals(
            listOf(ExteriorAudioCommand(1000, -1442839934, 1), ExteriorAudioCommand(1000, -1442839934, 0)),
            commands,
        )
    }

    @Test fun non23UsesLockPlaybackAndEndingSequence() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.NON_23)
        val commands = mutableListOf<ExteriorAudioCommand>()
        val session = ExteriorAudioSession(context) { commands += it }
        try {
            session.acquire()
            assertEquals(3, session.streamType)
        } finally { session.release() }
        assertEquals(
            listOf(
                ExteriorAudioCommand(1000, -1442839775, 1),
                ExteriorAudioCommand(1000, -1442839775, 0),
                ExteriorAudioCommand(1002, -1442839933, 1),
            ),
            commands,
        )
    }

    @Test fun multipleOwnersOnlyOpenAndCloseOnce() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.CONTROLLER_23)
        val commands = mutableListOf<ExteriorAudioCommand>()
        val first = ExteriorAudioSession(context) { commands += it }
        val second = ExteriorAudioSession(context) { commands += it }
        first.acquire(); second.acquire()
        first.release()
        assertEquals(listOf(ExteriorAudioCommand(1000, -1442839934, 1)), commands)
        second.release()
        assertEquals(2, commands.size)
    }

    @Test fun openingFailureDoesNotClaimOwnership() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.CONTROLLER_23)
        val failed = ExteriorAudioSession(context) { error("denied") }
        assertThrows(IllegalStateException::class.java) { failed.acquire() }
        val commands = mutableListOf<ExteriorAudioCommand>()
        val retry = ExteriorAudioSession(context) { commands += it }
        try { retry.acquire() } finally { retry.release() }
        assertEquals(2, commands.size)
    }

    @Test fun productionWriterUsesPrivilegedHelperForOpenAndClose() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.CONTROLLER_23)
        val helper = mockk<HelperClient>()
        coEvery { helper.writeStatus(any(), any(), any()) } returns 1
        val session = ExteriorAudioSession(context, helper)

        try { session.acquire() } finally { session.release() }

        coVerify(exactly = 1) { helper.writeStatus(1000, -1442839934, 1) }
        coVerify(exactly = 1) { helper.writeStatus(1000, -1442839934, 0) }
    }

    @Test fun helperRefusalDoesNotClaimOwnership() {
        ExteriorControllerProfile.select(context, ExteriorControllerProfile.CONTROLLER_23)
        val helper = mockk<HelperClient>()
        coEvery { helper.writeStatus(any(), any(), any()) } returns -1

        assertThrows(IllegalStateException::class.java) {
            ExteriorAudioSession(context, helper).acquire()
        }
        coVerify(exactly = 1) { helper.writeStatus(1000, -1442839934, 1) }
    }
}
