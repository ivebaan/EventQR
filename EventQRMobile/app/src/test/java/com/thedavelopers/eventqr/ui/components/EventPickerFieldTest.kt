package com.thedavelopers.eventqr.ui.components

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The shared event picker's states and interactions (plan 5.2 "Event picker"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventPickerFieldTest {

    private lateinit var activity: Activity
    private lateinit var picker: EventPickerField
    private lateinit var input: EventPickerInput

    private val options = listOf(
        EventPickerOption("1", "Night Market", "12 Nov 2026"),
        EventPickerOption("2", "Day Fair", "13 Nov 2026"),
    )

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        picker = EventPickerField(activity)
        // The host sits at the top of the screen, so the popup has room to drop under it.
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(picker, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        activity.setContentView(container)
        input = picker.findViewById(R.id.eventPickerInput)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

    private fun adapter(): BaseAdapter = input.adapter as BaseAdapter

    private val popupField: ListPopupWindow
        get() = AutoCompleteTextView::class.java
            .getDeclaredField("mPopup")
            .apply { isAccessible = true }
            .get(input) as ListPopupWindow

    private fun open(): Unit {
        picker.setOptions(options, "1")
        // The first layout has to run before the tap: the popup reads the host's width and the row
        // heights, which are zero until the decor is measured.
        shadowOf(Looper.getMainLooper()).idle()
        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The row tap a user makes: the popup's own click path, which runs the framework's completion
     * (`replaceText`, then the item click listener) rather than the listener on its own.
     */
    private fun tapRow(position: Int) {
        require(popupField.performItemClick(position)) { "the picker's popup is not showing" }
    }

    /** The popup's own ListView, built on demand if the popup has not materialised it yet. */
    private fun popupListView(): ListView {
        val popup = popupField
        if (popup.listView == null) {
            popup.show()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return requireNotNull(popup.listView) { "the picker's popup did not build its list" }
    }

    /** The popup list has no size detached from a window: give it one so taps can be hit tested. */
    private fun layoutPopupList(listView: ListView) {
        val width = 600
        val probe = adapter().getView(0, null, listView)
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = probe.measuredHeight * adapter().count
        listView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        listView.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun preselectedId_showsTheTitleAndTheMetaLine() {
        // Detached from the window, so the field is at rest (no focus stealing the meta line away).
        val resting = EventPickerField(androidx.appcompat.view.ContextThemeWrapper(activity, R.style.Theme_EventQR))
        resting.setOptions(options, "1")

        val restingInput = resting.findViewById<EventPickerInput>(R.id.eventPickerInput)
        assertEquals("Night Market", restingInput.text.toString())
        val meta = resting.findViewById<TextView>(R.id.eventPickerMeta)
        assertEquals("12 Nov 2026", meta.text.toString())
        assertEquals(View.VISIBLE, meta.visibility)
    }

    @Test
    fun tapOpensTheFullList() {
        open()

        assertTrue(input.isPopupShowing)
        assertEquals(options.size, adapter().count)
        // Keyboard-first: the query starts empty so the whole list is typeable; the chosen event
        // moves to the hint instead of sitting in the text.
        assertEquals("", input.text.toString())
        assertEquals("Night Market", input.hint.toString())
    }

    @Test
    fun tapOnTheField_focusesIt_andRaisesTheKeyboard() {
        open()

        assertTrue("the field must take focus so the IME stays up", input.hasFocus())
        assertTrue("the field must hand over to the keyboard", input.showSoftInputOnFocus)
        assertEquals("Night Market", input.stateDescription)
    }

    @Test
    fun typing_filtersTheRowsByName() {
        open()

        input.setText("night")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, adapter().count)
        assertEquals("Night Market", (adapter().getItem(0) as EventPickerOption).title)
    }

    @Test
    fun rowClick_returnsTheId_restoresTheText_andDismisses() {
        var selected: String? = null
        picker.setOnEventSelectedListener { selected = it }
        open()

        input.onItemClickListener.onItemClick(null, null, 1, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2", selected)
        assertEquals("Day Fair", input.text.toString())
        assertFalse(input.isPopupShowing)
    }

    @Test
    fun tappingASecondRow_viaTheFrameworkCompletionPath_selectsIt() {
        var selected: String? = null
        picker.setOnEventSelectedListener { selected = it }
        open()
        // A tap always lands on a laid-out row; the completion path resolves the position from it.
        layoutPopupList(popupListView())

        tapRow(1)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2", selected)
        assertEquals("Day Fair", input.text.toString())
        assertFalse(input.isPopupShowing)
    }

    @Test
    fun tappingASecondRow_withARealTouch_selectsIt() {
        var selected: String? = null
        picker.setOnEventSelectedListener { selected = it }
        open()

        val listView = popupListView()
        layoutPopupList(listView)

        val firstRow = requireNotNull(listView.getChildAt(0)) { "the popup list laid out no rows" }
        val node = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(firstRow, node)
        val actions = node.actionList.map { it.id }
        node.recycle()
        assertTrue(
            "a real row must offer ACTION_CLICK to TalkBack, got actions $actions",
            actions.contains(AccessibilityNodeInfo.ACTION_CLICK),
        )

        val secondRow = requireNotNull(listView.getChildAt(1)) { "the popup list laid out one row" }
        val x = secondRow.left + secondRow.width / 2f
        val y = secondRow.top + secondRow.height / 2f
        val downTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).let {
            listView.dispatchTouchEvent(it)
            it.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).let {
            listView.dispatchTouchEvent(it)
            it.recycle()
        }
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertEquals("2", selected)
        assertEquals("Day Fair", input.text.toString())
        assertFalse(input.isPopupShowing)
    }

    @Test
    fun reopenAfterSelection_showsTheFullList() {
        open()
        input.onItemClickListener.onItemClick(null, null, 1, 0)
        shadowOf(Looper.getMainLooper()).idle()

        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Reopening clears the query to the chosen title's hint and restores every option.
        assertEquals("", input.text.toString())
        assertEquals("Day Fair", input.hint.toString())
        assertEquals(options.size, adapter().count)
        assertEquals("Night Market", (adapter().getItem(0) as EventPickerOption).title)
    }

    @Test
    fun dismissWithoutChoosing_restoresTheSelectedTitle_andNeverSelects() {
        var selected: String? = null
        picker.setOnEventSelectedListener { selected = it }
        open()
        input.setText("zzz")
        shadowOf(Looper.getMainLooper()).idle()

        popupField.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Night Market", input.text.toString())
        assertEquals("Night Market", input.hint.toString())
        assertEquals(null, selected)
    }

    @Test
    fun clear_resetsTheQuery_keepsTheSelection_andShowsTheFullList() {
        open()
        input.setText("zzz")
        shadowOf(Looper.getMainLooper()).idle()

        picker.findViewById<ImageView>(R.id.eventPickerTrailing).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("", input.text.toString())
        assertEquals("Night Market", input.hint.toString())
        assertEquals(options.size, adapter().count)
        assertEquals("Night Market", input.stateDescription)
    }

    @Test
    fun blur_revertsUnsubmittedText() {
        open()
        input.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        input.setText("zzz")
        shadowOf(Looper.getMainLooper()).idle()

        input.clearFocus()

        assertEquals("Night Market", input.text.toString())
    }

    @Test
    fun dropDownHeight_capsAtFiveRows() {
        val eight = (1..8).map { EventPickerOption("$it", "Event $it", "1${it} Nov 2026") }
        picker.setOptions(eight, "1")
        shadowOf(Looper.getMainLooper()).idle()
        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val rowMin = activity.resources.getDimensionPixelSize(R.dimen.ui_touch_min)
        assertEquals(5 * rowMin, popupField.height)
        assertEquals(8, adapter().count)
    }

    @Test
    fun dropDownHeight_wrapsToTheRowsItHas() {
        open()

        val rowMin = activity.resources.getDimensionPixelSize(R.dimen.ui_touch_min)
        assertTrue(
            "two wrapped rows must stay under the five-row cap, got ${popupField.height}",
            popupField.height < 5 * rowMin,
        )
        assertTrue("the popup must still hold both rows", popupField.height >= rowMin)
    }

    @Test
    fun dropDownWidth_matchesTheHost_andNotTheNarrowerInput() {
        open()

        assertTrue("the host must be laid out before its width is read", picker.width > 0)
        assertEquals(picker.width, popupField.width)
        assertTrue("the input is narrower than its host", picker.width > input.width)
    }

    @Test
    fun disabledField_doesNotOpen() {
        picker.setOptions(options, "1")
        picker.isEnabled = false

        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(input.isPopupShowing)
    }

    @Test
    fun noMatchRow_isNotClickable_andSaysSo() {
        open()

        input.setText("zzz")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, adapter().count)
        val row = adapter().getView(0, null, picker as ViewGroup)
        assertFalse(row.isClickable)
        assertEquals(
            activity.getString(R.string.ui_event_picker_no_match),
            row.findViewById<TextView>(R.id.eventPickerRowTitle).text.toString(),
        )
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, row.accessibilityLiveRegion)
    }

    @Test
    fun detach_dismissesThePopup() {
        open()
        assertTrue(input.isPopupShowing)

        activity.setContentView(View(activity))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(input.isPopupShowing)
    }

    @Test
    fun chosenRow_isSelected_andReadsAsAButton() {
        open()

        val row = adapter().getView(0, null, picker as ViewGroup)

        assertTrue(row.isSelected)
        assertTrue(row.isActivated)
        val info = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(row, info)
        assertEquals("android.widget.Button", info.className.toString())
        info.recycle()
    }

    @Test
    fun loadingField_showsTheLoadingCopy_andNeverOpens() {
        picker.setLoading(true)

        assertEquals(activity.getString(R.string.ui_event_picker_loading), input.text.toString())
        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(input.isPopupShowing)
    }
}
