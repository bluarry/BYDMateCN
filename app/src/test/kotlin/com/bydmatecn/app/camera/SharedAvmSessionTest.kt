package com.bydmatecn.app.camera

import android.hardware.AVMCamera
import android.view.Surface
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedAvmSessionTest {
    private lateinit var session: SharedAvmSession
    private val recorder: Surface = mockk(relaxed = true)
    private val replacement: Surface = mockk(relaxed = true)
    private val mirror: Surface = mockk(relaxed = true)

    @Before fun setUp() {
        AVMCamera.reset()
        session = SharedAvmSession()
    }

    @After fun tearDown() {
        session.detach("mirror", "test cleanup")
        session.detach("recorder", "test cleanup")
        AVMCamera.reset()
    }

    @Test fun `two consumers are bound to one AVM handle`() {
        assertTrue(session.attach("recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND))
        assertTrue(session.attach("mirror", listOf(AvmSurfaceBinding(2, mirror)), AvmConsumerPriority.INTERACTIVE))

        assertTrue(session.isActive("recorder"))
        assertTrue(session.isActive("mirror"))
        assertEquals(1, AVMCamera.openCount)
        assertEquals(listOf(2, 2), AVMCamera.addedIndexes.takeLast(2))
        assertTrue(AVMCamera.setIndexes.isEmpty())
    }

    @Test fun `detaching one consumer keeps the other active`() {
        session.attach("recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND)
        session.attach("mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE)

        val opensBeforeDetach = AVMCamera.openCount
        session.detach("mirror", "hidden")

        assertFalse(session.isActive("mirror"))
        assertTrue(session.isActive("recorder"))
        assertEquals(opensBeforeDetach, AVMCamera.openCount)
        assertEquals(3, AVMCamera.removedIndexes.last())
    }

    @Test fun `shared physical binding remains until its last owner detaches`() {
        val binding = AvmSurfaceBinding(2, recorder)
        assertTrue(session.attach("recorder", listOf(binding), AvmConsumerPriority.BACKGROUND))
        assertTrue(session.attach("mirror", listOf(binding), AvmConsumerPriority.INTERACTIVE))

        assertEquals(listOf(2), AVMCamera.addedIndexes)
        session.detach("mirror", "hidden")

        assertTrue(session.isActive("recorder"))
        assertTrue(AVMCamera.removedIndexes.isEmpty())
    }

    @Test fun `source size and fps are configured before surfaces start`() {
        val config = AvmSourceConfig(1280, 720, 12)

        assertTrue(
            session.attach(
                "recorder",
                listOf(AvmSurfaceBinding(2, recorder)),
                AvmConsumerPriority.BACKGROUND,
                config,
            ),
        )

        assertEquals(listOf(1280 to 720), AVMCamera.previewSizes)
        assertEquals(listOf(12), AVMCamera.cameraFps)
        assertEquals(listOf("size", "fps", "add", "start"), AVMCamera.calls)
    }

    @Test fun `preferred source follows the active producer dimensions`() {
        val active = AvmSourceConfig(2560, 720, 18)
        assertTrue(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE, active,
        ))

        assertEquals(active, session.preferredSourceConfig())
    }

    @Test fun `same source size combines consumers at maximum requested fps`() {
        assertTrue(session.attach(
            "recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND,
            AvmSourceConfig(1280, 720, 10),
        ))
        assertTrue(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
            AvmSourceConfig(1280, 720, 20),
        ))

        assertTrue(session.isActive("recorder"))
        assertTrue(session.isActive("mirror"))
        assertEquals(20, AVMCamera.cameraFps.last())
    }


    @Test fun `fps update rejection still attaches the new consumer`() {
        assertTrue(session.attach(
            "recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND,
            AvmSourceConfig(1280, 720, 10),
        ))
        AVMCamera.sourceConfigResult = false

        assertTrue(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
            AvmSourceConfig(1280, 720, 20),
        ))

        assertTrue(session.isActive("recorder"))
        assertTrue(session.isActive("mirror"))
        assertEquals(1, AVMCamera.openCount)
        assertEquals(listOf(2, 3), AVMCamera.addedIndexes.takeLast(2))
        assertTrue(session.diagnosticSnapshot().contains("recorder=[2]"))
        assertTrue(session.diagnosticSnapshot().contains("mirror=[3]"))
    }

    @Test fun `conflicting size inherits active producer without suspending recorder`() {
        assertTrue(session.attach(
            "recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND,
            AvmSourceConfig(1280, 720, 15),
        ))
        val opens = AVMCamera.openCount

        assertTrue(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
            AvmSourceConfig(1920, 1080, 15),
        ))

        assertTrue(session.isActive("recorder"))
        assertTrue(session.isActive("mirror"))
        assertEquals(opens, AVMCamera.openCount)
        assertEquals(1280 to 720, AVMCamera.previewSizes.last())
    }

    @Test fun `last detach closes the AVM handle`() {
        session.attach("recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND)
        session.detach("recorder", "stopped")

        assertFalse(session.isActive("recorder"))
        assertEquals(1, AVMCamera.closeCount)
    }

    @Test fun `deferred owner can retry without reopening working producer`() {
        assertTrue(session.attach(
            "recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND,
        ))
        AVMCamera.maxBindings = 1
        assertFalse(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
        ))
        val opens = AVMCamera.openCount

        AVMCamera.maxBindings = 2
        assertTrue(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
        ))

        assertEquals(opens, AVMCamera.openCount)
        assertTrue(session.isActive("recorder"))
        assertTrue(session.isActive("mirror"))
    }

    @Test fun `rejected extra binding preserves existing recorder`() {
        assertTrue(session.attach(
            "recorder", listOf(AvmSurfaceBinding(2, recorder)), AvmConsumerPriority.BACKGROUND,
        ))
        AVMCamera.maxBindings = 1

        assertFalse(session.attach(
            "mirror", listOf(AvmSurfaceBinding(3, mirror)), AvmConsumerPriority.INTERACTIVE,
        ))

        assertTrue(session.isActive("recorder"))
        assertFalse(session.isActive("mirror"))
        assertTrue(session.hasRegistration("mirror"))
        assertEquals(1, AVMCamera.openCount)
    }

    @Test fun `same owner replaces surface without reopening producer`() {
        val oldBinding = AvmSurfaceBinding(2, recorder)
        val newBinding = AvmSurfaceBinding(2, replacement)
        assertTrue(session.attach("recorder", listOf(oldBinding), AvmConsumerPriority.BACKGROUND))
        val opens = AVMCamera.openCount

        assertTrue(session.attach("recorder", listOf(newBinding), AvmConsumerPriority.BACKGROUND))

        assertEquals(opens, AVMCamera.openCount)
        assertFalse(session.isBindingActive("recorder", oldBinding))
        assertTrue(session.isBindingActive("recorder", newBinding))
        assertEquals(listOf(2, 2), AVMCamera.addedIndexes.takeLast(2))
        assertEquals(2, AVMCamera.removedIndexes.last())
    }

    @Test fun `failed surface replacement rolls back provisional binding and remains retryable`() {
        val oldBinding = AvmSurfaceBinding(2, recorder)
        val newBinding = AvmSurfaceBinding(2, replacement)
        assertTrue(session.attach("recorder", listOf(oldBinding), AvmConsumerPriority.BACKGROUND))
        AVMCamera.removeFailures = 1

        assertFalse(session.attach("recorder", listOf(newBinding), AvmConsumerPriority.BACKGROUND))

        assertTrue(session.isBindingActive("recorder", oldBinding))
        assertFalse(session.isBindingActive("recorder", newBinding))
        assertEquals(listOf(2, 2), AVMCamera.removedIndexes.takeLast(2))

        assertTrue(session.attach("recorder", listOf(newBinding), AvmConsumerPriority.BACKGROUND))
        assertFalse(session.isBindingActive("recorder", oldBinding))
        assertTrue(session.isBindingActive("recorder", newBinding))
    }

    @Test fun `diagnostics expose indexes but never surface text`() {
        assertTrue(session.attach(
            "recorder",
            listOf(AvmSurfaceBinding(2, recorder)),
            AvmConsumerPriority.BACKGROUND,
        ))

        val snapshot = session.diagnosticSnapshot()
        assertTrue(snapshot.contains("recorder=[2]"))
        assertFalse(snapshot.contains(recorder.toString()))
        assertFalse(snapshot.contains("Surface("))
    }

    @Test fun `partial remove failure restores every old physical binding`() {
        val oldFirst = AvmSurfaceBinding(1, recorder)
        val oldSecond = AvmSurfaceBinding(2, mirror)
        val newFirst = AvmSurfaceBinding(1, replacement)
        val newSecondSurface: Surface = mockk(relaxed = true)
        val newSecond = AvmSurfaceBinding(2, newSecondSurface)
        assertTrue(session.attach(
            "recorder",
            listOf(oldFirst, oldSecond),
            AvmConsumerPriority.BACKGROUND,
        ))
        // Warm replacement adds both new outputs, removes oldFirst, then fails on oldSecond.
        AVMCamera.failRemoveCalls += 2

        assertFalse(session.attach(
            "recorder",
            listOf(newFirst, newSecond),
            AvmConsumerPriority.BACKGROUND,
        ))

        assertTrue(session.isBindingActive("recorder", oldFirst))
        assertTrue(session.isBindingActive("recorder", oldSecond))
        assertFalse(session.isBindingActive("recorder", newFirst))
        assertFalse(session.isBindingActive("recorder", newSecond))
        assertTrue(AVMCamera.activeBindings.any { it.first == 1 && it.second === recorder })
        assertTrue(AVMCamera.activeBindings.any { it.first == 2 && it.second === mirror })
        assertFalse(AVMCamera.activeBindings.any { it.second === replacement })
        assertFalse(AVMCamera.activeBindings.any { it.second === newSecondSurface })

        // The desired replacement remains registered and can be retried without reopening AVM.
        val opens = AVMCamera.openCount
        assertTrue(session.attach(
            "recorder",
            listOf(newFirst, newSecond),
            AvmConsumerPriority.BACKGROUND,
        ))
        assertEquals(opens, AVMCamera.openCount)
        assertTrue(session.isBindingActive("recorder", newFirst))
        assertTrue(session.isBindingActive("recorder", newSecond))
    }


    @Test fun `reselecting old live binding discards deferred replacement`() {
        val oldBinding = AvmSurfaceBinding(2, recorder)
        val rejectedBinding = AvmSurfaceBinding(2, replacement)
        assertTrue(session.attach("recorder", listOf(oldBinding), AvmConsumerPriority.BACKGROUND))
        AVMCamera.maxBindings = 1

        assertFalse(session.attach(
            "recorder",
            listOf(rejectedBinding),
            AvmConsumerPriority.BACKGROUND,
        ))
        assertTrue(session.hasRegistration("recorder"))
        assertTrue(session.isBindingActive("recorder", oldBinding))

        assertTrue(session.attach(
            "recorder",
            listOf(oldBinding),
            AvmConsumerPriority.BACKGROUND,
        ))
        session.detach("recorder", "caller selected old live output")

        assertFalse(session.isActive("recorder"))
        assertEquals(1, AVMCamera.closeCount)
    }


}
