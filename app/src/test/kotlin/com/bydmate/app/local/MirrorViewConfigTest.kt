package com.bydmate.app.local
import org.junit.Assert.*
import org.junit.Test
class MirrorViewConfigTest {
    @Test fun defaultIsIdentity() { val c = MirrorViewConfig(); assertEquals(1f, c.scaleX(), 0f); assertEquals(1f, c.scaleY(), 0f) }
    @Test fun flipOnlyChangesHorizontalSign() { val c = MirrorViewConfig(true, 150, 90); assertEquals(-1.5f, c.scaleX(), 0f); assertEquals(1.5f, c.scaleY(), 0f) }
    @Test(expected = IllegalArgumentException::class) fun invalidZoomRejected() { MirrorViewConfig(zoomPercent = 201) }
    @Test(expected = IllegalArgumentException::class) fun invalidRotationRejected() { MirrorViewConfig(rotation = 45) }
}
