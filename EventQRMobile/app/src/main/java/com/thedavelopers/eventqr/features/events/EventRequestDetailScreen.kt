package com.thedavelopers.eventqr.features.events

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.core.format.formatEventTime
import com.thedavelopers.eventqr.core.format.formatRegistrationWindow
import com.thedavelopers.eventqr.core.util.DateFormatters
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.ui.components.EmptyState
import com.thedavelopers.eventqr.ui.components.EventBadgeStatus
import com.thedavelopers.eventqr.ui.components.InlineErrorStrip
import com.thedavelopers.eventqr.ui.components.MetricRowStacking
import com.thedavelopers.eventqr.ui.components.StatusBadge
import com.thedavelopers.eventqr.ui.components.UiButton
import com.thedavelopers.eventqr.ui.components.UiButtonTone
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.LocalGrid
import com.thedavelopers.eventqr.ui.theme.LocalType
import com.thedavelopers.eventqr.ui.theme.UiDisabledFill
import com.thedavelopers.eventqr.ui.theme.UiError
import com.thedavelopers.eventqr.ui.theme.UiErrorContainer
import com.thedavelopers.eventqr.ui.theme.UiHairline
import com.thedavelopers.eventqr.ui.theme.UiOnErrorContainer
import com.thedavelopers.eventqr.ui.theme.UiOnPrimary
import com.thedavelopers.eventqr.ui.theme.UiOnSuccessContainer
import com.thedavelopers.eventqr.ui.theme.UiPrimary
import com.thedavelopers.eventqr.ui.theme.UiSuccessContainer
import com.thedavelopers.eventqr.ui.theme.UiSurface
import com.thedavelopers.eventqr.ui.theme.UiSurfaceSunken
import com.thedavelopers.eventqr.ui.theme.UiTextDisabled
import com.thedavelopers.eventqr.ui.theme.UiTextPrimary
import com.thedavelopers.eventqr.ui.theme.UiTextSecondary
import java.util.UUID

/**
 * The one decision this screen can submit. It names the confirm dialog's pending action and, while
 * a submit is in flight, which footer control owns the spinner (§5.1, §6.5).
 */
enum class RequestDecision {
    APPROVE,
    REJECT,
}

/**
 * Why a load failed, as a type instead of a sentence: the screen keys its recovery on this, so a
 * reworded or translated message can never turn an access failure into a Retry that fails again.
 * `Access` and `NotFound` cannot be retried; `Retryable` is the ghost Retry (§6.8 #3, #20).
 */
enum class DetailErrorKind {
    ACCESS,
    NOT_FOUND,
    RETRYABLE,
}

/**
 * ACCESS and NOT_FOUND cannot be recovered on this screen: a Retry would fail the same way, so the
 * screen offers the way back instead and never leaves a strip or a decision footer live over it.
 */
private val DetailErrorKind?.cannotRetry: Boolean
    get() = this == DetailErrorKind.ACCESS || this == DetailErrorKind.NOT_FOUND

internal fun eventRequestBadgeStatus(status: EventRequestStatus): EventBadgeStatus = when (status) {
    EventRequestStatus.APPROVED -> EventBadgeStatus.APPROVED
    EventRequestStatus.REJECTED -> EventBadgeStatus.REJECTED
    EventRequestStatus.PENDING -> EventBadgeStatus.PENDING
}

internal fun requesterDisplay(
    requesterName: String?,
    contactEmail: String?,
    requesterUserId: UUID?,
    unknownLabel: String,
): String {
    requesterName?.takeIf { it.isNotBlank() }?.let { return it }
    contactEmail?.takeIf { it.isNotBlank() }?.let { return it }
    return unknownLabel
}

/**
 * The request's EVENT TIME value (§6.5), through the one shared formatter the attendee event detail
 * and the organizer event details already read, so the same two instants print the same string on
 * all three screens. No start returns null, so the caller hides the row with its hairline.
 */
internal fun eventRequestTime(context: Context, request: EventRequestResponse): String? =
    formatEventTime(context, request.startDateTime, request.endDateTime)

