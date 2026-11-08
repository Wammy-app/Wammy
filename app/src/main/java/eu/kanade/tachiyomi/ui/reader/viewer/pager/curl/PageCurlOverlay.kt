package eu.kanade.tachiyomi.ui.reader.viewer.pager.curl

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import kotlin.math.abs

/**
 * Overlay view that renders a realistic 3D comic page curl transition bounded to the page area.
 */
@SuppressLint("ViewConstructor")
class PageCurlOverlay(
    context: Context,
    val viewer: PagerViewer,
) : View(context) {

    val engine = PageCurlEngine()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 248, 245)
    }
    private val dropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 85 // Backlit translucent comic paper showing mirrored artwork
    }
    private val reflectMatrix = Matrix()

    private val uncurledPath = Path()
    private val revealedPath = Path()
    private val flapPath = Path()
    private val dropShadowPath = Path()
    private val creasePath = Path()

    private var currentBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null

    var isCurling = false
        private set

    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var fromRight = true
    private var targetPosition = -1

    private var animator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private var soundLoaded = false
    private var lastSoundPlayTime = 0L
    private var activeStreamId = 0

    init {
        visibility = INVISIBLE
        setWillNotDraw(false)
        initSound()
    }

    private fun initSound() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build().apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && sampleId == soundId) {
                        soundLoaded = true
                    }
                }
                soundId = load(context, R.raw.page_turn, 1)
            }
    }

    private fun playPageTurnSound() {
        if (!viewer.config.pageTurnSound) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSoundPlayTime < 280L) return
        lastSoundPlayTime = now

        if (soundLoaded && soundId != 0) {
            if (activeStreamId != 0) {
                soundPool?.stop(activeStreamId)
            }
            activeStreamId = soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f) ?: 0
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            ensureBitmaps(w, h)
        }
    }

    fun ensureBitmaps(
        reqW: Int = width.takeIf { it > 0 } ?: viewer.container.width,
        reqH: Int = height.takeIf { it > 0 } ?: viewer.container.height,
    ): Boolean {
        if (reqW <= 0 || reqH <= 0) return false
        if (currentBitmap == null || currentBitmap?.width != reqW || currentBitmap?.height != reqH) {
            currentBitmap?.recycle()
            nextBitmap?.recycle()
            currentBitmap = Bitmap.createBitmap(reqW, reqH, Bitmap.Config.ARGB_8888)
            nextBitmap = Bitmap.createBitmap(reqW, reqH, Bitmap.Config.ARGB_8888)
        }
        return currentBitmap != null && nextBitmap != null
    }

    fun shouldInterceptTouch(ev: MotionEvent): Boolean {
        if (viewer.isZoomed()) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isCurling) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.3f) {
                    if (!ensureBitmaps()) return false

                    val currentPos = viewer.pager.currentItem
                    val targetPos = if (dx < 0) currentPos + 1 else currentPos - 1
                    val curlFromRight = (dx < 0)

                    if (targetPos in 0 until viewer.adapter.count) {
                        val curBmp = currentBitmap ?: return false
                        val nxtBmp = nextBitmap ?: return false
                        val okCur = viewer.capturePageToBitmap(currentPos, curBmp)
                        val okNxt = viewer.capturePageToBitmap(targetPos, nxtBmp)
                        if (okCur && okNxt) {
                            val bounds = viewer.getPageBounds(currentPos)
                                ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
                            engine.setup(bounds.left, bounds.top, bounds.right, bounds.bottom)

                            isDragging = true
                            isCurling = true
                            targetPosition = targetPos
                            fromRight = curlFromRight
                            visibility = VISIBLE

                            val pw = engine.pageWidth.takeIf { it > 0 } ?: width.toFloat().coerceAtLeast(1f)
                            val rawDist = if (fromRight) (downX - ev.x) else (ev.x - downX)
                            val dist = (rawDist - touchSlop).coerceAtLeast(0f)
                            val travel = (pw * 0.55f).coerceAtLeast(300f)
                            val progress = (dist / travel).coerceIn(0f, 1f)
                            engine.computeCurl(progress, downY, fromRight)
                            invalidate()

                            velocityTracker?.addMovement(ev)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isCurling && !shouldInterceptTouch(ev)) {
            return false
        }
        velocityTracker?.addMovement(ev)

        val pw = engine.pageWidth.takeIf { it > 0 } ?: width.toFloat().coerceAtLeast(1f)
        val travel = (pw * 0.55f).coerceAtLeast(300f)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && travel > 0f) {
                    val rawDist = if (fromRight) (downX - ev.x) else (ev.x - downX)
                    val dist = (rawDist - touchSlop).coerceAtLeast(0f)
                    val progress = (dist / travel).coerceIn(0f, 1f)
                    engine.computeCurl(progress, downY, fromRight)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging && travel > 0f) {
                    isDragging = false
                    val rawDist = if (fromRight) (downX - ev.x) else (ev.x - downX)
                    val dist = (rawDist - touchSlop).coerceAtLeast(0f)
                    val progress = (dist / travel).coerceIn(0f, 1f)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val isFlingForward = if (fromRight) vx < -350f else vx > 350f
                    val isFlingBackward = if (fromRight) vx > 350f else vx < -350f
                    val shouldCommit = (progress > 0.38f && !isFlingBackward) || isFlingForward
                    if (shouldCommit) {
                        playPageTurnSound()
                    }
                    animateSettle(from = progress, to = if (shouldCommit) 1f else 0f, commit = shouldCommit)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging && travel > 0f) {
                    isDragging = false
                    val rawDist = if (fromRight) (downX - ev.x) else (ev.x - downX)
                    val dist = (rawDist - touchSlop).coerceAtLeast(0f)
                    val progress = (dist / travel).coerceIn(0f, 1f)
                    animateSettle(from = progress, to = 0f, commit = false)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    fun startCurlAnimation(targetPos: Int, fromRight: Boolean, onComplete: (() -> Unit)? = null) {
        if (isCurling) return
        if (!ensureBitmaps()) {
            viewer.pager.setCurrentItem(targetPos, false)
            onComplete?.invoke()
            return
        }

        val curBmp = currentBitmap ?: run {
            viewer.pager.setCurrentItem(targetPos, false)
            onComplete?.invoke()
            return
        }
        val nxtBmp = nextBitmap ?: run {
            viewer.pager.setCurrentItem(targetPos, false)
            onComplete?.invoke()
            return
        }

        val currentPos = viewer.pager.currentItem
        val okCur = viewer.capturePageToBitmap(currentPos, curBmp)
        val okNxt = viewer.capturePageToBitmap(targetPos, nxtBmp)
        if (!okCur || !okNxt) {
            viewer.pager.setCurrentItem(targetPos, false)
            onComplete?.invoke()
            return
        }

        val bounds = viewer.getPageBounds(currentPos)
            ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        engine.setup(bounds.left, bounds.top, bounds.right, bounds.bottom)

        targetPosition = targetPos
        this.fromRight = fromRight
        isCurling = true
        visibility = VISIBLE

        val touchY = bounds.top + bounds.height() * 0.25f
        playPageTurnSound()

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                engine.computeCurl(p, touchY, fromRight)
                invalidate()
            }
            doOnEnd {
                finishCurl(commit = true)
                onComplete?.invoke()
            }
            start()
        }
    }

    private fun animateSettle(from: Float, to: Float, commit: Boolean) {
        animator?.cancel()

        val duration = if (commit) {
            ((1f - from) * 450f + 180f).toLong().coerceIn(320L, 500L)
        } else {
            (from * 400f + 120f).toLong().coerceIn(180L, 350L)
        }
        val settleInterpolator = if (commit) {
            DecelerateInterpolator(1.8f)
        } else {
            DecelerateInterpolator(1.5f)
        }
        animator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = settleInterpolator
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                engine.computeCurl(p, downY, fromRight)
                invalidate()
            }
            doOnEnd {
                finishCurl(commit)
            }
            start()
        }
    }

    private fun finishCurl(commit: Boolean) {
        val target = targetPosition
        isCurling = false
        isDragging = false
        visibility = INVISIBLE
        if (commit && target >= 0) {
            viewer.pager.setCurrentItem(target, false)
        }
    }

    private fun getBackgroundColor(): Int = when (viewer.config.theme) {
        0 -> Color.WHITE
        2 -> Color.rgb(32, 33, 36)
        1 -> Color.rgb(25, 26, 28)
        else -> Color.rgb(25, 26, 28)
    }

    override fun onDraw(canvas: Canvas) {
        if (!isCurling) return
        val curBmp = currentBitmap ?: return
        val nxtBmp = nextBitmap ?: return

        val pL = engine.pageLeft
        val pT = engine.pageTop
        val pR = engine.pageRight
        val pB = engine.pageBottom
        val fxT = engine.foldTopX
        val fxB = engine.foldBottomX
        val normX = engine.normalX
        val normY = engine.normalY
        val cWidth = engine.curlWidth

        // 1. Static backdrop: preserve the black/gray letterbox bars outside the page
        canvas.drawColor(getBackgroundColor())

        // 2. Underneath revealed page (nxtBmp)
        revealedPath.reset()
        if (fromRight) {
            revealedPath.moveTo(fxT, pT)
            revealedPath.lineTo(pR, pT)
            revealedPath.lineTo(pR, pB)
            revealedPath.lineTo(fxB, pB)
        } else {
            revealedPath.moveTo(pL, pT)
            revealedPath.lineTo(fxT, pT)
            revealedPath.lineTo(fxB, pB)
            revealedPath.lineTo(pL, pB)
        }
        revealedPath.close()

        canvas.save()
        canvas.clipRect(pL, pT, pR, pB)
        canvas.clipPath(revealedPath)
        canvas.drawBitmap(nxtBmp, 0f, 0f, bitmapPaint)

        // Drop shadow onto revealed page along diagonal fold line
        val sDist = 50f
        dropShadowPath.reset()
        dropShadowPath.moveTo(fxT, pT)
        dropShadowPath.lineTo(fxT + normX * sDist, pT + normY * sDist)
        dropShadowPath.lineTo(fxB + normX * sDist, pB + normY * sDist)
        dropShadowPath.lineTo(fxB, pB)
        dropShadowPath.close()

        dropShadowPaint.shader = LinearGradient(
            engine.shadowStartX,
            engine.shadowStartY,
            engine.shadowEndX,
            engine.shadowEndY,
            Color.argb(130, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(dropShadowPath, dropShadowPaint)
        canvas.restore()

        // 3. Flat uncurled part of the current page (curBmp)
        uncurledPath.reset()
        if (fromRight) {
            uncurledPath.moveTo(pL, pT)
            uncurledPath.lineTo(fxT, pT)
            uncurledPath.lineTo(fxB, pB)
            uncurledPath.lineTo(pL, pB)
        } else {
            uncurledPath.moveTo(fxT, pT)
            uncurledPath.lineTo(pR, pT)
            uncurledPath.lineTo(pR, pB)
            uncurledPath.lineTo(fxB, pB)
        }
        uncurledPath.close()

        canvas.save()
        canvas.clipRect(pL, pT, pR, pB)
        canvas.clipPath(uncurledPath)
        canvas.drawBitmap(curBmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        // 4. Actual folded flap (the entire turned page back)
        // Mathematically reflect the peeled region across the fold line!
        reflectMatrix.reset()
        reflectMatrix.postTranslate(-engine.midX, -engine.midY)
        reflectMatrix.postRotate(-engine.foldAngleDeg + 90f)
        reflectMatrix.postScale(-1f, 1f)
        reflectMatrix.postRotate(engine.foldAngleDeg - 90f)
        reflectMatrix.postTranslate(engine.midX, engine.midY)

        flapPath.reset()
        revealedPath.transform(reflectMatrix, flapPath)

        canvas.save()
        canvas.clipPath(flapPath)

        // 4a. Warm off-white paper base
        canvas.drawPath(flapPath, paperPaint)

        // 4b. Translucent mirrored comic artwork showing through the paper
        canvas.drawBitmap(curBmp, reflectMatrix, backPagePaint)

        // 4c. Soft natural crease shadow along the fold bend (NOT a cylinder)
        val creaseDist = 36f
        creasePath.reset()
        creasePath.moveTo(fxT, pT)
        creasePath.lineTo(fxT - normX * creaseDist, pT - normY * creaseDist)
        creasePath.lineTo(fxB - normX * creaseDist, pB - normY * creaseDist)
        creasePath.lineTo(fxB, pB)
        creasePath.close()

        creasePaint.shader = LinearGradient(
            engine.midX,
            engine.midY,
            engine.midX - normX * creaseDist,
            engine.midY - normY * creaseDist,
            Color.argb(80, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(creasePath, creasePaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        currentBitmap?.recycle()
        currentBitmap = null
        nextBitmap?.recycle()
        nextBitmap = null
        velocityTracker?.recycle()
        velocityTracker = null
        soundPool?.release()
        soundPool = null
        soundLoaded = false
    }
}
