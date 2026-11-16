package com.thedavelopers.eventqr.features.attendee

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.util.UiStrings
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.PointTransactionResponse
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The event payload paints first: the two activity ledgers that only decide whether the cancel
 * action is offered are read after that paint instead of in front of it. The final state is the
 * state the screen settled on before: the same action for the same ledgers, only later.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class EventDetailPresenterFirstPaintTest {

    private val eventId = UUID.randomUUID()
    private val userId = "u1"

    @After
    fun tearDown() = RegistrationsCache.clear()

    private fun registration() = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
    )

    private fun presenter(view: RecordingView, repo: GatedRepo) =
        EventDetailPresenter(view, repo, UiStrings(ApplicationProvider.getApplicationContext()))

    @Test
    fun theEventPaintsBeforeTheActivityLedgersAreRead() {
        val reg = registration()
        // A fresh cache answers the status check at once, so nothing but the paint holds the check back.
        RegistrationsCache.set(listOf(reg), userId)
        val repo = GatedRepo().apply { gate = CompletableDeferred() }
        val view = RecordingView(repo, userId)

        presenter(view, repo).loadEventDetails(eventId.toString())
        shadowOf(Looper.getMainLooper()).idle()

        // The event is on screen and the load has finished while both ledger reads still hang.
        assertEquals(1, view.events.size)
        assertEquals(listOf(true, false), view.loading)
        assertEquals("the ledgers start after the paint", 0, view.pointsAtPaint.single())
        // The check has started (and is suspended on the gate), so no verdict is in yet.
        assertTrue(view.cancellable.all { it == null })

        repo.gate!!.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, repo.pointReads)
        assertEquals(1, repo.scanReads)
        assertEquals(reg.registrationId.toString(), view.cancellable.last())
    }

    @Test
    fun aFailedEventLoadKeepsItsErrorAndStillRunsTheDeferredCheck() {
        val reg = registration()
        RegistrationsCache.set(listOf(reg), userId)
        val repo = GatedRepo().apply { eventResult = NetworkResult.Error("offline") }
        val view = RecordingView(repo, userId)

        presenter(view, repo).loadEventDetails(eventId.toString())
        shadowOf(Looper.getMainLooper()).idle()

        // Only the timing changed: a failed payload still reports itself in the strip, and the
        // activity check that the paint would have released runs anyway.
        assertEquals(listOf("offline"), view.loadErrors)
        assertEquals(1, repo.pointReads)
        assertEquals(reg.registrationId.toString(), view.cancellable.last())
    }

    @Test
    fun aRetryDuringTheLoadCancelsTheSupersededOne() {
        val reg = registration()
        RegistrationsCache.set(listOf(reg), userId)
        val repo = GatedRepo().apply { eventGate = CompletableDeferred() }
        val view = RecordingView(repo, userId)
        val presenter = presenter(view, repo)

        presenter.loadEventDetails(eventId.toString())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the payload has not landed, so nothing is painted", 0, view.events.size)

        // A retry while the first read is still in flight supersedes it.
        presenter.loadEventDetails(eventId.toString())
        shadowOf(Looper.getMainLooper()).idle()
        repo.eventGate!!.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        // Only the newer load paints, and only one activity check ever runs for it.
        assertEquals(1, view.events.size)
        assertEquals(1, repo.pointReads)
        assertEquals(reg.registrationId.toString(), view.cancellable.last())
    }

    private inner class GatedRepo : AttendeeRepository(ApplicationProvider.getApplicationContext()) {
        var eventResult: NetworkResult<AttendeeEventResponse> =
            NetworkResult.Success(AttendeeEventResponse(eventId = eventId, title = "Fest"))
        var pointReads = 0
        var scanReads = 0
        /** Holds both ledger reads open, so a test decides when the check lands. */
        var gate: CompletableDeferred<Unit>? = null
        /** Holds the event payload open, so a test can supersede a load that has not painted yet. */
        var eventGate: CompletableDeferred<Unit>? = null

        override suspend fun getEvent(eventId: String): NetworkResult<AttendeeEventResponse> {
            eventGate?.await()
            return eventResult
        }

        override suspend fun getMyPointTransactions(eventId: String): NetworkResult<List<PointTransactionResponse>> {
            pointReads++
            gate?.await()
            return NetworkResult.Success(emptyList())
        }

        override suspend fun getMyEventTransactions(eventId: String): NetworkResult<List<TransactionResponse>> {
            scanReads++
            gate?.await()
            return NetworkResult.Success(emptyList())
        }
    }

    private class RecordingView(private val repo: GatedRepo, private val userId: String) : EventDetailContract.View {
        val events = mutableListOf<AttendeeEventResponse>()
        val loading = mutableListOf<Boolean>()
        /** The ledger reads already issued when the event was painted. */
        val pointsAtPaint = mutableListOf<Int>()
        val cancellable = mutableListOf<String?>()
        val loadErrors = mutableListOf<String>()

        override fun renderEvent(event: AttendeeEventResponse) {
            events += event
            pointsAtPaint += repo.pointReads
        }

        override fun showLoading(isLoading: Boolean) { loading += isLoading }

        override fun updateRegistrationStatus(isRegistered: Boolean) = Unit
        override fun onRegistrationStatusCheckFailed() = Unit
        override fun setRegisteredId(registrationId: String?) = Unit
        override fun setCancellableRegistration(registrationId: String?) { cancellable += registrationId }
        override fun showCancelling(isCancelling: Boolean) = Unit
        override fun onRegistrationCancelled(message: String) = Unit
        override fun showCancelFailure(message: String) = Unit
        override fun openRegistration(eventId: String, eventTitle: String, email: String, fullName: String, phoneNumber: String) = Unit
        override fun showLoadError(message: String) { loadErrors += message }
        override fun getSessionUserId(): String? = userId
        override fun getSessionEmail(): String = ""
        override fun getSessionFullName(): String = ""
        override fun getSessionPhone(): String = ""
        override fun showMessage(message: String) = Unit
    }
}