/**
 * The request's REGISTRATION WINDOW value (§6.5), same helper as the other two event details: both
 * ends as full datetimes, `event_detail_date_range` breaking after the start. No end at all returns
 * null, so the row and its hairline are hidden.
 */
internal fun eventRequestRegistrationWindow(context: Context, request: EventRequestResponse): String? =
    formatRegistrationWindow(
        context,
        request.registrationStartDateTime,
        request.registrationEndDateTime,
    )

/**
 * Event request detail (§6.5), shared by admin and attendee.
 *
 * The host activity owns the top bar (§5.8) through `setContentWithDetailHeader`, so this screen
 * draws no header and no Scaffold. The request is a printed spec table: `overline` label left,
 * value right, 1dp hairlines, no icon rows, no nested cards, no elevation. For an admin on a
 * pending request the decision pair is a 72dp sticky footer outside the scroll - primary Approve
 * last, destructive-outline Reject (§5.1, §6.7).
 */
@Composable
fun EventRequestDetailScreen(
    request: EventRequestResponse?,
    isLoading: Boolean,
    errorMessage: String?,
    isAdmin: Boolean,
    onRetryClick: () -> Unit,
    onApproveClick: ((remarks: String?) -> Unit)? = null,
    onRejectClick: ((remarks: String?) -> Unit)? = null,
    decisionMessage: String? = null,
    decisionIsError: Boolean = false,
    pendingAction: RequestDecision? = null,
    errorKind: DetailErrorKind? = null,
    modifier: Modifier = Modifier,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val context = LocalContext.current
    // §6.4 / §9.3, the same rule the organizer and attendee spec rows follow: above `fontScale`
    // 1.3 an overline label and its value cannot share one 320dp row, so both stack.
    val stacked = MetricRowStacking.stacks(LocalConfiguration.current.fontScale)
    // Both survive a rotation: the dialog was open, so it reopens, and the typed remarks come back
    // with it. Only the enum name is saved, so an unknown value simply closes the dialog.
    var confirmActionName by rememberSaveable { mutableStateOf<String?>(null) }
    var remarksText by rememberSaveable { mutableStateOf("") }
    // The last sentence announced, so a rotation does not read the same failure into the new window.
    var announcedError by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmAction = RequestDecision.entries.firstOrNull { it.name == confirmActionName }
    // A decision in flight locks both controls: one request, one verdict, no double submit. A
    // refresh or the read a 409 triggers is a load, not a submission, so neither locks the footer.
    val isSubmitting = pendingAction != null

    // The XML twin announces through `showInlineError`; the Compose mirror has no side effect, so
    // the host announces a new sentence itself - never a live region, which would swallow the
    // strip's Retry as its own node.
    val view = LocalView.current
    LaunchedEffect(errorMessage) {
        val message = errorMessage
        if (message.isNullOrBlank()) {
            announcedError = null
        } else if (message != announcedError) {
            announcedError = message
            view.announceForAccessibility(message)
        }
    }

    // A decision that landed consumes the typed reason; a failed one keeps it, so the admin can
    // retry with the words they already wrote.
    LaunchedEffect(decisionMessage, decisionIsError) {
        if (!decisionMessage.isNullOrBlank() && !decisionIsError) remarksText = ""
    }

    // A reload (or the decision itself) can move the request off PENDING, and then the dialog's
    // verb no longer applies: it closes instead of sitting over a decided request. An access or
    // missing-request failure closes it too - the footer it would act on is gone with the content.
    LaunchedEffect(request?.status, errorKind) {
        if (errorKind.cannotRetry || (request != null && request.status != EventRequestStatus.PENDING)) {
            confirmActionName = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(com.thedavelopers.eventqr.ui.theme.UiBackground),
    ) {
        when {
            isLoading && request == null -> DetailSkeleton()

            errorMessage != null && request == null -> {
                // §6.8 #20: the head is blank too, so the failure is the whole page. Recovery is a
                // ghost action (§6.8 #3), never a primary: nothing here is the screen's main task.
                // An access or missing-request failure cannot be retried, so it offers the way back
                // instead of a Retry that would fail the same way.
                val noRetry = errorKind.cannotRetry
                // The activity is the direct way back; outside one (a preview) the back dispatcher
                // still owns the decision, so a no-retry failure never offers a dead tap.
                val activity = LocalActivity.current
                val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val goBack: () -> Unit = {
                    if (activity != null) activity.finish() else backDispatcher?.onBackPressed()
                }
                EmptyState(
                    icon = AppIcons.Error,
                    title = stringResource(R.string.ui_request_detail_error_title),
                    description = errorMessage,
                    actionLabel = stringResource(
                        if (noRetry) R.string.ui_admin_go_back else R.string.common_retry,
                    ),
                    onActionClick = if (noRetry) goBack else onRetryClick,
                    primaryAction = false,
                )
            }

            request != null -> Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = grid.gutter),
                ) {
                    // §6.8 #19: a load that fails over a request already on screen keeps the
                    // content and reports itself here, under the head, instead of being swallowed.
                    // The host announces it (see the screen's top-level effect); the strip itself
                    // carries no live region, so its Retry stays its own node. A failure no retry
                    // can fix never reaches this branch: the request is dropped, so the screen
                    // shows the error empty state instead.
                    if (!errorMessage.isNullOrBlank() && !errorKind.cannotRetry) {
                        InlineErrorStrip(
                            message = errorMessage,
                            onRetry = onRetryClick,
                            // §5.16 the XML strip's top margin is `space_md`, not `space_16`.
                            modifier = Modifier.padding(top = grid.s12),
                        )
                    }

                    // ── Identity: name, status badge, who and when ──
                    // At or below 1.3 the badge trails the title on one row; above it the title takes
                    // the full width and the badge drops beneath (`space_8`), start aligned, exactly
                    // as the admin queue rows reflow (§6.5). The name never clips while stacked.
                    val eventTitle = request.eventName.ifBlank { stringResource(R.string.common_untitled_event) }
                    val badgeStatus = eventRequestBadgeStatus(request.status)
                    if (stacked) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = grid.s20)) {
                            Text(
                                text = eventTitle,
                                style = type.titleL,
                                color = UiTextPrimary,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(modifier = Modifier.height(grid.s8))
                            StatusBadge(status = badgeStatus, showIcon = false)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = grid.s20),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = eventTitle,
                                style = type.titleL,
                                color = UiTextPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            Spacer(modifier = Modifier.size(grid.s8))
                            StatusBadge(status = badgeStatus, showIcon = false)
                        }
                    }

                    if (isAdmin) {
                        Text(
                            text = stringResource(
                                R.string.ui_request_detail_submitted_by,
                                requesterDisplay(
                                    request.requesterName,
                                    request.contactEmail,
                                    request.requesterUserId,
                                    stringResource(R.string.ui_request_detail_unknown_requester),
                                ),
                            ),
                            style = type.bodyS,
                            color = UiTextSecondary,
                            modifier = Modifier.padding(top = grid.s8),
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.ui_request_detail_submitted_on,
                            DateFormatters.formatEventDate(request.createdAt),
                        ),
                        style = type.micro,
                        color = UiTextSecondary,
                        modifier = Modifier.padding(top = grid.s4),
                    )

                    // §6.8: a decision result stays in the flow; a toast would leave the screen
                    // silent. It sits directly under the identity block, before the reason, so a
                    // long reason can never push the outcome below the fold.
                    if (!decisionMessage.isNullOrBlank()) {
                        DecisionStrip(message = decisionMessage, isError = decisionIsError)
                    }

                    // An admin decides on the requester's own words, so they sit right under the
                    // identity block instead of after the spec table. For an attendee the reason is
                    // the last thing they wrote, so it stays at the end of the page.
                    if (isAdmin) ReasonBlock(request.reasonForRequest)

                    if (request.status == EventRequestStatus.APPROVED || request.status == EventRequestStatus.REJECTED) {
                        val approval = request.status == EventRequestStatus.APPROVED
                        NoteBlock(
                            title = stringResource(
                                if (approval) R.string.ui_request_detail_approval_note else R.string.ui_request_detail_rejection_note,
                            ),
                            message = request.adminRemarks?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.ui_request_detail_no_note),
                            tone = if (approval) NoteTone.INFO else NoteTone.ERROR,
                            modifier = Modifier.padding(top = grid.s16),
                        )
                    }

                    // ── Description ──
                    SectionEyebrow(stringResource(R.string.ui_request_detail_description))
                    JustifiedBody(
                        text = request.eventDescription?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.ui_request_detail_no_description),
                    )

                    // ── The spec table: every row is label + value + hairline. Both ranges read the
                    // one shared formatter (§6.2, §6.4), so the same instants print the same value
                    // here as on the attendee event detail and the organizer event details. ──
                    eventRequestTime(context, request)?.let { value ->
                        SpecRow(label = stringResource(R.string.common_event_schedule), value = value)
                    }
                    eventRequestRegistrationWindow(context, request)?.let { value ->
                        SpecRow(label = stringResource(R.string.common_event_registration), value = value)
                    }
                    request.eventCategory?.takeIf { it.isNotBlank() }?.let { category ->
                        SpecRow(label = stringResource(R.string.ui_request_detail_category), value = category)
                    }
                    request.targetAudience?.takeIf { it.isNotBlank() }?.let { audience ->
                        SpecRow(label = stringResource(R.string.ui_request_detail_audience), value = audience)
                    }
                    SpecRow(
                        label = stringResource(R.string.ui_request_detail_venue),
                        value = request.venue?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.ui_request_detail_not_available),
                    )
                    SpecRow(
                        label = stringResource(R.string.ui_request_detail_capacity),
                        value = request.capacity.toString(),
                    )
                    if (isAdmin && !request.contactEmail.isNullOrBlank()) {
                        SpecRow(label = stringResource(R.string.ui_request_detail_contact_email), value = request.contactEmail)
                    }
                    if (isAdmin && !request.contactNumber.isNullOrBlank()) {
                        SpecRow(label = stringResource(R.string.ui_request_detail_contact_phone), value = request.contactNumber)
                    }
                    if (!isAdmin) ReasonBlock(request.reasonForRequest)

                    Spacer(modifier = Modifier.height(grid.s32))
                }

                // An access or missing-request failure hides the decision pair: a session or role
                // that is gone must never be offered a verdict, and no footer may stay live under
                // a failure no retry can fix.
                if (
                    isAdmin &&
                    request.status == EventRequestStatus.PENDING &&
                    !errorKind.cannotRetry
                ) {
                    ApprovalFooter(
                        pendingAction = pendingAction,
                        // Opening a confirm never clears the typed reason: the field holds it until
                        // a verdict lands, and only a successful one consumes it.
                        onReject = { confirmActionName = RequestDecision.REJECT.name },
                        onApprove = { confirmActionName = RequestDecision.APPROVE.name },
                    )
                }
            }
        }
    }

    confirmAction?.let { action ->
        ConfirmDialog(
            action = action,
            remarks = remarksText,
            isSubmitting = isSubmitting,
            onRemarksChange = { remarksText = it },
            onDismiss = {
                confirmActionName = null
                remarksText = ""
            },
            onConfirm = {
                // The field is cleared by the verdict, not by the tap: a 5xx or a dead connection
                // must keep the reason the admin already typed.
                val remarks = remarksText.ifBlank { null }
                confirmActionName = null
                when (action) {
                    RequestDecision.APPROVE -> onApproveClick?.invoke(remarks)
                    RequestDecision.REJECT -> onRejectClick?.invoke(remarks)
                }
            },
        )
    }
}

