package com.thedavelopers.eventqr.features.events

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.ui.theme.EventQrTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The confirm dialog's remarks (§5.12, §6.5): the typed reason is consumed by a verdict, not by the
 * tap, so a 5xx or a dead connection leaves it in the field for the retry; and a request that leaves
 * PENDING closes the dialog instead of leaving a verb that no longer applies over it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventRequestDetailRemarksTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val request = EventRequestResponse(
        eventRequestId = UUID.randomUUID(),
        requesterUserId = UUID.randomUUID(),
        eventName = "DevFest Manila",
        status = EventRequestStatus.PENDING,
    )

    private fun setScreen(
        decisionMessage: MutableState<String?> = mutableStateOf(null),
        decisionIsError: MutableState<Boolean> = mutableStateOf(false),
        onReject: (String?) -> Unit = {},
        status: MutableState<EventRequestStatus> = mutableStateOf(EventRequestStatus.PENDING),
    ) {
        composeTestRule.setContent {
            EventQrTheme {
                EventRequestDetailScreen(
                    request = request.copy(status = status.value),
                    isLoading = false,
                    errorMessage = null,
                    isAdmin = true,
                    onRetryClick = {},
                    onApproveClick = {},
                    onRejectClick = onReject,
                    decisionMessage = decisionMessage.value,
                    decisionIsError = decisionIsError.value,
                )
            }
        }
    }

    /** The footer's outlined trigger; the dialog's filled commit shares its label. */
    private fun openRejectDialog() = composeTestRule.onNodeWithText("Reject").performClick()

    private fun rejectInDialog() =
        composeTestRule.onNode(hasText("Reject") and hasAnyAncestor(isDialog())).performClick()

    @Test
    fun aFailedDecisionKeepsTheTypedRemarks() {
        val message = mutableStateOf<String?>(null)
        val isError = mutableStateOf(false)
        var submitted: String? = null
        setScreen(message, isError, onReject = { remarks ->
            submitted = remarks
            message.value = "Server error. Try again later."
            isError.value = true
        })

        openRejectDialog()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("The venue is already booked")
        rejectInDialog()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reject request?").assertDoesNotExist()
        assertEquals("The venue is already booked", submitted)

        openRejectDialog()
        composeTestRule.onNode(hasSetTextAction())
            .assert(hasText("The venue is already booked"))
    }

    @Test
    fun aLandedDecisionConsumesTheTypedRemarks() {
        val message = mutableStateOf<String?>(null)
        val isError = mutableStateOf(false)
        setScreen(message, isError, onReject = {
            message.value = "Request rejected"
            isError.value = false
        })

        openRejectDialog()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("The venue is already booked")
        rejectInDialog()
        composeTestRule.waitForIdle()

        openRejectDialog()
        composeTestRule.onNode(hasSetTextAction() and hasText("The venue is already booked"))
            .assertDoesNotExist()
    }

    @Test
    fun theDialogClosesWhenTheRequestLeavesPending() {
        val status = mutableStateOf(EventRequestStatus.PENDING)
        setScreen(status = status)

        openRejectDialog()
        composeTestRule.onNodeWithText("Reject request?").assertExists()

        // A 409 reread (or the decision itself) moved the request on: the verb no longer applies.
        status.value = EventRequestStatus.APPROVED
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reject request?").assertDoesNotExist()
    }
}
