package com.thedavelopers.eventqr.features.notifications

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.core.util.RelativeTimeUtils
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.ui.components.EmptyState
import com.thedavelopers.eventqr.ui.components.InlineErrorStrip
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.LocalGrid
import com.thedavelopers.eventqr.ui.theme.LocalType
import com.thedavelopers.eventqr.ui.theme.UiBackground
import com.thedavelopers.eventqr.ui.theme.UiHairline
import com.thedavelopers.eventqr.ui.theme.UiPrimary
import com.thedavelopers.eventqr.ui.theme.UiPrimaryContainer
import com.thedavelopers.eventqr.ui.theme.UiPrimaryWash
import com.thedavelopers.eventqr.ui.theme.UiSurfaceSunken
import com.thedavelopers.eventqr.ui.theme.UiTextPrimary
import com.thedavelopers.eventqr.ui.theme.UiTextSecondary

/**
 * Notifications (§6.5) - retires Gen B.
 *
 * The activity owns the top bar (shared `EventQrDetailHeader`), so this screen draws no header and
 * no Scaffold. Rows use the `list_row_icon` geometry: 88dp, 40dp tile, title, two-line body,
 * relative time in `micro` on the right. Read vs unread is unmistakable: an unread row fills
 * `primary_wash`, carries a 8dp `primary` dot in the gutter overlay, a 600 `text_primary` title and
 * a `primary` timestamp; a read row falls back to `background`, a `surface_sunken` tile with a
 * `text_secondary` icon, a 400 `text_secondary` title and timestamp. Hairline dividers inset to the
 * text edge. No per-row card, no circular icon backgrounds, no purple glow.
 */
