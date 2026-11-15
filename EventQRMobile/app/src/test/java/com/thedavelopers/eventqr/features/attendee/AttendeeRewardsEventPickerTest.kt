package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.api.dto.RewardStatus
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerInput
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The attendee Rewards screen hosts the shared event picker (plan 5.2): the registered events are
 * its options, the chosen one is the row it opened, and a pick either loads that event or - when it
 * is the event already on screen - does nothing at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AttendeeRewardsEventPickerTest {

    private val eventA: UUID = UUID.randomUUID()
    private val eventB: UUID = UUID.randomUUID()

    /** An explicit id the attendee never registered for. */
    private val unregisteredEvent: UUID = UUID.randomUUID()

    @Before
    fun setUp() {
        // The screen reads this cache before the fetch; a stale empty list would hide the picker.
        RegistrationsCache.clear()
    }

    /** Only the three reads this screen makes are faked; every request is recorded by event id. */
    private class FakeRepository(context: Context) : AttendeeRepository(context) {
        var registrations: NetworkResult<List<RegistrationResponse>> = NetworkResult.Success(emptyList())
        val rewardRequests = mutableListOf<String>()
        val rewardsByEvent = mutableMapOf<String, List<RewardResponse>>()

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> = registrations

        override suspend fun getMyRewardRedemptions(eventId: String): NetworkResult<List<RewardRedemptionResponse>> =
            NetworkResult.Success(emptyList())

        override suspend fun getRewardsByEvent(
            eventId: String,
            includeUnavailable: Boolean,
        ): NetworkResult<List<RewardResponse>> {
            rewardRequests += eventId
            return NetworkResult.Success(rewardsByEvent[eventId].orEmpty())
        }
    }

    private fun registration(eventId: UUID, title: String, registeredAt: String) = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        registeredAt = Instant.parse(registeredAt),
        eventTitle = title,
    )

    private fun reward(eventId: UUID, name: String) = RewardResponse(
        rewardId = UUID.randomUUID(),
        eventId = eventId,
        name = name,
        pointsRequired = 50,
        status = RewardStatus.ACTIVE,
    )

    /** Both events registered, A the most recent one, so A is the option the screen opens on. */
    private fun defaultRepository() = FakeRepository(ApplicationProvider.getApplicationContext()).apply {
        registrations = NetworkResult.Success(
            listOf(
                registration(eventB, "Day Fair", "2026-11-13T09:00:00Z"),
                registration(eventA, "Night Market", "2026-11-14T09:00:00Z"),
            ),
        )
        rewardsByEvent[eventA.toString()] = listOf(reward(eventA, "Coffee"))
        rewardsByEvent[eventB.toString()] = listOf(reward(eventB, "Mug"))
    }

    private fun build(
        repository: FakeRepository,
        requestedEventId: String? = null,
    ): AttendeeRewardsActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), AttendeeRewardsActivity::class.java)
        if (requestedEventId != null) intent.putExtra(EXTRA_EVENT_ID, requestedEventId)
        val controller = Robolectric.buildActivity(AttendeeRewardsActivity::class.java, intent)
        controller.get().repositoryOverride = repository
        // Visible attaches the tree: the picker posts its selection from a real window.
        controller.create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    private fun input(activity: AttendeeRewardsActivity): EventPickerInput =
        activity.findViewById<EventPickerField>(R.id.event_picker_field)
            .findViewById(R.id.eventPickerInput)

    private fun options(activity: AttendeeRewardsActivity): List<EventPickerOption> {
        val adapter = input(activity).adapter as BaseAdapter
        return (0 until adapter.count).map { adapter.getItem(it) as EventPickerOption }
    }

    /** The row tap a user makes: open the list, then the popup's own item click. */
    private fun choose(activity: AttendeeRewardsActivity, position: Int) {
        val field = input(activity)
        field.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        field.onItemClickListener.onItemClick(null, null, position, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The title the reward list renders, bound without waiting for a layout pass. */
    private fun firstRewardTitle(activity: AttendeeRewardsActivity): String {
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerRewards)
        val adapter = requireNotNull(recycler.adapter) { "the rewards list has no adapter" }
        assertTrue("the rewards list rendered no rows", adapter.itemCount > 0)
        val holder = adapter.createViewHolder(recycler, 0)
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.findViewById<TextView>(R.id.txtRewardTitle).text.toString()
    }

    private fun View.allTexts(): List<String> = when (this) {
        is TextView -> if (visibility == View.VISIBLE) listOf(text.toString()) else emptyList()
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allTexts() }
        else -> emptyList()
    }

    private fun View.findSpinner(): Spinner? = when (this) {
        is Spinner -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSpinner() }
        else -> null
    }

    @Test
    fun thePickerListsTheRegisteredEvents_andOpensOnTheCurrentOne() {
        val repository = defaultRepository()

        val activity = build(repository)

        assertEquals("Night Market", input(activity).text.toString())
        assertEquals(listOf(eventA.toString(), eventB.toString()), options(activity).map { it.id })
        assertEquals(listOf("Night Market", "Day Fair"), options(activity).map { it.title })
        // The old dropdown row carried the title alone: no meta line to carry over.
        assertTrue(options(activity).all { it.meta == null })
        assertEquals(listOf(eventA.toString()), repository.rewardRequests)
        assertEquals("Coffee", firstRewardTitle(activity))
    }

    @Test
    fun choosingAnotherEvent_loadsThatEventAndRendersItsRewards() {
        val repository = defaultRepository()
        val activity = build(repository)

        choose(activity, 1)

        // The presenter was asked for the new id, once, after the first event's load.
        assertEquals(listOf(eventA.toString(), eventB.toString()), repository.rewardRequests)
        assertEquals("Day Fair", input(activity).text.toString())
        assertEquals("Mug", firstRewardTitle(activity))
        assertFalse(input(activity).isPopupShowing)
    }

    @Test
    fun choosingTheEventAlreadyOnScreen_doesNotReloadIt() {
        val repository = defaultRepository()
        val activity = build(repository)

        choose(activity, 0)

        assertEquals(listOf(eventA.toString()), repository.rewardRequests)
        assertEquals("Night Market", input(activity).text.toString())
        assertEquals("Coffee", firstRewardTitle(activity))
    }

    @Test
    fun theHandBuiltSelectorIsGone() {
        val activity = build(defaultRepository())

        assertNotNull("the shared picker must host the event choice", activity.findViewById<View>(R.id.event_picker_field))
        assertNull("a hidden Spinner must not survive behind the picker", activity.window.decorView.findSpinner())
        listOf("spinnerRegisteredEvents", "layoutDropdownContent", "skeletonDropdown").forEach { legacy ->
            assertEquals(
                "$legacy belongs to the deleted dropdown and must not exist",
                0,
                activity.resources.getIdentifier(legacy, "id", activity.packageName),
            )
        }
    }

    @Test
    fun requestedEventWithoutARegistration_keepsTheNotRegisteredCopy_andPickingLoadsAgain() {
        val repository = defaultRepository()

        val activity = build(repository, requestedEventId = unregisteredEvent.toString())

        val notRegistered = activity.findViewById<View>(R.id.layoutNotRegisteredForEvent)
        assertEquals(View.VISIBLE, notRegistered.visibility)
        val copy = activity.window.decorView.allTexts()
        assertTrue(copy.contains(activity.getString(R.string.user_rewards_not_registered_for_this_event)))
        assertTrue(copy.contains(activity.getString(R.string.user_rewards_register_for_this_event_to_see_its_re)))
        // Nothing was loaded: no other event's rewards and balance are shown instead.
        assertTrue(repository.rewardRequests.isEmpty())
        // No selection, so the field reads its hint, and every option stays usable.
        assertEquals("", input(activity).text.toString())
        assertEquals(activity.getString(R.string.common_select_event), input(activity).hint.toString())
        assertEquals(2, options(activity).size)

        choose(activity, 0)

        assertEquals(listOf(eventA.toString()), repository.rewardRequests)
        assertEquals(View.GONE, notRegistered.visibility)
    }

    @Test
    fun failedEventsLoad_keepsTheErrorCopy() {
        val repository = defaultRepository().apply {
            registrations = NetworkResult.Error("Network problem")
        }

        val activity = build(repository)

        assertTrue(activity.window.decorView.allTexts().contains("Network problem"))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorRewardsContainer).visibility)
    }
}