/**
 * First load (§5.11): bars in the loaded geometry, never a centred spinner. One TalkBack node
 * announces "Loading"; the bars themselves are decorative.
 */
@Composable
private fun DetailSkeleton() {
    val grid = LocalGrid.current
    val loading = stringResource(R.string.ui_skeleton_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) { contentDescription = loading }
            .padding(horizontal = grid.gutter, vertical = grid.s20),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(grid.skeletonBarHeight)
                .background(UiSurfaceSunken),
        )
        Spacer(modifier = Modifier.height(grid.s12))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(grid.skeletonBarHeight)
                .background(UiSurfaceSunken),
        )
        Spacer(modifier = Modifier.height(grid.s24))
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth().height(grid.metricRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.35f)
                        .height(grid.skeletonBarHeight)
                        .background(UiSurfaceSunken),
                )
                Spacer(modifier = Modifier.weight(0.3f))
                Box(
                    modifier = Modifier
                        .weight(0.35f)
                        .height(grid.skeletonBarHeight)
                        .background(UiSurfaceSunken),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.stroke_hairline))
                    .background(UiHairline),
            )
        }
    }
}

/** 72dp sticky footer (§6.7): destructive-outline Reject, 8dp gap, primary Approve last. */
@Composable
private fun ApprovalFooter(
    pendingAction: RequestDecision?,
    onReject: () -> Unit,
    onApprove: () -> Unit,
) {
    val grid = LocalGrid.current
    // The window is edge to edge, so the bar's fill has to run behind the gesture bar while its
    // content stays above it - the XML form footers do the same with their navigation-bar inset.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiSurface)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.stroke_hairline))
                .background(UiHairline),
        )
        Row(
            // A minimum, not a fixed height: at a large font scale the bar grows instead of
            // clipping its labels.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimensionResource(R.dimen.ui_footer_height))
                .padding(horizontal = grid.gutter, vertical = grid.s12),
            horizontalArrangement = Arrangement.spacedBy(grid.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiButton(
                text = stringResource(R.string.ui_request_detail_reject),
                onClick = onReject,
                modifier = Modifier.weight(1f),
                tone = UiButtonTone.DESTRUCTIVE,
                // The submitter keeps the enabled palette so its spinner is drawn on the live fill;
                // `loading` already blocks its clicks. Every other control stays disabled, so a
                // second verdict cannot be fired.
                enabled = pendingAction == null || pendingAction == RequestDecision.REJECT,
                // §5.1: the submitter holds the label slot with a 20dp spinner and keeps its width.
                loading = pendingAction == RequestDecision.REJECT,
            )
            UiButton(
                text = stringResource(R.string.ui_request_detail_approve),
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                enabled = pendingAction == null || pendingAction == RequestDecision.APPROVE,
                loading = pendingAction == RequestDecision.APPROVE,
            )
        }
    }
}

