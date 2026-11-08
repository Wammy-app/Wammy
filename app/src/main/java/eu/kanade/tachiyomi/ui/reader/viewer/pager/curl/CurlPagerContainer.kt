package eu.kanade.tachiyomi.ui.reader.viewer.pager.curl

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer

/**
 * Container holding the ViewPager and PageCurlOverlay, routing touches when page curl is active.
 */
class CurlPagerContainer(context: Context) : FrameLayout(context) {

    var curlOverlay: PageCurlOverlay? = null
    var viewer: PagerViewer? = null

    val isCurlActive: Boolean
        get() = viewer?.isCurlTransitionEnabled() == true

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isCurlActive) return false
        val overlay = curlOverlay ?: return false
        return overlay.shouldInterceptTouch(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isCurlActive) return super.onTouchEvent(ev)
        val overlay = curlOverlay ?: return super.onTouchEvent(ev)
        if (overlay.isCurling) {
            return overlay.onTouchEvent(ev)
        }
        return super.onTouchEvent(ev)
    }
}
