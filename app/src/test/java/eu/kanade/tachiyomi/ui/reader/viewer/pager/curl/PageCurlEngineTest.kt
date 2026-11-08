package eu.kanade.tachiyomi.ui.reader.viewer.pager.curl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PageCurlEngineTest {

    private lateinit var engine: PageCurlEngine
    private val left = 0f
    private val top = 400f
    private val right = 1080f
    private val bottom = 2000f

    @BeforeEach
    fun setUp() {
        engine = PageCurlEngine()
        engine.setup(left, top, right, bottom)
    }

    @Test
    fun `initial setup creates correct page dimensions`() {
        assertEquals(1080f, engine.pageWidth)
        assertEquals(1600f, engine.pageHeight)
        assertEquals(left, engine.pageLeft)
        assertEquals(top, engine.pageTop)
        assertEquals(right, engine.pageRight)
        assertEquals(bottom, engine.pageBottom)
    }

    @Test
    fun `curl from right moves fold line from right to left with natural corner tilt`() {
        // Touch near top of page (e.g. Y = 600, top = 400)
        engine.computeCurl(progress = 0.4f, touchY = 600f, fromRight = true)

        // Fold line should be inside the page bounds
        assertTrue(engine.foldTopX < right, "foldTopX should have moved left of right edge")
        assertTrue(engine.foldBottomX < right, "foldBottomX should have moved left of right edge")

        // Touching near top means top corner curls ahead of bottom corner (foldTopX < foldBottomX)
        assertTrue(engine.foldTopX < engine.foldBottomX, "Top fold should be ahead of bottom fold for top touch")

        // Normal vector points to the right towards the revealed page
        assertTrue(engine.normalX > 0f, "normalX should point right for fromRight curl")
        assertTrue(engine.shadowEndX > engine.shadowStartX, "Drop shadow should extend rightward onto revealed page")
    }

    @Test
    fun `curl from left moves fold line from left to right with natural corner tilt`() {
        // Touch near top of page
        engine.computeCurl(progress = 0.4f, touchY = 600f, fromRight = false)

        assertTrue(engine.foldTopX > left, "foldTopX should have moved right of left edge")
        assertTrue(engine.foldBottomX > left, "foldBottomX should have moved right of left edge")

        // Touching near top means top corner curls ahead of bottom corner (foldTopX > foldBottomX)
        assertTrue(engine.foldTopX > engine.foldBottomX, "Top fold should be ahead of bottom fold for left-to-right curl")

        // Normal vector points to the left towards the revealed page
        assertTrue(engine.normalX < 0f, "normalX should point left for fromLeft curl")
        assertTrue(engine.shadowEndX < engine.shadowStartX, "Drop shadow should extend leftward onto revealed page")
    }

    @Test
    fun `extreme progress and touch values do not produce NaN or infinite coordinates`() {
        val testProgresses = floatArrayOf(-0.5f, 0f, 0.01f, 0.5f, 0.99f, 1f, 1.5f)
        val testTouches = floatArrayOf(-500f, 0f, 400f, 1000f, 2000f, 3000f)

        for (p in testProgresses) {
            for (t in testTouches) {
                engine.computeCurl(progress = p, touchY = t, fromRight = true)
                assertFalse(engine.foldTopX.isNaN(), "foldTopX was NaN for p=$p, t=$t")
                assertFalse(engine.foldTopX.isInfinite(), "foldTopX was Infinite for p=$p, t=$t")
                assertFalse(engine.foldBottomX.isNaN(), "foldBottomX was NaN for p=$p, t=$t")
                assertFalse(engine.foldBottomX.isInfinite(), "foldBottomX was Infinite for p=$p, t=$t")
                assertFalse(engine.normalX.isNaN(), "normalX was NaN for p=$p, t=$t")
                assertFalse(engine.normalY.isNaN(), "normalY was NaN for p=$p, t=$t")
                assertFalse(engine.shadowEndX.isNaN(), "shadowEndX was NaN for p=$p, t=$t")
                assertFalse(engine.rollEndX.isNaN(), "rollEndX was NaN for p=$p, t=$t")
            }
        }
    }
}