/** In-flow decision result with a polite live region, so the change is announced without a toast. */
@Composable
private fun DecisionStrip(message: String, isError: Boolean) {
    val grid = LocalGrid.current
    val type = LocalType.current
    Text(
        text = message,
        style = type.bodyS,
        color = if (isError) UiOnErrorContainer else UiOnSuccessContainer,
        modifier = Modifier
            .padding(top = grid.s16)
            .fillMaxWidth()
            .clip(RoundedCornerShape(grid.radiusCard))
            .background(if (isError) UiErrorContainer else UiSuccessContainer)
            .padding(grid.s12)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SectionEyebrow(text: String) {
    val grid = LocalGrid.current
    val type = LocalType.current
    Text(
        text = text,
        style = type.overline,
        color = UiTextSecondary,
        modifier = Modifier
            .padding(top = grid.s24, bottom = grid.s8)
            .semantics { heading() },
    )
}

/** The requester's own words, under its own section label. Absent copy draws nothing. */
@Composable
private fun ReasonBlock(reason: String?) {
    if (reason.isNullOrBlank()) return
    SectionEyebrow(stringResource(R.string.ui_request_detail_reason))
    JustifiedBody(text = reason)
}

/**
 * A prose body with real inter-word justification (§6.5). Compose `Text` has no justification, and
 * faking it word by word would break selection, `fontScale` reflow and RTL, so the body is the
 * platform TextView the rest of the app already uses for the `body` role
 * (`res/layout/ui_justified_body.xml`, `TextAppearance.EventQR.Type.Body`, start aligned).
 * `justificationMode` is `inter_word` there, which is API 26 - this build's `minSdk` - and a
 * platform that ignored it would fall back to the start edge, never to a broken layout.
 */
@Composable
private fun JustifiedBody(text: String) {
    AndroidView(
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.ui_justified_body, null) as TextView
        },
        update = { view -> view.text = text },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One spec-table row (§6.5 / §5.9 form 2): label left, value right, hairline underneath. The row is
 * a minimum height, not a fixed one, so a long value or a large font scale wraps and grows. The label
 * hugs its own text and the value takes the rest of the row - the attendee event detail's geometry -
 * because a 50/50 split forces a full `12 Oct 2026, 9:41 AM` line to break inside the timestamp.
 *
 * Above `fontScale` 1.3 the pair stacks through the shared [MetricRowStacking] rule: the label on
 * top, the value start-aligned `space_4` under it, the same `space_12` padding and hairline. Both
 * texts stay one merged TalkBack node either way.
 */
@Composable
private fun SpecRow(label: String, value: String) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val stacked = MetricRowStacking.stacks(LocalConfiguration.current.fontScale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = grid.metricRowHeight)
                    .padding(vertical = grid.s12),
            ) {
                Text(text = label, style = type.overline, color = UiTextSecondary)
                Text(
                    text = value,
                    style = type.numericS,
                    color = UiTextPrimary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(top = grid.s4),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = grid.metricRowHeight)
                    .padding(vertical = grid.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = type.overline,
                    color = UiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = type.numericS,
                    color = UiTextPrimary,
                    textAlign = TextAlign.End,
                    // A long value wraps inside its share instead of ellipsizing the fact away. The
                    // share is filled, not shrunk to the text: that is what pins every value to the
                    // row's end edge, including a one-word one.
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = grid.s16),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(dimensionResource(R.dimen.stroke_hairline)).background(UiHairline))
    }
}

