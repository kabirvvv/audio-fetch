package com.audiofetch

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the Home / Search / Library tab content and lets the user swipe
 * left or right anywhere on the page to change tabs — WITHOUT hijacking
 * horizontal drags meant for nested content (home shelf rows, mood chips,
 * any horizontally-scrolling RecyclerView).
 *
 * How the conflict is avoided:
 * A RecyclerView claims a horizontal drag at Android's normal touch-slop
 * (~8dp) and immediately calls requestDisallowInterceptTouchEvent(true) on
 * every ancestor, including this view. This container deliberately waits
 * for a MUCH larger horizontal movement (touchSlop) before it decides to
 * intercept anything. So for any gesture that starts on a horizontally
 * scrollable child, that child claims the drag well before this container's
 * own threshold is reached, and this container is told to back off and
 * never intercepts. Only gestures starting on empty background, vertical
 * scroll content, or anywhere a horizontal RecyclerView doesn't claim the
 * drag ever reach this container's own tab-swipe handling.
 */
class SwipeNavContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    // Deliberately larger than a RecyclerView's own touch slop so nested
    // horizontal scrolling always wins the gesture first.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 3.5f
    private val minFlingVelocity = 300 * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var intercepting = false
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        intercepting = true
                        velocityTracker = VelocityTracker.obtain()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
            }
        }
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val dx = ev.x - downX
                    if (abs(dx) > touchSlop && abs(tracker.xVelocity) > minFlingVelocity) {
                        if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    }
                    tracker.recycle()
                }
                velocityTracker = null
                intercepting = false
            }
        }
        return true
    }
}
