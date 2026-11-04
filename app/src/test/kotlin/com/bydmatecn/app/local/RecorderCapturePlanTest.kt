package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderCapturePlanTest {
    @Test fun invalidSurfaceFallsBackWithoutChangingDirectionOrFlip() {
        val plan = RecorderCapturePlan(RecorderDirection.REAR, 99, true).sanitized()
        assertEquals(RecorderDirection.REAR, plan.direction)
        assertEquals(RecorderCapturePlan.DEFAULT_SURFACE_INDEX, plan.surfaceIndex)
        assertEquals(true, plan.horizontalFlip)
    }

    @Test fun panoramaStripOrderMatchesVehicleSource() {
        assertEquals(3, RecorderDirection.FRONT.stripIndex)
        assertEquals(0, RecorderDirection.REAR.stripIndex)
        assertEquals(1, RecorderDirection.LEFT.stripIndex)
        assertEquals(2, RecorderDirection.RIGHT.stripIndex)
    }

    @Test fun everyDirectionUsesAnIndependentPreferenceKey() {
        val keys = RecorderDirection.entries.map(VehicleRecorderPreferences::captureSurfaceKey)
        assertEquals(keys.size, keys.distinct().size)
        assertEquals("capture_surface_index_front", keys.first())
    }


    @Test fun panoramaStripBoundsCoverTheCropWithoutOverlap() {
        val strips = (0 until 4).map { panoramaStripBounds(10, 1283, it, 4) }
        assertEquals(10, strips.first().left)
        assertEquals(1293, strips.last().rightExclusive)
        assertEquals(strips[0].rightExclusive, strips[1].left)
        assertEquals(strips[1].rightExclusive, strips[2].left)
        assertEquals(strips[2].rightExclusive, strips[3].left)
        assertEquals(1283, strips.sumOf { it.width })
    }

    @Test fun panoramaStripBoundsMatchFourEqualTiles() {
        assertEquals(PanoramaStripBounds(0, 320), panoramaStripBounds(0, 1280, 0, 4))
        assertEquals(PanoramaStripBounds(960, 1280), panoramaStripBounds(0, 1280, 3, 4))
    }

    @Test fun sourceColumnSupportsHorizontalFlipWithoutSecondBitmapPass() {
        assertEquals(10, sourceColumn(10, 100, 0, 4, false))
        assertEquals(85, sourceColumn(10, 100, 0, 4, true))
        assertEquals(10, sourceColumn(10, 100, 3, 4, true))
    }
}
