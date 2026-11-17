package com.thedavelopers.eventqr.ui.components

import android.app.Activity
import android.os.Parcelable
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Pull-to-refresh contract for a screen with its own in-flow strip (§6.8): the stock disc is
 * suppressed, the state callback fires once per real change, a null listener stays null rather
 * than installing a wrapper, and the opt-in pull callback grows with the finger and clears on
 * release or cancel.
 *
 * The disc is found by type, never by class name and never by position: R8 rewrites
 * `CircleImageView` to a short name in release builds, and the library calls `bringToFront()` on
 * the disc on every touch down, so child 0 is the content child after the first touch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventQrSwipeRefreshLayoutTest {

    private var eventTime = 0L

    private fun attach(withStrip: Boolean): EventQrSwipeRefreshLayout {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_EventQR) }
        val swipe = EventQrSwipeRefreshLayout(activity)
        if (withStrip) swipe.onRefreshStateChanged = {}
        // A content child, as every real host has: the library reads it for canChildScrollUp().
        swipe.addView(View(activity))
        activity.setContentView(swipe)
        controller.create().start().resume().visible()
        return swipe
    }

    private fun contentChild(swipe: SwipeRefreshLayout): View =
        (0 until swipe.childCount).map(swipe::getChildAt).first { it !is ImageView }

    private fun discChild(swipe: SwipeRefreshLayout): ImageView =
        (0 until swipe.childCount).map(swipe::getChildAt).first { it is ImageView } as ImageView

    private fun touch(swipe: EventQrSwipeRefreshLayout, action: Int, y: Float) {
        val event = MotionEvent.obtain(0L, eventTime, action, 100f, y, 0)
        swipe.dispatchTouchEvent(event)
        event.recycle()
        eventTime += 10L
    }

    /**
     * One real gesture, start to finish. Robolectric needs no looper: the library's drag math runs
     * inside dispatch. Default mdpi here means the 64dp trigger is 64px and the pull is half the
     * finger travel past an 8px slop.
     */
    private fun dispatchDrag(
        swipe: EventQrSwipeRefreshLayout,
        downY: Float,
        moves: List<Float>,
        end: Int,
    ) {
        touch(swipe, MotionEvent.ACTION_DOWN, downY)
        moves.forEach { touch(swipe, MotionEvent.ACTION_MOVE, it) }
        touch(swipe, end, moves.last())
    }

    /** The library exposes no getter, and the wrapper it stores is the unit under test. */
    private fun installedListener(swipe: SwipeRefreshLayout): SwipeRefreshLayout.OnRefreshListener? {
        val field = SwipeRefreshLayout::class.java.getDeclaredField("mListener")
        field.isAccessible = true
        return field.get(swipe) as SwipeRefreshLayout.OnRefreshListener?
    }

    @Test
    fun withAStrip_theStockDiscDoesNotPaint() {
        // Found by type: ImageView is a framework supertype, so R8 can never rename it away.
        val disc = discChild(attach(withStrip = true))
        assertEquals(0f, disc.alpha, 0f)
        assertEquals(0f, disc.elevation, 0f)
    }

    @Test
    fun aRestoredRefreshFlag_isDroppedWithItsStrip() {
        val old = attach(withStrip = true).apply {
            id = 4242
            isRefreshing = true
        }
        val state = SparseArray<Parcelable>().apply { old.saveHierarchyState(this) }

        val seen = mutableListOf<Boolean>()
        val swipe = attach(withStrip = true).apply {
            id = 4242
            onRefreshStateChanged = { seen += it }
        }
        swipe.restoreHierarchyState(state)

        assertFalse(swipe.isRefreshing)
        // The strip must be told, not just the field: a silent clear would leave it painted.
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun withoutAStrip_theStockDiscIsLeftAlone() {
        val disc = discChild(attach(withStrip = false))
        assertEquals(1f, disc.alpha, 0f)
        assertTrue("stock elevation ${disc.elevation}", disc.elevation > 0f)
    }

    @Test
    fun stateIsReportedOncePerRealChange() {
        val swipe = attach(withStrip = true)
        val seen = mutableListOf<Boolean>()
        swipe.onRefreshStateChanged = { seen += it }

        swipe.isRefreshing = true
        swipe.isRefreshing = true
        swipe.isRefreshing = false

        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun nullListener_isNotWrapped() {
        val swipe = attach(withStrip = true)
        swipe.setOnRefreshListener(null)
        assertNull(installedListener(swipe))
    }

    @Test
    fun listenerStillReachesTheScreen() {
        val swipe = attach(withStrip = false)
        var calls = 0
        swipe.setOnRefreshListener { calls++ }

        installedListener(swipe)?.onRefresh()

        assertEquals(1, calls)
    }

    @Test
    fun listenerStartsTheStrip() {
        val swipe = attach(withStrip = true)
        val seen = mutableListOf<Boolean>()
        swipe.onRefreshStateChanged = { seen += it }
        swipe.setOnRefreshListener { }

        installedListener(swipe)?.onRefresh()

        assertEquals(listOf(true), seen)
    }

    @Test
    fun aPullReportsGrowingFractions_andReleaseHandsOverToTheRefreshState() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        val states = mutableListOf<Boolean>()
        var refreshes = 0
        swipe.onPullProgress = { pulls += it }
        swipe.onRefreshStateChanged = { states += it }
        swipe.setOnRefreshListener { refreshes++ }

        dispatchDrag(
            swipe,
            downY = 10f,
            moves = listOf(40f, 80f, 120f, 160f, 200f),
            end = MotionEvent.ACTION_UP,
        )

        val fractions = pulls.filterNotNull()
        assertTrue("no pull was reported: $pulls", fractions.size >= 3)
        assertEquals("fractions must grow with the finger: $fractions", fractions.sorted(), fractions)
        assertTrue("a pull past the trigger must reach 1f: $fractions", fractions.last() >= 1f)
        // Past the trigger the library owns the gesture: the fraction clears instead of spinning here.
        assertNull("a released pull must clear the fraction: $pulls", pulls.last())
        assertTrue("the library never armed the refresh", swipe.isRefreshing)

        // The library delivers onRefresh from its own offset animation, which Robolectric does not
        // run; the installed listener is the same object it calls there.
        installedListener(swipe)?.onRefresh()
        assertEquals(listOf(true), states)
        assertEquals(1, refreshes)

        swipe.isRefreshing = false
        assertEquals("the strip must be told the refresh ended", listOf(true, false), states)
    }

    @Test
    fun aCancelledPullClearsTheStrip_andNeverStartsARefresh() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        val states = mutableListOf<Boolean>()
        var refreshes = 0
        swipe.onPullProgress = { pulls += it }
        swipe.onRefreshStateChanged = { states += it }
        swipe.setOnRefreshListener { refreshes++ }

        dispatchDrag(
            swipe,
            downY = 10f,
            moves = listOf(40f, 80f),
            end = MotionEvent.ACTION_CANCEL,
        )

        assertTrue("the pull must paint before it is cancelled: $pulls", pulls.filterNotNull().isNotEmpty())
        assertNull("a cancelled pull must clear the fraction: $pulls", pulls.last())
        assertFalse(swipe.isRefreshing)
        assertEquals(0, refreshes)
        assertTrue("a cancelled pull must not start the refresh state: $states", states.isEmpty())
    }

    @Test
    fun anArmedPull_clearsTheStripOnlyAfterTheRefreshIsArmed() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        var refreshingAtClear: Boolean? = null
        swipe.onPullProgress = { fraction ->
            pulls += fraction
            if (fraction == null) refreshingAtClear = swipe.isRefreshing
        }
        swipe.setOnRefreshListener { }

        dispatchDrag(
            swipe,
            downY = 10f,
            moves = listOf(40f, 120f, 200f),
            end = MotionEvent.ACTION_UP,
        )

        assertTrue("the pull never armed the refresh", swipe.isRefreshing)
        // 1.1.0 arms the refresh inside super on the release dispatch, so the null callback must
        // run after it: seeing false here would hide the strip and repaint it a moment later.
        assertEquals(true, refreshingAtClear)
        // The clear is the single trailing event. A null reported before super would be followed by
        // more fractions, and a second null would tear the strip down twice - both read here.
        assertNull("the release must end the pull sequence: $pulls", pulls.last())
        assertEquals("the clear must be the only one: $pulls", 1, pulls.count { it == null })
    }

    @Test
    fun aSubThresholdPull_clearsWithNoRefreshArmed() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        var refreshingAtClear: Boolean? = null
        swipe.onPullProgress = { fraction ->
            pulls += fraction
            if (fraction == null) refreshingAtClear = swipe.isRefreshing
        }
        swipe.setOnRefreshListener { }

        dispatchDrag(
            swipe,
            downY = 10f,
            moves = listOf(30f, 50f),
            end = MotionEvent.ACTION_UP,
        )

        assertTrue("the pull never painted", pulls.filterNotNull().isNotEmpty())
        assertNull("a released pull must clear the fraction: $pulls", pulls.last())
        assertFalse(swipe.isRefreshing)
        assertEquals(false, refreshingAtClear)
    }

    @Test
    fun aCallbackInstalledAfterATouch_hidesTheDiscNotTheContent() {
        val swipe = attach(withStrip = false)
        // The library's ACTION_DOWN calls bringToFront() on the disc, so child 0 is the content.
        touch(swipe, MotionEvent.ACTION_DOWN, 10f)
        touch(swipe, MotionEvent.ACTION_UP, 10f)
        assertTrue(contentChild(swipe) === swipe.getChildAt(0))

        swipe.onRefreshStateChanged = {}

        assertEquals(0f, discChild(swipe).alpha, 0f)
        assertEquals(1f, contentChild(swipe).alpha, 0f)
    }

    @Test
    fun aNestedScrollPull_readsRawTravel_andClearsWithTheArmedRefresh() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        val states = mutableListOf<Boolean>()
        var refreshingAtClear: Boolean? = null
        swipe.onPullProgress = { fraction ->
            pulls += fraction
            if (fraction == null) refreshingAtClear = swipe.isRefreshing
        }
        swipe.onRefreshStateChanged = { states += it }
        swipe.setOnRefreshListener { }
        val target = contentChild(swipe)
        val trigger = swipe.resources.getDimensionPixelSize(R.dimen.ui_refresh_pull_trigger).toFloat()

        touch(swipe, MotionEvent.ACTION_DOWN, 10f)
        assertTrue(swipe.onStartNestedScroll(target, target, ViewCompat.SCROLL_AXIS_VERTICAL))
        swipe.onNestedScrollAccepted(target, target, ViewCompat.SCROLL_AXIS_VERTICAL)

        // A scroll up at the top is not a pull: it must report no fraction at all, not a coerced 0f
        // that paints the strip's empty hairline for a frame.
        touch(swipe, MotionEvent.ACTION_MOVE, 4f)
        swipe.onNestedScroll(target, 0, 0, 0, 6)
        assertTrue("an upward scroll at the top must not paint a pull: $pulls", pulls.isEmpty())
        assertTrue("an upward scroll at the top must not show the strip: $states", states.isEmpty())

        // 32px of finger travel is half the trigger at mdpi; the touch path would halve that again
        // past the slop.
        touch(swipe, MotionEvent.ACTION_MOVE, 42f)
        swipe.onNestedScroll(target, 0, 0, 0, -40)
        touch(swipe, MotionEvent.ACTION_MOVE, 90f)
        swipe.onNestedScroll(target, 0, 0, 0, -40)
        // 80px of raw nested travel is past the trigger at mdpi, so the library arms its refresh.
        swipe.onStopNestedScroll(target)

        assertTrue("the nested pull never armed the refresh", swipe.isRefreshing)
        // Raw travel over the trigger, coerced at 1f: past it the library owns the gesture.
        assertEquals(
            listOf<Float?>(32f / trigger, (80f / trigger).coerceIn(0f, 1f), null),
            pulls,
        )
        assertEquals(true, refreshingAtClear)
    }

    @Test
    fun aNewGesture_cannotInheritTheLastGesturesNestedMode() {
        val swipe = attach(withStrip = true)
        val pulls = mutableListOf<Float?>()
        swipe.onPullProgress = { fraction -> pulls += fraction }
        val target = contentChild(swipe)

        // A child starts a nested scroll and never reports its stop, so the flag would stay set.
        touch(swipe, MotionEvent.ACTION_DOWN, 10f)
        assertTrue(swipe.onStartNestedScroll(target, target, ViewCompat.SCROLL_AXIS_VERTICAL))
        swipe.onNestedScrollAccepted(target, target, ViewCompat.SCROLL_AXIS_VERTICAL)

        // The next gesture is a plain touch drag: past the slop the touch path halves the travel, so
        // the same 32px can never report the nested 0.5f. The exact fraction depends on the device's
        // touch slop; a stale nested flag would double it.
        dispatchDrag(swipe, downY = 10f, moves = listOf(42f), end = MotionEvent.ACTION_CANCEL)

        val slop = ViewConfiguration.get(swipe.context).scaledTouchSlop.toFloat()
        val trigger = swipe.resources.getDimensionPixelSize(R.dimen.ui_refresh_pull_trigger).toFloat()
        assertEquals(listOf<Float?>((42f - 10f - slop) * 0.5f / trigger, null), pulls)
    }

    @Test
    fun withoutAPullCallback_theDragNeverReports_andTheStockDiscIsLeftAlone() {
        val swipe = attach(withStrip = false)
        // Not opted in: there is nothing to report to, and the stock spinner is still the cue.
        assertNull(swipe.onPullProgress)

        dispatchDrag(
            swipe,
            downY = 10f,
            moves = listOf(40f, 80f, 120f),
            end = MotionEvent.ACTION_UP,
        )

        // The library brings the disc to the front on ACTION_DOWN, so find it by type, not index.
        val disc = discChild(swipe)
        assertEquals(1f, disc.alpha, 0f)
        assertTrue("stock elevation ${disc.elevation}", disc.elevation > 0f)
        assertFalse("a short drag must not arm a refresh", swipe.isRefreshing)
    }
}
