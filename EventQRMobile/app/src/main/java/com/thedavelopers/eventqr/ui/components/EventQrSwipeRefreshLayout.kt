package com.thedavelopers.eventqr.ui.components

import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thedavelopers.eventqr.R

/*
 * The one pull-to-refresh (§5.11).
 *
 * No raised disc on a screen that supplies [onRefreshStateChanged]: the stock disc is suppressed
 * outright there, because it carries a 4dp elevation shadow and §9.6 #8 fails any drop shadow on
 * content. The in-progress state is then the screen's own in-flow strip (§6.8). A screen without a
 * strip keeps the stock spinner, so the suppression is opt-in and no legacy surface is left with an
 * unmarked refresh.
 *
 * A screen that also supplies [onPullProgress] gets the drag itself, so its strip has an affordance
 * before the finger lifts: the fraction grows with the pull, reaches 1f at the refresh trigger and
 * clears on release or cancel. Observation only - every touch keeps flowing to the library.
 *
 * Placement rule unchanged: it wraps only the content BELOW the header, never the header itself, so
 * the head stays put.
 *
 * The skeleton that shows while it spins must match the loaded geometry within 4dp (§5.11).
 */
class EventQrSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    /**
     * Set this to paint the screen's own in-flow progress strip: `true` when a refresh starts,
     * `false` when it stops. Setting it also suppresses the stock disc; a screen that leaves it
     * null keeps the spinner it has today.
     */
    var onRefreshStateChanged: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) hideStockSpinner()
        }

    /**
     * Opt-in pull distance for that same strip: `0f`..`1f` while a finger drags the content down,
     * `null` once the drag ends (released or cancelled). `1f` is the refresh trigger, so the screen
     * can paint the pull determinate and hand over to its indeterminate refresh state. A screen
     * that leaves this null never sees a pull event and keeps the library's stock behaviour.
     */
    var onPullProgress: ((Float?) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) {
                finishPull()
            } else {
                // The library exposes no getter for its trigger distance, so an opt-in screen pins
                // it to our token and the fraction below is exact. The token is the 1.1.0 default,
                // so the gesture a finger has to make does not change.
                setDistanceToTriggerSync(pullTriggerPx)
            }
        }

    /** Last state handed to [onRefreshStateChanged], so a repeated set does not repaint. */
    private var reportedRefreshing = false

    /**
     * The library's own disc, captured once at construction. `createProgressView()` runs inside the
     * super constructor and adds it before any subclass initializer, so it is there to capture - and
     * finding it once is the only reliable way to keep it: position is not stable (`bringToFront()`
     * makes child 0 the content after the first touch down) and the class name is not either (R8
     * rewrites `CircleImageView`, while the framework [ImageView] supertype it extends survives).
     * Holding the reference also means a host that adds its own ImageView child can never be faded.
     */
    private val stockDisc: ImageView? =
        (0 until childCount).map(::getChildAt).firstOrNull { it is ImageView } as? ImageView

    private val pullSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val pullTriggerPx = resources.getDimensionPixelSize(R.dimen.ui_refresh_pull_trigger)

    /** `null` until a drag starts, then the last fraction handed to [onPullProgress]. */
    private var reportedPull: Float? = null
    private var pullTracking = false
    private var pullDownY = 0f
    private var pullStartY = 0f

    /**
     * True from [onStartNestedScroll] until [onStopNestedScroll]. The library has two drag paths: a
     * nested-scrolling content child (`RecyclerView`) feeds raw finger travel to its trigger, while
     * the touch path scales travel by [PULL_DRAG_RATE]. The fraction below has to know which one is
     * live, or a nested drag would read half what the library actually measured.
     */
    private var nestedPull = false

    init {
        setColorSchemeColors(ContextCompat.getColor(context, R.color.ui_primary))
        setProgressBackgroundColorSchemeColor(ContextCompat.getColor(context, R.color.ui_surface))
        setProgressViewOffset(
            false,
            resources.getDimensionPixelOffset(R.dimen.space_sm),
            resources.getDimensionPixelOffset(R.dimen.space_xxxl),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (onRefreshStateChanged != null) hideStockSpinner()
    }

    /**
     * Reads a non-terminal event before the library so the fraction leads the finger by no more
     * than one event, and a terminal one after it, because the library only publishes its
     * post-release state inside `super`: `finishSpinner()` arms `isRefreshing` in the same dispatch
     * that delivers ACTION_UP. Observing the release afterwards keeps the strip at 100% through the
     * disc's ~200ms arm animation, instead of hiding it (the null callback ran while `isRefreshing`
     * was still false) and painting it again when the refresh listener finally fires.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            val handled = super.dispatchTouchEvent(ev)
            finishPull()
            return handled
        }
        observePull(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The library's typed NestedScrollingParent2/3 overrides delegate the touch type here, so one
     * override covers both. Only an accepted scroll switches the drag to the nested path.
     */
    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        val accepted = super.onStartNestedScroll(child, target, nestedScrollAxes)
        if (accepted) nestedPull = true
        return accepted
    }

    /**
     * Runs inside the child's terminal dispatch, so the library has already armed the refresh from
     * its own `mTotalUnconsumed` when this clears the pull. The typed override delegates here too.
     */
    override fun onStopNestedScroll(target: View) {
        super.onStopNestedScroll(target)
        nestedPull = false
        finishPull()
    }

    override fun setOnRefreshListener(listener: OnRefreshListener?) {
        // Null stays null: wrapping it would report a refresh the screen never listens for.
        if (listener == null) {
            super.setOnRefreshListener(null)
            return
        }
        super.setOnRefreshListener(
            OnRefreshListener {
                // The gesture path sets mRefreshing without going through setRefreshing().
                reportRefreshing(true)
                listener.onRefresh()
            },
        )
    }

    override fun setRefreshing(refreshing: Boolean) {
        super.setRefreshing(refreshing)
        reportRefreshing(refreshing)
    }

    /**
     * Disabling the view mid-drag leaves the strip painted with no finger left to clear it: the
     * library drops its own drag, so the pull is cleared here too. Safe during inflation, before the
     * fields exist: `finishPull()` sees no reported fraction and returns.
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) finishPull()
    }

    /**
     * Drops a restored refresh flag on a screen that paints its own strip. The library restores
     * `mRefreshing` through [setRefreshing] after `onCreate`, so the flag belongs to the job of the
     * destroyed instance and no live load would ever clear the strip. Screens without the callback
     * keep the stock restore behaviour.
     *
     * This assumes a strip screen restarts its load on recreation, which every current caller does.
     * A screen that carries a refresh across recreation must not use this callback.
     */
    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState(state)
        if (onRefreshStateChanged != null && isRefreshing) isRefreshing = false
    }

    /**
     * Reads the finger's pull without consuming the event: dispatch still hands it to the library
     * unchanged. The library's own gate is mirrored (disabled, already refreshing, content scrolled
     * off the top) so a list scroll can never paint a pull.
     */
    private fun observePull(ev: MotionEvent) {
        if (onPullProgress == null) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A fresh gesture never inherits the last one: a child that starts a nested scroll
                // and never reports its stop would otherwise make this touch read as a nested one
                // and double every fraction. `finishPull()` also clears a fraction left painted.
                nestedPull = false
                finishPull()
                pullDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                // One finger owns the drag: with a second one down, `ev.y` is another pointer's
                // position and the fraction would jump.
                if (ev.pointerCount > 1) {
                    pullTracking = false
                    finishPull()
                    return
                }
                // offset 0 is the library's disc; a content child exists on every real screen, and
                // canChildScrollUp() must not be asked before it does.
                val hasTarget = childCount > 1
                if (!isEnabled || isRefreshing || (hasTarget && canChildScrollUp())) {
                    finishPull()
                    return
                }
                // Upward travel is a scroll, not a pull: no finger on this screen ever pulls up, and
                // a coerced 0f would paint an empty strip for one frame on the list's most common
                // gesture. Clear whatever the last gesture left and report nothing.
                val travel = ev.y - pullDownY
                if (travel <= 0f) {
                    finishPull()
                    return
                }
                val fraction = if (nestedPull) {
                    // Raw travel: the nested path feeds `mTotalUnconsumed` straight to the trigger,
                    // with no DRAG_RATE and no touch slop.
                    travel / pullTriggerPx
                } else {
                    if (!pullTracking) {
                        if (travel <= pullSlop) return
                        pullTracking = true
                        pullStartY = pullDownY + pullSlop
                    }
                    (ev.y - pullStartY) * PULL_DRAG_RATE / pullTriggerPx
                }
                reportPull(fraction.coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                pullTracking = false
                finishPull()
            }
        }
    }

    /** Fires [onPullProgress] only when the fraction moves, so a repaint is never doubled. */
    private fun reportPull(fraction: Float) {
        if (reportedPull == fraction) return
        reportedPull = fraction
        onPullProgress?.invoke(fraction)
    }

    /** Ends a drag: a released, cancelled or superseded pull must leave no strip painted. */
    private fun finishPull() {
        pullTracking = false
        if (reportedPull == null) return
        reportedPull = null
        onPullProgress?.invoke(null)
    }

    /** Fires [onRefreshStateChanged] only when the state moves, so a repaint is never doubled. */
    private fun reportRefreshing(refreshing: Boolean) {
        if (reportedRefreshing == refreshing) return
        reportedRefreshing = refreshing
        onRefreshStateChanged?.invoke(refreshing)
    }

    /**
     * Stops the library's disc from drawing without touching its geometry: the pull math and the
     * target offset still use the disc's measured size, only the paint stops. Only the captured disc
     * is hidden, so no content child can vanish with it.
     */
    private fun hideStockSpinner() {
        val disc = stockDisc ?: return
        disc.elevation = 0f
        disc.alpha = 0f
    }

    private companion object {
        /** SwipeRefreshLayout 1.1.0 turns finger travel into pull at half distance (DRAG_RATE). */
        const val PULL_DRAG_RATE = 0.5f
    }
}