@Composable
fun NotificationsScreen(
    notifications: List<NotificationResponse>,
    isLoading: Boolean,
    errorMessage: String?,
    actionFailureCount: Int,
    onNotificationClick: (NotificationResponse) -> Unit,
    onRetryClick: () -> Unit,
    onActionRetry: () -> Unit,
    navigatesOnTap: (NotificationResponse) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val view = LocalView.current
    // One sentence for both read and mark-read failures (§7.3); the counter says whether one failed.
    val actionErrorMessage = if (actionFailureCount > 0) {
        stringResource(R.string.ui_notifications_update_failed)
    } else {
        null
    }

    // The sentence never changes, so a live region would stay silent on a repeat failure; announce
    // each new failure explicitly instead (§5.16). The count is the key: a repeated failure bumps it.
    LaunchedEffect(actionFailureCount) {
        actionErrorMessage?.let(view::announceForAccessibility)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        containerColor = UiBackground,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(UiBackground),
        ) {
            when {
                isLoading && notifications.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = UiPrimary, strokeWidth = grid.s2)
                }

                errorMessage != null && notifications.isEmpty() -> EmptyState(
                    icon = AppIcons.Error,
                    title = stringResource(R.string.ui_notifications_error_title),
                    description = errorMessage,
                    actionLabel = stringResource(R.string.common_retry),
                    onActionClick = onRetryClick,
                    primaryAction = true,
                )

                notifications.isEmpty() -> EmptyState(
                    icon = AppIcons.NoNotifications,
                    title = stringResource(R.string.ui_notifications_empty_title),
                    description = stringResource(R.string.ui_notifications_empty_body),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // §6.8 row 21: a failed read reports above the list; the rows keep their state.
                    if (actionErrorMessage != null) {
                        InlineErrorStrip(
                            message = actionErrorMessage,
                            onRetry = onActionRetry,
                            modifier = Modifier.padding(
                                start = grid.s20,
                                top = grid.s12,
                                end = grid.s20,
                                bottom = grid.s8,
                            ),
                        )
                    }
                    // The unread summary sits directly above the list, not in the header: it counts
                    // the rows on screen and is hidden (space collapsed, not unmounted abruptly) at zero.
                    val unreadCount = notifications.count { !notificationIsRead(it) }
                    // Hold the last non-zero count so the exit animation never prints "0 unread".
                    var shownUnreadCount by remember { mutableIntStateOf(0) }
                    if (unreadCount > 0) shownUnreadCount = unreadCount
                    AnimatedVisibility(
                        visible = unreadCount > 0,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.ui_notifications_unread_count,
                                shownUnreadCount,
                                shownUnreadCount,
                            ),
                            style = type.micro,
                            color = UiTextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .padding(
                                    start = grid.s20,
                                    top = grid.s8,
                                    end = grid.s20,
                                    bottom = grid.s8,
                                ),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = grid.s24),
                    ) {
                        items(notifications, key = { it.notificationId }) { item ->
                            NotificationRow(
                                item = item,
                                navigable = navigatesOnTap(item),
                                onClick = { onNotificationClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationRow(
    item: NotificationResponse,
    navigable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = LocalGrid.current
    val type = LocalType.current
    val density = LocalDensity.current
    val isRead = notificationIsRead(item)
    val tone = notificationRowTone(isRead)
    val timestamp = RelativeTimeUtils.formatRelativeOrDash(item.createdAt)
    val stateLabel = stringResource(
        if (isRead) R.string.ui_notifications_read else R.string.ui_notifications_unread,
    )
    // The 8dp dot rides on the title line. The Row is centre-aligned inside its 88dp floor, so the
    // title does not sit at a fixed padding offset; measure it instead. The padding-based value is
    // the fallback for the first frame, before any layout callback has run.
    val fallbackMarkerTopPx = with(density) {
        (grid.s12 + type.title.lineHeight.toDp() / 2 - grid.s4).toPx()
    }
    val dotRadiusPx = with(density) { grid.s4.toPx() }
    // Raw positions live outside state: a scroll moves both, and the derived offset does not change,
    // so only the offset is observed and a scrolling list never recomposes its rows.
    val markerAnchor = remember { MarkerAnchor() }
    var markerTopPx by remember { mutableFloatStateOf(fallbackMarkerTopPx) }
    val publishMarkerTop = {
        val centre = markerAnchor.titleCentre
        val boxTop = markerAnchor.boxTop
        if (!centre.isNaN() && !boxTop.isNaN()) {
            val measured = centre - boxTop - dotRadiusPx
            if (measured != markerTopPx) markerTopPx = measured
        }
    }
    val markerTop = with(density) { markerTopPx.toDp() }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    markerAnchor.boxTop = coordinates.boundsInRoot().top
                    publishMarkerTop()
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimensionResource(R.dimen.ui_row_height_tall))
                    .background(tone.background)
                    .clickable(onClick = onClick)
                    // Before the padding so the node's bounds are the whole 88dp row (§9.6).
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        stateDescription = stateLabel
                    }
                    .padding(
                        start = grid.s20,
                        end = grid.s16,
                        top = grid.s12,
                        bottom = grid.s12,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(grid.rowTile)
                        .clip(RoundedCornerShape(grid.radiusCard))
                        .background(tone.tile),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(resolveNotificationIcon(item.notificationType)),
                        contentDescription = null,
                        tint = tone.iconTint,
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_md)),
                    )
                }

                Spacer(modifier = Modifier.width(grid.s16))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            style = type.title,
                            color = tone.titleColor,
                            fontWeight = tone.titleWeight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInRoot()
                                    markerAnchor.titleCentre = bounds.top + bounds.height / 2
                                    publishMarkerTop()
                                },
                        )
                        Spacer(modifier = Modifier.width(grid.s8))
                        Text(
                            text = timestamp,
                            style = type.micro,
                            color = tone.timestampColor,
                            maxLines = 1,
                            // Reads after the body: title, body, time, state, Button.
                            modifier = Modifier.semantics { traversalIndex = 1f },
                        )
                    }

                    Spacer(modifier = Modifier.height(grid.s2))

                    Text(
                        text = item.message,
                        style = type.bodyS,
                        color = UiTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (navigable) {
                    Spacer(modifier = Modifier.width(grid.s8))
                    Icon(
                        painter = painterResource(AppIcons.Chevron),
                        contentDescription = null,
                        tint = UiTextSecondary,
                        modifier = Modifier.size(dimensionResource(R.dimen.icon_sm)),
                    )
                }
            }

            // Non-layout overlay: the marker paints over the gutter and never shifts the tile.
            // `offset` is direction-aware, so the start-edge inset needs no manual RTL mirror.
            if (!isRead) {
                Box(
                    modifier = Modifier
                        .offset(x = grid.s4, y = markerTop)
                        .size(grid.s8)
                        .clip(CircleShape)
                        .background(UiPrimary)
                        .clearAndSetSemantics {
                            testTag = NotificationUnreadMarkerTag
                            invisibleToUser()
                        },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.stroke_hairline))
                .background(UiBackground),
        ) {
            Spacer(modifier = Modifier.width(grid.dividerInset))
            Box(modifier = Modifier.fillMaxWidth().height(dimensionResource(R.dimen.stroke_hairline)).background(UiHairline))
        }
    }
}

