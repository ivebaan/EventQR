package com.thedavelopers.eventqr.features.admin

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.AccountStatus
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.features.events.DetailErrorKind
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/**
 * The request detail's state machine (§6.5): a 409 reread rides the one tracked load, an access
 * failure is typed rather than matched from copy, and a verdict outlives a refresh.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AdminEventRequestDetailViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var repository: FakeRepository
    private lateinit var viewModel: AdminEventRequestDetailViewModel

    @Before
    fun setUp() {
        repository = FakeRepository(application)
        viewModel = AdminEventRequestDetailViewModel(application, repository)
    }

    @After
    fun tearDown() {
        // The 409 reread is deliberately left suspended in one test; drop it with the screen.
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun a409ReloadIsTrackedAndCancelledByTheNextDecision() {
        var reloadCancelled = false
        repository.decide = { _, _ -> errorResult(409) }
        repository.load = {
            if (repository.loadCount > 1) {
                // Never answers: the reload must be cancelled, not left to land on its own.
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { reloadCancelled = true }
                }
            } else {
                NetworkResult.Success(pending())
            }
        }

        viewModel.load(REQUEST_ID)
        idle()
        assertEquals(1, repository.loadCount)

        viewModel.approve(null)
        idle()
        assertEquals("the 409 must reread the request", 2, repository.loadCount)
        assertEquals(application.getString(R.string.ui_admin_error_conflict), viewModel.decisionMessage.value)
        assertTrue(viewModel.decisionIsError.value)

        viewModel.reject("no")
        idle()
        assertTrue("a second decision must cancel the reread", reloadCancelled)
    }

    @Test
    fun anAccessFailureIsATypedAccessError() {
        repository.load = { errorResult(403) }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.ACCESS, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_admin_required), viewModel.errorMessage.value)
        assertNull(viewModel.request.value)
    }

    @Test
    fun aRoleCheckFailureIsAnAccessError() {
        repository.role = AccountRole.ATTENDEE

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.ACCESS, viewModel.errorKind.value)
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun aMissingRequestIsNotFoundNotRetryable() {
        repository.load = { errorResult(404) }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.NOT_FOUND, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_not_found), viewModel.errorMessage.value)
    }

    @Test
    fun aDisabledAccountIsAnAccessError() {
        repository.load = { errorResult(403, "Account is disabled. Contact support.") }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.ACCESS, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_disabled), viewModel.errorMessage.value)
    }

    @Test
    fun anAccessSentenceWithoutAStatusIsNotTypedFromText() {
        // The copy can say anything; only the HTTP status may choose a non-retryable recovery.
        repository.load = { NetworkResult.Error("401 Unauthorized. Admin access required.") }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.RETRYABLE, viewModel.errorKind.value)
    }

    @Test
    fun aServerFailureStaysRetryable() {
        repository.load = { errorResult(500) }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.RETRYABLE, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_server), viewModel.errorMessage.value)
    }

    @Test
    fun theVerdictSurvivesARefreshAndOnlyANewRequestClearsIt() {
        repository.decide = { _, _ -> NetworkResult.Success(approved()) }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.approve(null)
        idle()
        assertEquals(application.getString(R.string.ui_request_detail_approved), viewModel.decisionMessage.value)

        repository.request = approved()
        viewModel.refresh()
        idle()

        assertEquals("a refresh must not wipe the verdict", application.getString(R.string.ui_request_detail_approved), viewModel.decisionMessage.value)
        assertFalse(viewModel.decisionIsError.value)

        viewModel.load(UUID.randomUUID().toString())
        idle()

        assertNull(viewModel.decisionMessage.value)
    }

    @Test
    fun aBadRequestIsValidationNotAConflictReread() {
        // A validation 400 can mention an already-reviewed request, but only the 409 status may
        // trigger the reread, so the sentence must not decide it.
        repository.decide = { _, _ ->
            errorResult(400, "Only pending requests can be approved. This request is already approved.")
        }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.reject("not a reason")
        idle()

        assertEquals("a 400 must not reload the request", 1, repository.loadCount)
        assertEquals(application.getString(R.string.ui_admin_error_decision), viewModel.decisionMessage.value)
        assertTrue(viewModel.decisionIsError.value)
    }

    @Test
    fun aDecisionAccessFailureDropsTheContentAndItsFooter() {
        // A 403 on the verdict cannot be retried from this screen: the content goes, so the screen
        // can only show the error empty state with the way back - no Retry, no live footer.
        repository.decide = { _, _ -> errorResult(403) }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.approve(null)
        idle()

        assertNull("the decided-on content must be dropped", viewModel.request.value)
        assertEquals(DetailErrorKind.ACCESS, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_admin_required), viewModel.errorMessage.value)
        assertNull(viewModel.pendingAction.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun aDecisionNotFoundFailureDropsTheContentAndItsFooter() {
        repository.decide = { _, _ -> errorResult(404) }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.reject("gone")
        idle()

        assertNull("the missing request must not stay on screen", viewModel.request.value)
        assertEquals(DetailErrorKind.NOT_FOUND, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_not_found), viewModel.errorMessage.value)
        assertNull(viewModel.pendingAction.value)
    }

    @Test
    fun aTransientDecisionFailureKeepsTheContentAndTheFooter() {
        // A 5xx is retryable, so the request, its footer and the in-flow verdict strip all stay.
        repository.decide = { _, _ -> errorResult(500) }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.approve(null)
        idle()

        assertTrue("a transient failure must keep the request", viewModel.request.value != null)
        // The verdict strip owns this failure; the typed load error stays null with its sentence.
        assertNull("a retryable verdict is a strip, not a load error", viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_server), viewModel.decisionMessage.value)
        assertTrue(viewModel.decisionIsError.value)
        assertNull("a decision failure is not a load strip", viewModel.errorMessage.value)
    }

    @Test
    fun aSecondApproveBeforeTheFirstLandsRunsOneDecision() {
        val gate = CompletableDeferred<NetworkResult<EventRequestResponse>>()
        var decisions = 0
        repository.decide = { _, _ ->
            decisions++
            gate.await()
        }

        viewModel.load(REQUEST_ID)
        idle()
        viewModel.approve(null)
        idle()
        assertTrue("the decision must be live before the second tap", viewModel.pendingAction.value != null)

        viewModel.approve(null)
        idle()
        assertEquals("a second tap must not send a second verdict", 1, decisions)

        gate.complete(NetworkResult.Success(approved()))
        idle()
        assertNull(viewModel.pendingAction.value)
    }

    @Test
    fun aRefreshUnderALiveDecisionLeavesTheLoadAlone() {
        val gate = CompletableDeferred<NetworkResult<EventRequestResponse>>()
        repository.decide = { _, _ -> gate.await() }

        viewModel.load(REQUEST_ID)
        idle()
        assertEquals(1, repository.loadCount)

        viewModel.approve(null)
        idle()
        assertTrue("the decision must be live", viewModel.pendingAction.value != null)

        viewModel.refresh()
        idle()
        assertEquals("a refresh must not repaint the pre-decision state", 1, repository.loadCount)

        gate.complete(NetworkResult.Success(approved()))
        idle()
    }

    @Test
    fun aRefreshAccessFailureDropsTheLoadedContent() {
        // A resume refresh that loses the session must not leave the loaded request with a Retry
        // strip over it: the content goes with the failure.
        viewModel.load(REQUEST_ID)
        idle()
        assertTrue(viewModel.request.value != null)

        repository.load = { errorResult(401) }
        viewModel.refresh()
        idle()

        assertNull(viewModel.request.value)
        assertEquals(DetailErrorKind.ACCESS, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_session), viewModel.errorMessage.value)
    }

    @Test
    fun aReadBadRequestDoesNotBlameADecision() {
        // A 400 on the GET is not the decision's validation: the copy must not mention a decision
        // that was never sent.
        repository.load = { errorResult(400) }

        viewModel.load(REQUEST_ID)
        idle()

        assertEquals(DetailErrorKind.RETRYABLE, viewModel.errorKind.value)
        assertEquals(application.getString(R.string.ui_admin_error_bad_request), viewModel.errorMessage.value)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Only what this screen calls is faked; the rest of the repository is never reached. */
    private class FakeRepository(context: Context) : AdminRepository(context) {
        var role = AccountRole.SUPER_ADMIN
        var request: EventRequestResponse = pending()
        var loadCount = 0
        var load: suspend (String) -> NetworkResult<EventRequestResponse> = { NetworkResult.Success(request) }
        var decide: suspend (String?, String?) -> NetworkResult<EventRequestResponse> =
            { _, _ -> NetworkResult.Success(approved()) }

        override suspend fun getCurrentUser(): NetworkResult<UserResponse> = NetworkResult.Success(
            UserResponse(
                userId = UUID.randomUUID(),
                email = "admin@example.com",
                fullName = "Admin",
                role = role,
                status = AccountStatus.ACTIVE,
            ),
        )

        override suspend fun getEventRequest(requestId: String): NetworkResult<EventRequestResponse> {
            loadCount++
            return load(requestId)
        }

        override suspend fun approveEvent(eventId: String, remarks: String?): NetworkResult<EventRequestResponse> =
            decide(eventId, remarks)

        override suspend fun rejectEvent(eventId: String, remarks: String?): NetworkResult<EventRequestResponse> =
            decide(eventId, remarks)
    }

    private companion object {
        const val REQUEST_ID = "3f6e1f38-1f21-4e0a-9a1e-9c9f1d2a4b6c"
    }
}

private fun pending() = EventRequestResponse(
    eventRequestId = UUID.randomUUID(),
    requesterUserId = UUID.randomUUID(),
    eventName = "DevFest Manila",
    status = EventRequestStatus.PENDING,
)

private fun approved() = pending().copy(status = EventRequestStatus.APPROVED)

private fun errorResult(code: Int, message: String = "HTTP $code"): NetworkResult.Error = NetworkResult.Error(
    message = message,
    throwable = HttpException(
        Response.error<Any>(
            code,
            "{\"message\":\"$message\"}".toResponseBody("application/json".toMediaType()),
        ),
    ),
    serverMessage = message,
)
