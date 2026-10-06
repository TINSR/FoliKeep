package com.yuejian.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {
    @Test fun roundTripWithCropRotationPanAndZoom() {
        for (rotation in listOf(0, 90, 180, 270)) for (zoom in listOf(1f, 2.5f, 5f)) {
            val transform = PageTransform(800f, 1200f, zoom, Point(-312f, 98f), rotation,
                NormalizedRect(.1f, .15f, .7f, .6f))
            for (p in listOf(Point(.1f, .15f), Point(.35f, .4f), Point(.8f, .75f))) {
                val result = transform.toPage(transform.toScreen(p))
                assertEquals(p.x, result.x, .00001f)
                assertEquals(p.y, result.y, .00001f)
            }
        }
    }
    @Test fun rotatedTopLeftHasExpectedPosition() {
        assertEquals(Point(800f, 0f), PageTransform(800f, 1200f, rotation = 90).toScreen(Point(0f, 0f)))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRectangleRejected() {
        NormalizedRect(.9f, 0f, .2f, 1f)
    }
    @Test(expected = IllegalArgumentException::class) fun nanRejected() {
        NormalizedRect(Float.NaN, 0f, 1f, 1f)
    }
}
