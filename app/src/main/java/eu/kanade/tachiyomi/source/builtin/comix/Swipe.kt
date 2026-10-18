package eu.kanade.tachiyomi.source.builtin.comix

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.PI
import kotlin.math.pow
import kotlin.random.Random

/**
 * Natural touchscreen human touch simulation for Android WebView.
 * Dispatches real hardware MotionEvents so that JavaScript event.isTrusted === true.
 * Ported from Keiyoushi's Comix implementation.
 */
fun WebView.swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
    val frames = Random.nextInt(25, 37)
    val finger = Finger()
    val handler = Handler(Looper.getMainLooper())

    fun send(frame: Int) {
        val action = when (frame) {
            0 -> MotionEvent.ACTION_DOWN
            frames + 1 -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        val progress = 1 - (1 - (frame.coerceAtMost(frames).toFloat() / frames).pow(1.6f)).pow(4)
        finger.send(this, action, fromX + (toX - fromX) * progress, fromY + (toY - fromY) * progress)
        if (frame <= frames) handler.postDelayed({ send(frame + 1) }, Random.nextLong(14, 19))
    }
    whenDrawn { send(0) }
}

/** Taps ([x], [y]) in CSS pixels the way a human finger does: a short press that barely moves. */
fun WebView.tap(x: Float, y: Float) {
    val finger = Finger()
    val handler = Handler(Looper.getMainLooper())
    val moves = Random.nextInt(0, 3)
    val stepMs = Random.nextLong(70, 130) / (moves + 1)

    fun send(frame: Int) {
        val action = when (frame) {
            0 -> MotionEvent.ACTION_DOWN
            moves + 1 -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }

        // A pressed fingertip rolls a pixel or so
        fun roll() = if (action == MotionEvent.ACTION_MOVE) Random.nextDouble(-1.0, 1.0).toFloat() else 0f
        finger.send(this, action, x + roll(), y + roll())
        if (frame <= moves) handler.postDelayed({ send(frame + 1) }, stepMs)
    }
    whenDrawn { send(0) }
}

/** One fingertip's contact, sent to the WebView as touchscreen events. */
private class Finger {
    private val majorMm = Random.nextDouble(7.0, 10.5).toFloat()
    private val minorRatio = Random.nextDouble(0.6, 0.95).toFloat()
    private val pressure = Random.nextDouble(0.25, 0.55).toFloat()
    private val orientation = Random.nextDouble(-PI / 2, PI / 2).toFloat()
    private var downTime = 0L

    fun send(view: WebView, action: Int, x: Float, y: Float) {
        val metrics = view.resources.displayMetrics
        val pxPerMm = (metrics.xdpi.takeIf { it in 100f..1000f } ?: metrics.densityDpi.toFloat()) / 25.4f
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        fun drift(spread: Double) = if (action == MotionEvent.ACTION_MOVE) Random.nextDouble(1 - spread, 1 + spread).toFloat() else 1f
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x * metrics.density
            this.y = y * metrics.density
            this.pressure = (pressure * drift(0.25)).coerceIn(0.05f, 1f)
            touchMajor = majorMm * pxPerMm * drift(0.15)
            touchMinor = (majorMm * minorRatio * pxPerMm * drift(0.15)).coerceAtMost(touchMajor)
            toolMajor = touchMajor
            toolMinor = touchMinor
            this.orientation = orientation
            size = touchMajor / maxOf(metrics.widthPixels, metrics.heightPixels)
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}

private fun WebView.whenDrawn(block: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var started = false
    val start = Runnable {
        if (!started) {
            started = true
            block()
        }
    }
    handler.postDelayed(start, 1000)
    postVisualStateCallback(
        0,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                handler.removeCallbacks(start)
                start.run()
            }
        },
    )
}