private enum class NoteTone { INFO, ERROR }

/**
 * A note is a flat semantic block, not a card: tonal fill, no border, no elevation. Approval is
 * informational; rejection is the error tone. Lime never appears here (nothing is confirmed).
 */
@Composable
private fun NoteBlock(
    title: String,
    message: String,
    tone: NoteTone,
    modifier: Modifier = Modifier,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val fill: Color
    val ink: Color
    when (tone) {
        NoteTone.INFO -> {
            fill = com.thedavelopers.eventqr.ui.theme.UiInfoContainer
            ink = com.thedavelopers.eventqr.ui.theme.UiOnInfoContainer
        }
        NoteTone.ERROR -> {
            fill = UiErrorContainer
            ink = UiOnErrorContainer
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(grid.radiusCard))
            .background(fill)
            .padding(grid.s16),
    ) {
        Text(text = title, style = type.overline, color = ink)
        Spacer(modifier = Modifier.height(grid.s4))
        Text(text = message, style = type.bodyS, color = ink)
    }
}

/**
 * Confirm dialog (§5.12): radius 16, title 18/600, body 15 secondary, actions right-aligned, the
 * primary last. A destructive confirm's commit is the one filled red control in the flow - `ui_error`
 * fill, `ui_on_primary` label at 6.59:1, 48dp floor, `radiusButton` - and its label is the verb
 * (`Reject`), never the question. The safe action (Approve) stays the dialog's primary text action
 * and the ghost cancel sits beside it.
 */
