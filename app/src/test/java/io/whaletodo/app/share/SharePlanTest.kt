package io.whaletodo.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePlanTest {
    @Test
    fun outputUsesFixedWidthAndKeepsAspectRatio() {
        val size = SharePlan.outputSize(360, 462)
        assertEquals(1080, size.width)
        assertEquals(1386, size.height)
    }

    @Test
    fun outputRoundsHeightAndNeverProducesZero() {
        assertEquals(SharePlan.Size(1080, 1), SharePlan.outputSize(1000, 1))
        assertEquals(SharePlan.Size(1080, 540), SharePlan.outputSize(2000, 1000))
    }

    @Test
    fun fileNamesAreLocalSafeAndUnique() {
        val share = SharePlan.shareFileName(1234)
        val camera = SharePlan.cameraFileName(1234)
        val gallery = SharePlan.galleryFileName(1234)
        assertTrue(share.endsWith(".png"))
        assertTrue(camera.endsWith(".jpg"))
        assertTrue(gallery.endsWith(".png"))
        listOf(share, camera, gallery).forEach {
            assertFalse(it.contains('/'))
            assertFalse(it.contains('\\'))
            assertFalse(it.contains(".."))
        }
        assertNotEquals(SharePlan.shareFileName(1234), SharePlan.shareFileName(5678))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBitmapWidthIsRejected() {
        SharePlan.outputSize(0, 10)
    }
}
