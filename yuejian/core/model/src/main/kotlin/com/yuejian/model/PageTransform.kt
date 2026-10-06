package com.yuejian.model

data class Point(val x: Float, val y: Float)
data class NormalizedRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(listOf(x, y, width, height).all { it.isFinite() })
        require(x in 0f..1f && y in 0f..1f && width >= 0 && height >= 0)
        require(x + width <= 1.0001f && y + height <= 1.0001f)
    }
}
/** All inputs are pixels except the unrotated page coordinates and crop. */
class PageTransform(
    private val width: Float,
    private val height: Float,
    private val scale: Float = 1f,
    private val translation: Point = Point(0f, 0f),
    private val rotation: Int = 0,
    private val crop: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f)
) {
    init {
        require(width.isFinite() && height.isFinite() && scale.isFinite())
        require(translation.x.isFinite() && translation.y.isFinite())
        require(width > 0 && height > 0 && scale > 0)
        require(crop.width > 0 && crop.height > 0)
        require(rotation in listOf(0, 90, 180, 270))
    }
    fun toScreen(page: Point): Point {
        val x = (page.x - crop.x) / crop.width
        val y = (page.y - crop.y) / crop.height
        val p = when (rotation) {
            90 -> Point(1-y, x)
            180 -> Point(1-x, 1-y)
            270 -> Point(y, 1-x)
            else -> Point(x, y)
        }
        return Point(p.x * width * scale + translation.x, p.y * height * scale + translation.y)
    }
    fun toPage(screen: Point): Point {
        val x = (screen.x - translation.x) / (width * scale)
        val y = (screen.y - translation.y) / (height * scale)
        val p = when (rotation) {
            90 -> Point(y, 1-x)
            180 -> Point(1-x, 1-y)
            270 -> Point(1-y, x)
            else -> Point(x, y)
        }
        return Point(p.x * crop.width + crop.x, p.y * crop.height + crop.y)
    }
}