@Composable
private fun ConfirmDialog(
    action: RequestDecision,
    remarks: String,
    isSubmitting: Boolean,
    onRemarksChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val title = stringResource(
        when (action) {
            RequestDecision.APPROVE -> R.string.ui_request_detail_confirm_approve
            RequestDecision.REJECT -> R.string.ui_request_detail_confirm_reject
        },
    )
    val body = stringResource(
        when (action) {
            RequestDecision.APPROVE -> R.string.ui_request_detail_confirm_approve_body
            RequestDecision.REJECT -> R.string.ui_request_detail_confirm_reject_body
        },
    )
    // The commit names its own verb; it never reuses the question as its label.
    val commitLabel = stringResource(
        when (action) {
            RequestDecision.APPROVE -> R.string.ui_request_detail_approve
            RequestDecision.REJECT -> R.string.ui_request_detail_reject
        },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(grid.radiusSheet),
        containerColor = UiSurface,
        title = { Text(text = title, style = type.title, color = UiTextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            // A keyboard on a short screen must not clip the dialog: the body scrolls, the actions
            // stay pinned. The field grows with its text instead of trapping the caret.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = body, style = type.body, color = UiTextSecondary)
                Spacer(modifier = Modifier.height(grid.s12))
                RemarksField(value = remarks, onValueChange = onRemarksChange)
            }
        },
        confirmButton = {
            if (action == RequestDecision.REJECT) {
                // §5.1 destructive commit: the verb on `ui_error`, filled, 48dp - a filled red
                // control exists only inside a confirm.
                DestructiveCommitButton(
                    label = commitLabel,
                    enabled = !isSubmitting,
                    onClick = onConfirm,
                )
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = !isSubmitting,
                ) {
                    Text(
                        text = commitLabel,
                        style = type.label,
                        color = UiPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.request_event_cancel), style = type.label, color = UiPrimary)
            }
        },
    )
}

