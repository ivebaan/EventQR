package com.thedavelopers.eventqr.features.events

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.ui.theme.EventQrTheme
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "One decision, one submit" (§6.5): the footer is locked by the decision in flight, not by the
 * load state, so a refresh or the reread a 409 starts never disables a live Approve or Reject.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventRequestDetailSubmittingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val request = EventRequestResponse(
        eventRequestId = UUID.randomUUID(),
        requesterUserId = UUID.randomUUID(),
        eventName = "DevFest Manila",
        status = EventRequestStatus.PENDING,
    )

    private fun setScreen(
        loading: MutableState<Boolean> = mutableStateOf(false),
        pendingAction: MutableState<RequestDecision?> = mutableStateOf(null),
    ) {
        composeTestRule.setContent {
            EventQrTheme {
                EventRequestDetailScreen(
                    request = request,
                    isLoading = loading.value,
                    errorMessage = null,
                    isAdmin = true,
                    onRetryClick = {},
                    onApproveClick = {},
                    onRejectClick = {},
                    pendingAction = pendingAction.value,
                )
            }
        }
    }

    @Test
    fun aLoadInFlightKeepsBothButtonsLive() {
        setScreen(loading = mutableStateOf(true))

        composeTestRule.onNodeWithText("Approve").assertIsEnabled()
        composeTestRule.onNodeWithText("Reject").assertIsEnabled()
    }

    @Test
    fun footerIsDisabledOnlyWhileADecisionIsInFlight() {
        val pendingAction = mutableStateOf<RequestDecision?>(null)
        setScreen(pendingAction = pendingAction)

        composeTestRule.onNodeWithText("Approve").assertIsEnabled()
        composeTestRule.onNodeWithText("Reject").assertIsEnabled()

        pendingAction.value = RequestDecision.APPROVE
        composeTestRule.waitForIdle()

        // The submitter holds its label slot with a spinner (§5.1); the other control is locked.
        composeTestRule.onNodeWithText("Approve").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reject").assertIsNotEnabled()

        pendingAction.value = null
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Approve").assertIsEnabled()
        composeTestRule.onNodeWithText("Reject").assertIsEnabled()
    }
}
