package app.lawnchair.hotseat

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import com.android.launcher3.BubbleTextView
import com.android.launcher3.CellLayout
import com.android.launcher3.DeviceProfile
import com.android.launcher3.IconGestureTouchTracker
import com.android.launcher3.PagedView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.Workspace
import com.android.launcher3.pageindicators.PageIndicatorDots

/**
 * Horizontally pageable container for dock [CellLayout] pages.
 */
class HotseatPagedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : PagedView<PageIndicatorDots>(context, attrs, defStyle) {

    fun interface OnDockPageChangeListener {
        fun onDockPageChanged(page: Int)
    }

    var isPagingEnabled: Boolean = false
        private set(value) {
            field = value
            applyPageIndicatorVisibility()
        }

    private var onDockPageChangeListener: OnDockPageChangeListener? = null
    private val iconGestureTouchTracker = IconGestureTouchTracker(context)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPageIndicator(indicator: PageIndicatorDots?) {
        mPageIndicator = indicator
        mPageIndicator?.let {
            it.setMarkersCount(childCount)
            it.setActiveMarker(nextPage)
        }
        applyPageIndicatorVisibility()
    }

    private fun applyPageIndicatorVisibility() {
        val indicator = mPageIndicator ?: return
        if (isPagingEnabled) {
            indicator.visibility = VISIBLE
            // Same as workspace: show while paging, fade out when idle.
            indicator.setShouldAutoHide(true)
        } else {
            indicator.setShouldAutoHide(false)
            indicator.visibility = GONE
        }
    }

    override fun getPageAt(index: Int): CellLayout? = getChildAt(index) as? CellLayout

    fun getCurrentCellLayout(): CellLayout? = getPageAt(nextPage)

    fun resetPages(hasVerticalHotseat: Boolean, workspace: Workspace<*>?, dp: DeviceProfile) {
        removeAllViews()
        val pageCount = if (hasVerticalHotseat) 1 else maxOf(1, dp.numHotseatPages)
        isPagingEnabled = pageCount > 1

        val inflater = LayoutInflater.from(context)
        for (i in 0 until pageCount) {
            val page = inflater.inflate(R.layout.hotseat_page, this, false) as CellLayout
            page.setHotseatPageIndex(i)
            if (workspace != null) {
                page.setCellLayoutContainer(workspace)
            }
            page.resetCellSize(dp)
            page.isLongClickable = false
            page.isHapticFeedbackEnabled = false
            if (hasVerticalHotseat) {
                page.setGridSize(1, dp.numShownHotseatIcons)
            } else {
                page.setGridSize(dp.numShownHotseatIcons, dp.numHotseatRows)
            }
            addView(page, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        mPageIndicator?.let {
            it.setMarkersCount(pageCount)
            it.setActiveMarker(0)
        }
        applyPageIndicatorVisibility()
        setCurrentPage(0)
        requestLayout()
    }

    fun setOnDockPageChangeListener(listener: OnDockPageChangeListener?) {
        onDockPageChangeListener = listener
    }

    override fun onPageEndTransition() {
        super.onPageEndTransition()
        onDockPageChangeListener?.onDockPageChanged(nextPage)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (mMaxScroll > 0) {
            mPageIndicator?.setScroll(l, mMaxScroll)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isPagingEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> iconGestureTouchTracker.onTouchDown(ev, findIconAtPosition(ev.x, ev.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> iconGestureTouchTracker.reset()
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun determineScrollingStart(ev: MotionEvent) {
        if (!iconGestureTouchTracker.isMovingTowardConfiguredIconGesture(ev)) {
            super.determineScrollingStart(ev)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isPagingEnabled) return false
        return super.onTouchEvent(ev)
    }

    /** Returns the page CellLayout under the given x in this view's coordinates. */
    fun findPageAtLocalX(x: Float): CellLayout? {
        val width = measuredWidth
        if (width <= 0 || childCount == 0) {
            return getCurrentCellLayout()
        }
        var raw = (scrollX + x).toInt() / width
        if (mIsRtl) {
            raw = childCount - 1 - raw
        }
        val page = raw.coerceIn(0, childCount - 1)
        return getPageAt(page)
    }

    fun findIconAtPosition(x: Float, y: Float): BubbleTextView? {
        if (!Utilities.pointInView(this, x, y, 0f)) return null
        val page = findPageAtLocalX(x) ?: return null
        val pageX = x + scrollX - page.left
        val pageY = y + scrollY - page.top
        if (!Utilities.pointInView(page, pageX, pageY, 0f)) return null
        return Workspace.findIconInCellLayout(page, pageX, pageY)
    }
}
