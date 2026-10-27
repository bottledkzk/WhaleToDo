package io.whaletodo.app.share

/** Pure geometry/naming rules for share exports; kept free of Android types for local tests. */
internal object SharePlan {
    const val TARGET_WIDTH = 1080

    data class Size(val width: Int, val height: Int)

    fun outputSize(width: Int, height: Int, targetWidth: Int = TARGET_WIDTH): Size {
        require(width > 0 && height > 0) { "invalid bitmap size" }
        require(targetWidth > 0) { "invalid target width" }
        val scaledHeight = ((height.toLong() * targetWidth + width / 2) / width)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return Size(targetWidth, scaledHeight)
    }

    fun shareFileName(timestamp: Long): String = "whaletodo-share-$timestamp.png"

    fun cameraFileName(timestamp: Long): String = "whaletodo-camera-$timestamp.jpg"

    fun galleryFileName(timestamp: Long): String = "WhaleToDo_$timestamp.png"
}
