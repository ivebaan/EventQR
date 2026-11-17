package com.thedavelopers.eventqr.features.admin

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.features.events.EventRequestDetailScreen
import com.thedavelopers.eventqr.ui.components.setContentWithDetailHeader
import com.thedavelopers.eventqr.ui.theme.EventQrTheme

class AdminEventRequestDetailActivity : AppCompatActivity() {

    private val viewModel: AdminEventRequestDetailViewModel by viewModels()
    private var requestId: String = ""
    private var hasResumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            Toast.makeText(this, this.getString(R.string.admin_event_request_detail_request_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentWithDetailHeader(getString(R.string.ui_request_detail_title)) {
            EventQrTheme {
                val request = viewModel.request.collectAsStateWithLifecycle().value
                val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
                val errorMessage = viewModel.errorMessage.collectAsStateWithLifecycle().value
                val errorKind = viewModel.errorKind.collectAsStateWithLifecycle().value
                val decisionMessage = viewModel.decisionMessage.collectAsStateWithLifecycle().value
                val decisionIsError = viewModel.decisionIsError.collectAsStateWithLifecycle().value
                val pendingAction = viewModel.pendingAction.collectAsStateWithLifecycle().value

                // No result is passed back: every caller repaints on return regardless (the list's
                // launcher reloads unconditionally), so a decision needs no RESULT_OK handshake.
                EventRequestDetailScreen(
                    request = request,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    isAdmin = true,
                    onRetryClick = { viewModel.refresh() },
                    onApproveClick = { remarks -> viewModel.approve(remarks) },
                    onRejectClick = { remarks -> viewModel.reject(remarks) },
                    decisionMessage = decisionMessage,
                    decisionIsError = decisionIsError,
                    pendingAction = pendingAction,
                    errorKind = errorKind,
                )
            }
        }

        viewModel.load(requestId)
    }

    override fun onResume() {
        super.onResume()
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }
        if (requestId.isNotBlank()) {
            // The refresh keeps the verdict strip: a resume must not wipe a decision that landed.
            viewModel.refresh()
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "extra_request_id"
    }
}