/** Test handle for the unread dot; it carries no accessibility node of its own. */
internal const val NotificationUnreadMarkerTag = "notification_unread_marker"

/** Raw marker anchors, deliberately not snapshot state: only the derived offset is observed. */
private class MarkerAnchor {
    var boxTop: Float = Float.NaN
    var titleCentre: Float = Float.NaN
}

internal fun notificationIsRead(item: NotificationResponse): Boolean =
    item.status == NotificationStatus.READ || item.readAt != null

/** Unread titles carry 600 weight; read titles sit at the body's 400. */
internal fun notificationTitleWeight(isRead: Boolean): FontWeight =
    if (isRead) FontWeight.Normal else FontWeight.SemiBold

/** Every per-state colour of a row, in one place so the tones cannot drift apart. */
internal data class NotificationRowTone(
    val background: Color,
    val titleColor: Color,
    val titleWeight: FontWeight,
    val timestampColor: Color,
    val tile: Color,
    val iconTint: Color,
)

@ReadOnlyComposable
@Composable
internal fun notificationRowTone(isRead: Boolean): NotificationRowTone {
    val titleWeight = notificationTitleWeight(isRead)
    return if (isRead) {
        NotificationRowTone(
            background = UiBackground,
            titleColor = UiTextSecondary,
            titleWeight = titleWeight,
            timestampColor = UiTextSecondary,
            tile = UiSurfaceSunken,
            iconTint = UiTextSecondary,
        )
    } else {
        NotificationRowTone(
            background = UiPrimaryWash,
            titleColor = UiTextPrimary,
            titleWeight = titleWeight,
            timestampColor = UiPrimary,
            tile = UiPrimaryContainer,
            iconTint = UiPrimary,
        )
    }
}

/** One icon per type; colour is not meaning here, so every icon is `primary` on the tonal tile. */
@DrawableRes
internal fun resolveNotificationIcon(type: NotificationType?): Int = when (type) {
    NotificationType.STAFF_ASSIGNMENT -> AppIcons.People
    NotificationType.REGISTRATION_NEW, NotificationType.REGISTRATION_CONFIRMED -> AppIcons.Success
    NotificationType.CAPACITY_WARNING, NotificationType.CAPACITY_FULL -> AppIcons.Warning
    NotificationType.REWARD_EXHAUSTED -> AppIcons.Reward
    NotificationType.REWARD_REDEEMED -> AppIcons.Reward
    NotificationType.POINTS_ADJUSTED -> AppIcons.Success
    NotificationType.EVENT_APPROVED -> AppIcons.Success
    NotificationType.EVENT_REJECTED -> AppIcons.Error
    NotificationType.EVENT_STARTING_SOON -> AppIcons.Time
    NotificationType.EVENT_COMPLETED -> AppIcons.Success
    NotificationType.SCAN_REJECTED -> AppIcons.Error
    NotificationType.SCAN_APPROVED -> AppIcons.Success
    else -> AppIcons.Notification
}
