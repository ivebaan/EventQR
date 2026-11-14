package com.thedavelopers.eventqr.ui.components

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
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
        activity.setContentView(picker)
        input = picker.findViewById(R.id.eventPickerInput)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

    private fun adapter(): BaseAdapter = input.adapter as BaseAdapter

    private fun open(): Unit {
        picker.setOptions(options, "1")
        input.performClick()
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
    fun reopenAfterSelection_showsTheFullList() {
        open()
        input.onItemClickListener.onItemClick(null, null, 1, 0)
        shadowOf(Looper.getMainLooper()).idle()

        input.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(options.size, adapter().count)
        assertEquals("Night Market", (adapter().getItem(0) as EventPickerOption).title)
    }

    @Test
    fun clear_resetsTheQuery_keepsTheSelection_andShowsTheFullList() {
        open()
        input.setText("zzz")
        shadowOf(Looper.getMainLooper()).idle()

        picker.findViewById<ImageView>(R.id.eventPickerTrailing).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("", input.text.toString())
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
