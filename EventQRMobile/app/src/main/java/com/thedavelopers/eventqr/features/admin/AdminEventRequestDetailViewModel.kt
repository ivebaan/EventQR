package com.thedavelopers.eventqr.features.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.features.events.DetailErrorKind
import com.thedavelopers.eventqr.features.events.RequestDecision
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The request detail's state, held across a rotation (§6.5): a recreate must not refetch behind a
 * skeleton, and a decision that already landed must keep its result and its corrected status.
 *
 * [repository] is the network source. It defaults to the app's one [AdminRepository]; a JVM test
 * passes its own through the same constructor.
 */
class AdminEventRequestDetailViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: AdminRepository = AdminRepository(application),
) : AndroidViewModel(application) {

    private var requestId: String? = null
    // The one load in flight: the role check, a refresh, and the read a 409 asks for all ride this
    // job. A decision cancels it, so a GET that started before the PATCH can never land afterwards
    // and write the pre-decision PENDING back over the verdict.
    private var loadJob: Job? = null

    private val _request = MutableStateFlow<EventRequestResponse?>(null)
    val request: StateFlow<EventRequestResponse?> = _request.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * The typed half of [errorMessage]: the screen keys its recovery on this, never on the copy, so
     * a reworded or translated sentence can never turn an access failure into a Retry that fails
     * the same way.
     */
    private val _errorKind = MutableStateFlow<DetailErrorKind?>(null)
    val errorKind: StateFlow<DetailErrorKind?> = _errorKind.asStateFlow()

    private val _decisionMessage = MutableStateFlow<String?>(null)
    val decisionMessage: StateFlow<String?> = _decisionMessage.asStateFlow()

    private val _decisionIsError = MutableStateFlow(false)
    val decisionIsError: StateFlow<Boolean> = _decisionIsError.asStateFlow()

    private val _pendingAction = MutableStateFlow<RequestDecision?>(null)
    val pendingAction: StateFlow<RequestDecision?> = _pendingAction.asStateFlow()

    /** First load only: a rotation re-enters with the same id and keeps what is already there. */
    fun load(id: String) {
        if (requestId == id) return
        requestId = id
        // A new request starts without the previous verdict. A refresh keeps it (see [refresh]).
        _decisionMessage.value = null
        _decisionIsError.value = false
        verifyAdminAndLoad()
    }

    fun refresh() {
        // A reload started under a live decision would answer with the state from before the click
        // and repaint PENDING over the verdict, so the decision owns the screen until it lands.
        if (_pendingAction.value != null) return
        // The verdict strip survives a refresh: coming back to the screen must not wipe a decision
        // that already landed. Only [load] and [decide] clear it.
        verifyAdminAndLoad()
    }

    fun approve(remarks: String?) {
        decide(RequestDecision.APPROVE, remarks)
    }

    fun reject(remarks: String?) {
        decide(RequestDecision.REJECT, remarks)
    }

    private fun verifyAdminAndLoad() {
        _isLoading.value = true
        clearError()

        // One tracked job covers the role check and the read that follows: a decision cancels the
        // pair, and a second load cancels the first so an older answer cannot repaint newer state.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val result = repository.getCurrentUser()) {
                is NetworkResult.Success -> {
                    if (result.data.role != AccountRole.ADMIN && result.data.role != AccountRole.SUPER_ADMIN) {
                        _isLoading.value = false
                        // The role check is an access verdict by definition, whatever it prints.
                        recordError(text(R.string.ui_admin_error_admin_required), DetailErrorKind.ACCESS)
                    } else {
                        fetchRequest()
                    }
                }
                is NetworkResult.Error -> fail(result)
                NetworkResult.Loading -> Unit
            }
        }
    }

    private suspend fun fetchRequest() {
        val id = requestId
        if (id == null) {
            // Nothing to read, so the skeleton must not stay up for a request that cannot load.
            _isLoading.value = false
            return
        }
        _isLoading.value = true
        clearError()

        when (val result = repository.getEventRequest(id)) {
            is NetworkResult.Success -> {
                _request.value = result.data
                _isLoading.value = false
            }
            is NetworkResult.Error -> fail(result)
            NetworkResult.Loading -> Unit
        }
    }

    /**
     * With a request already on screen the screen turns this into the in-flow strip (§6.8 #19);
     * with none it is the error empty state.
     */
    private fun fail(error: NetworkResult.Error) {
        _isLoading.value = false
        recordError(toFriendlyError(error, decision = false), kindOf(error))
    }

    /**
     * Records the failure the screen reads as a type as well as a sentence. ACCESS and NOT_FOUND
     * have no retry, so they drop the request too: the screen then falls to its error empty state,
     * which offers the way back and never a Retry that could only fail again (§6.8 #3, #20).
     */
    private fun recordError(message: String, kind: DetailErrorKind) {
        _errorMessage.value = message
        _errorKind.value = kind
        if (kind != DetailErrorKind.RETRYABLE) _request.value = null
    }

    private fun clearError() {
        _errorMessage.value = null
        _errorKind.value = null
    }

    private fun decide(action: RequestDecision, remarks: String?) {
        val id = requestId ?: return
        if (_pendingAction.value != null) return

        _pendingAction.value = action
        _isLoading.value = true
        _decisionMessage.value = null
        // The strip's Retry is a load: it must not be tappable under a live decision either.
        clearError()

        // A load that is still in flight would answer with the state from before the click.
        loadJob?.cancel()

        viewModelScope.launch {
            val result = when (action) {
                RequestDecision.APPROVE -> repository.approveEvent(id, remarks)
                RequestDecision.REJECT -> repository.rejectEvent(id, remarks)
            }
            when (result) {
                is NetworkResult.Success -> {
                    // The endpoint returns the reviewed request, so the badge and the footer flip
                    // without a reload and can never be left showing a stale PENDING.
                    val decided = result.data
                    _request.value = if (decided.status == EventRequestStatus.PENDING) {
                        decided.copy(status = action.decidedStatus(), adminRemarks = decided.adminRemarks ?: remarks)
                    } else {
                        decided
                    }
                    _pendingAction.value = null
                    _isLoading.value = false
                    // A load failure from before the decision must not keep its strip stacked
                    // above the verdict: this read succeeded, so the stale sentence is gone.
                    clearError()
                    _decisionIsError.value = false
                    _decisionMessage.value = text(
                        when (action) {
                            RequestDecision.APPROVE -> R.string.ui_request_detail_approved
                            RequestDecision.REJECT -> R.string.ui_request_detail_rejected
                        },
                    )
                }
                is NetworkResult.Error -> {
                    _pendingAction.value = null
                    _isLoading.value = false
                    _decisionIsError.value = true
                    val message = toFriendlyError(result, decision = true)
                    val kind = kindOf(result)
                    if (kind == DetailErrorKind.RETRYABLE) {
                        // The verdict strip carries this failure: [errorKind] stays paired with the
                        // load's [errorMessage], which is null here, so a retryable verdict can never
                        // paint a load strip over the content it just kept.
                        _decisionMessage.value = message
                    } else {
                        // An access or missing-request verdict cannot be retried from here, so the
                        // content and its footer go with it: the screen falls to the error empty
                        // state, which offers the way back and no Retry.
                        recordError(message, kind)
                    }
                    // A 409 means the request moved on the server: reread it so the footer stops
                    // offering a decision that no longer applies. The read is the tracked load, so
                    // a later decision cancels it and it can never outlive the screen.
                    if (isStateConflict(result)) {
                        loadJob?.cancel()
                        loadJob = viewModelScope.launch { fetchRequest() }
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun RequestDecision.decidedStatus(): EventRequestStatus = when (this) {
        RequestDecision.APPROVE -> EventRequestStatus.APPROVED
        RequestDecision.REJECT -> EventRequestStatus.REJECTED
    }

    /** Only a 409 says the request moved on: a 400 is the decision's own validation (its remarks). */
    private fun isStateConflict(error: NetworkResult.Error): Boolean =
        (error.throwable as? HttpException)?.code() == 409

    /**
     * ACCESS and NOT_FOUND have no retry; everything else is a transient failure. The kind comes
     * from the HTTP status alone, never from the sentence, so reworded or translated copy can never
     * turn an access failure into a Retry that fails the same way.
     */
    private fun kindOf(error: NetworkResult.Error): DetailErrorKind =
        when ((error.throwable as? HttpException)?.code()) {
            401, 403 -> DetailErrorKind.ACCESS
            404 -> DetailErrorKind.NOT_FOUND
            else -> DetailErrorKind.RETRYABLE
        }

    private fun toFriendlyError(error: NetworkResult.Error, decision: Boolean): String {
        // A disabled account is the backend's 401/403 with that sentence in its body: the status is
        // the recovery verdict, the body only picks which access sentence is truer.
        val accountDisabled = error.serverMessage
            ?.lowercase()
            ?.let { it.contains("disabled") || it.contains("suspend") } == true

        // The status decides; the message is only the copy fallback for what a status cannot say.
        when ((error.throwable as? HttpException)?.code()) {
            // A 400 means the endpoint's own validation, and only the decision has a body to
            // validate: a read reports what failed instead of blaming a decision never sent.
            400 -> return text(
                if (decision) R.string.ui_admin_error_decision else R.string.ui_admin_error_bad_request,
            )
            401 -> return if (accountDisabled) {
                text(R.string.ui_admin_error_disabled)
            } else {
                text(R.string.ui_admin_error_session)
            }
            403 -> return if (accountDisabled) {
                text(R.string.ui_admin_error_disabled)
            } else {
                text(R.string.ui_admin_error_admin_required)
            }
            404 -> return text(R.string.ui_admin_error_not_found)
            409 -> return text(R.string.ui_admin_error_conflict)
            in 500..599 -> return text(R.string.ui_admin_error_server)
        }

        // No status at all: a dead connection is the one thing worth spelling out, and
        // `safeApiCall` reports it as an IO exception rather than a sentence to match.
        return when (error.throwable) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException, is IOException ->
                text(R.string.ui_admin_error_network)
            else -> error.message
        }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}
