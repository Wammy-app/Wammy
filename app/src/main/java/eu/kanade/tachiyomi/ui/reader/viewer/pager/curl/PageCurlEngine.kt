package eu.kanade.tachiyomi.ui.reader.viewer.pager.curl

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure mathematical engine that computes 2D/3D diagonal page curl geometry,
 * fold line angles, reflection transforms, and shadow coordinates.
 */
class PageCurlEngine {

    var pageLeft: Float = 0f
        private set
    var pageTop: Float = 0f
        private set
    var pageRight: Float = 0f
        private set
    var pageBottom: Float = 0f
        private set

    val pageWidth: Float
        get() = pageRight - pageLeft

    val pageHeight: Float
        get() = pageBottom - pageTop

    var foldTopX: Float = 0f
        private set
    var foldBottomX: Float = 0f
        private set

    var midX: Float = 0f
        private set
    var midY: Float = 0f
        private set

    var foldAngleRad: Float = 0f
        private set
    var foldAngleDeg: Float = 0f
        private set

    // Normal vector pointing perpendicular to fold line towards revealed page
    var normalX: Float = 0f
        private set
    var normalY: Float = 0f
        private set

    var curlWidth: Float = 0f
        private set

    // Drop shadow coordinates along fold line
    var shadowStartX: Float = 0f
        private set
    var shadowStartY: Float = 0f
        private set
    var shadowEndX: Float = 0f
        private set
    var shadowEndY: Float = 0f
        private set

    // Inner paper roll highlight coordinates
    var rollStartX: Float = 0f
        private set
    var rollStartY: Float = 0f
        private set
    var rollEndX: Float = 0f
        private set
    var rollEndY: Float = 0f
        private set

    // Corner tip coordinates for corner curl
    var cornerTipX: Float = 0f
        private set
    var cornerTipY: Float = 0f
        private set

    fun setup(left: Float, top: Float, right: Float, bottom: Float) {
        pageLeft = left
        pageTop = top
        pageRight = right
        pageBottom = bottom
    }

    /**
     * Computes the fold line, reflection axis, and shadow vectors.
     *
     * @param progress 0.0f (flat) to 1.0f (fully turned)
     * @param touchY absolute Y coordinate of the touch (or relative ratio)
     * @param fromRight true if curling from right to left, false if left to right
     */
    fun computeCurl(
        progress: Float,
        touchY: Float,
        fromRight: Boolean,
    ) {
        val w = pageWidth
        val h = pageHeight
        if (w <= 0f || h <= 0f) return

        val p = progress.coerceIn(0f, 1f)
        val relTouchY = if (h > 0f) ((touchY - pageTop) / h).coerceIn(0.1f, 0.9f) else 0.5f

        // Natural comic corner tilt: if user touched near the top (relTouchY < 0.5),
        // the top corner curls faster than the bottom; if near bottom, vice versa.
        val tilt = (0.5f - relTouchY) * 0.70f * w

        val totalTravel = w * 1.15f

        val centerFoldX: Float
        if (fromRight) {
            centerFoldX = pageRight - p * totalTravel
            foldTopX = centerFoldX - tilt * (1f - p * 0.4f)
            foldBottomX = centerFoldX + tilt * (1f - p * 0.4f)
        } else {
            centerFoldX = pageLeft + p * totalTravel
            foldTopX = centerFoldX + tilt * (1f - p * 0.4f)
            foldBottomX = centerFoldX - tilt * (1f - p * 0.4f)
        }

        midX = (foldTopX + foldBottomX) * 0.5f
        midY = (pageTop + pageBottom) * 0.5f

        val dx = foldBottomX - foldTopX
        val dy = pageBottom - pageTop
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

        foldAngleRad = atan2(dy, dx)
        foldAngleDeg = Math.toDegrees(foldAngleRad.toDouble()).toFloat()

        // Perpendicular normal vector pointing towards revealed page
        val rawNormX = dy / len
        val rawNormY = -dx / len

        // For fromRight, normal points right (+X). For fromLeft, normal points left (-X).
        val sign = if (fromRight) 1f else -1f
        normalX = rawNormX * sign
        normalY = rawNormY * sign

        // Drop shadow onto revealed underneath page (45px wide along normal)
        val shadowDist = (w * 0.05f).coerceIn(30f, 65f)
        shadowStartX = midX
        shadowStartY = midY
        shadowEndX = midX + normalX * shadowDist
        shadowEndY = midY + normalY * shadowDist

        // 3D paper cylinder roll along the crease (opposite direction to normal)
        rollStartX = midX
        rollStartY = midY
        rollEndX = midX - normalX * curlWidth
        rollEndY = midY - normalY * curlWidth

        // Corner tip position for peeling corner
        val cornerDist = curlWidth * 1.2f
        cornerTipX = foldTopX - normalX * cornerDist
        cornerTipY = pageTop + (if (tilt > 0) cornerDist * 0.5f else 0f)
    }
}