/**
 * §5.1 destructive commit: `ui_error` fill, `ui_on_primary` label (6.59:1), `radiusButton`, 48dp floor.
 * Not a shared component on purpose - the plan allows this tone only inside a confirm dialog, and
 * this is the app's confirm that rejects a request. The disabled label is `ui_text_disabled`, the
 * same token [UiButton] uses, so one fill family has one disabled label colour.
 */
@Composable
private fun DestructiveCommitButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    Box(
        modifier = Modifier
            .heightIn(min = grid.touchMin)
            .clip(RoundedCornerShape(grid.radiusButton))
            .background(if (enabled) UiError else UiDisabledFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = grid.s16),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.label,
            color = if (enabled) UiOnPrimary else UiTextDisabled,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * `bg_ui_field` language in Compose: sunken 4dp-radius box, no outline, 2dp focus stroke, and the
 * static `overline` label above it (§5.2). The label is the field's visible name; the box carries no
 * placeholder, so an empty field never borrows the label as hint text.
 */
@Composable
private fun RemarksField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val shape = RoundedCornerShape(grid.radiusButton)
    var focused by remember { mutableStateOf(false) }
    val fieldLabel = stringResource(R.string.ui_request_detail_remarks_label)

    Column(modifier = Modifier.fillMaxWidth()) {
        // The field itself carries the spoken name, so the visible label is presentation only: two
        // name nodes would read the label twice.
        Text(
            text = fieldLabel,
            style = type.overline,
            color = UiTextSecondary,
            modifier = Modifier.clearAndSetSemantics {},
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = type.body.copy(color = UiTextPrimary),
            cursorBrush = SolidColor(UiPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = grid.s4)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    // A contentDescription on a text field replaces its text for TalkBack, so it is
                    // only set while the field is empty: once a reason is typed, that reason is
                    // what has to be read back, not the label again.
                    if (value.isEmpty()) contentDescription = fieldLabel
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimensionResource(R.dimen.ui_field_multiline_height))
                        .clip(shape)
                        .background(UiSurfaceSunken)
                        .then(
                            if (focused) {
                                Modifier.border(dimensionResource(R.dimen.stroke_focus), UiPrimary, shape)
                            } else {
                                Modifier
                            },
                        )
                        .padding(grid.cardPadding),
                    contentAlignment = Alignment.TopStart,
                ) {
                    innerTextField()
                }
            },
        )
    }
}
