package com.thedavelopers.eventqr.features.organizer.idtemplate

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.idprinting.IdCardLayoutConfig
import com.thedavelopers.eventqr.features.idprinting.model.dto.IdTemplateConfigResponse
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

/**
 * ID Display Settings (§6.4 v1.48): the four "Show on printed ID" options are the shared
 * `Widget.EventQR.Ui.CheckBox`, not clickable rows with a fake mark and a chevron. Each option owns
 * its own toggle and repaints the live preview; the card title is a heading, the status line is a
 * polite live region, and every card on the screen is flat (no elevation).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class IdTemplateSettingsActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<IdTemplateSettingsActivity>? = null

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        ShadowLooper.idleMainLooper()
    }

    /**
     * The repository is swapped before `create()`, so the screen's own load runs against [visible]
     * and never touches the network.
     */
    private fun build(visible: List<String?> = emptyList()): IdTemplateSettingsActivity {
        val intent = Intent(context, IdTemplateSettingsActivity::class.java)
            .putExtra(EXTRA_EVENT_ID, EVENT_ID)
        val controller = Robolectric.buildActivity(IdTemplateSettingsActivity::class.java, intent)
        this.controller = controller
        val activity = controller.get()
        activity.installRepositoryFactory { ConfigRepository(it, visible) }
        controller.create().start().resume().visible()
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun togglesCard(activity: IdTemplateSettingsActivity): LinearLayout =
        activity.findViewById(R.id.idt_toggles_card)

    private fun checkBoxes(activity: IdTemplateSettingsActivity): List<CheckBox> =
        togglesCard(activity).descendants().filterIsInstance<CheckBox>()

    private fun checkBox(activity: IdTemplateSettingsActivity, label: String): CheckBox =
        checkBoxes(activity).first { it.text.toString() == label }

    private fun preview(activity: IdTemplateSettingsActivity): LinearLayout =
        activity.findViewById(R.id.idt_preview_container)

    /** A preview value renders only while its field is on; hidden fields must leave no node. */
    private fun previewHasText(activity: IdTemplateSettingsActivity, value: String): Boolean =
        preview(activity).descendants().any { it is TextView && it.text.toString() == value }

    private fun View.descendants(): List<View> {
        if (this !is ViewGroup) return emptyList()
        return (0 until childCount).flatMap { index ->
            val child = getChildAt(index)
            listOf(child) + child.descendants()
        }
    }

    @Test
    fun togglesCard_holdsExactlyTheFourOptionalFieldsAsCheckBoxes() {
        val activity = build()

        val boxes = checkBoxes(activity)

        assertEquals("one control per optional field", 4, boxes.size)
        assertEquals(
            IdCardLayoutConfig.OPTIONAL_FIELDS.map { IdCardLayoutConfig.displayName(it) },
            boxes.map { it.text.toString() },
        )
        // The row is the target: a checkbox that hugs its label leaves dead space that looks tappable.
        boxes.forEach {
            assertEquals(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (it.layoutParams as LinearLayout.LayoutParams).width,
            )
        }
    }

    @Test
    fun configLoad_checksTheSavedFields() {
        val activity = build(
            visible = listOf(IdCardLayoutConfig.FIELD_ROLE, IdCardLayoutConfig.FIELD_EVENT_DATE),
        )

        assertTrue(checkBox(activity, "Role/Type").isChecked)
        assertTrue(checkBox(activity, "Event Date").isChecked)
        assertFalse(checkBox(activity, "Event Name").isChecked)
        assertFalse(checkBox(activity, "Attendee ID").isChecked)

        // The preview follows the loaded config, not just the marks.
        assertTrue(previewHasText(activity, IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_ROLE)))
        assertFalse(previewHasText(activity, IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_EVENT_NAME)))
    }

    @Test
    fun tappingACheckBox_flipsIt_andRepaintsThePreview() {
        val activity = build()
        val attendeeId = checkBox(activity, "Attendee ID")

        assertFalse(attendeeId.isChecked)
        assertFalse("a hidden field prints nothing", previewHasText(activity, "#012"))

        // CompoundButton toggles inside performClick but reports `false` when no OnClickListener is
        // attached (its own listener is an OnCheckedChangeListener), so only the state is asserted.
        attendeeId.performClick()

        assertTrue("the checkbox owns the toggle", attendeeId.isChecked)
        assertTrue("the preview repaints on the spot", previewHasText(activity, "#012"))

        attendeeId.performClick()
        assertFalse(attendeeId.isChecked)
        assertFalse("the field leaves the preview again", previewHasText(activity, "#012"))
    }

    @Test
    fun optionRows_carryNoChevronAndNoFakeMark() {
        val activity = build()

        // Positive control: the locked card still draws its icon plates, so the check is not vacuous.
        assertTrue(
            activity.findViewById<LinearLayout>(R.id.idt_locked_card).descendants().any { it is ImageView },
        )

        assertEquals(
            "a toggle row does not navigate, so it carries no chevron or icon child",
            emptyList<ImageView>(),
            togglesCard(activity).descendants().filterIsInstance<ImageView>(),
        )
        checkBoxes(activity).forEach {
            assertNotNull(
                "the 20dp mark comes from the shared style as a compound drawable",
                it.compoundDrawablesRelative.firstOrNull(),
            )
        }
    }

    @Test
    fun titleIsAHeading_andTheStatusLineIsAPoliteLiveRegion() {
        val activity = build()

        val title = togglesCard(activity).getChildAt(0) as TextView
        assertEquals(
            activity.getString(R.string.ui_organizer_id_template_show_on_printed_id),
            title.text.toString(),
        )
        assertTrue("the card title is a heading", ViewCompat.isAccessibilityHeading(title))

        val status = activity.findViewById<TextView>(R.id.idt_status)
        assertNotNull(status)
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            status.accessibilityLiveRegion,
        )
    }

    @Test
    fun everyCardIsFlat() {
        val activity = build()

        assertEquals("no elevation on the toggle card", 0f, togglesCard(activity).elevation)
        preview(activity).descendants().forEach {
            assertEquals("no elevation anywhere in the preview: ${it.javaClass.simpleName}", 0f, it.elevation)
        }
    }

    private class ConfigRepository(
        context: Context,
        private val visible: List<String?>,
    ) : IdTemplateConfigRepository(context) {
        override suspend fun fetchConfig(eventId: String): NetworkResult<IdTemplateConfigResponse> =
            NetworkResult.Success(IdTemplateConfigResponse(eventId = eventId, visibleFields = visible))
    }

    private companion object {
        val EVENT_ID: String = UUID.fromString("3c1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0").toString()
    }
}
