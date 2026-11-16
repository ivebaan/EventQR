package com.thedavelopers.eventqr.features.notifications

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.EventQrTheme
import com.thedavelopers.eventqr.ui.theme.UiBackground
import com.thedavelopers.eventqr.ui.theme.UiPrimary
import com.thedavelopers.eventqr.ui.theme.UiPrimaryContainer
import com.thedavelopers.eventqr.ui.theme.UiPrimaryWash
import com.thedavelopers.eventqr.ui.theme.UiSurfaceSunken
import com.thedavelopers.eventqr.ui.theme.UiTextSecondary
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationsScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun unreadNotification_announcesUnreadState() {
        setCard(item = notification(status = NotificationStatus.PENDING))

        composeTestRule.onNode(hasStateDescription("Unread")).assertIsDisplayed()
    }

    @Test
    fun readNotification_announcesReadState() {
        setCard(item = notification(status = NotificationStatus.READ))

        composeTestRule.onNode(hasStateDescription("Read")).assertIsDisplayed()
    }

    @Test
    fun readAtTimestamp_marksNotificationReadRegardlessOfStatus() {
        setCard(item = notification(status = NotificationStatus.PENDING, readAt = Instant.now()))

        composeTestRule.onNode(hasStateDescription("Read")).assertIsDisplayed()
        composeTestRule.onNode(hasStateDescription("Unread")).assertDoesNotExist()
    }

    @Test
    fun unreadNotification_doesNotClaimToBeRead() {
        setCard(item = notification(status = NotificationStatus.PENDING))

        composeTestRule.onNode(hasStateDescription("Read")).assertDoesNotExist()
    }

    @Test
    fun unreadNotification_carriesTheButtonRole() {
        setCard(item = notification(status = NotificationStatus.PENDING))

        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsDisplayed()
    }

    @Test
    fun readNotification_carriesTheButtonRole() {
        setCard(item = notification(status = NotificationStatus.READ))

        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsDisplayed()
    }

    @Test
    fun unreadMarker_isOnlyOnUnreadRows() {
        setCard(item = notification(status = NotificationStatus.PENDING))
        composeTestRule.onNodeWithTag(NotificationUnreadMarkerTag).assertExists()
    }

    @Test
    fun readRow_carriesNoUnreadMarker() {
        setCard(item = notification(status = NotificationStatus.READ))

        composeTestRule.onNodeWithTag(NotificationUnreadMarkerTag).assertDoesNotExist()
    }

    @Test
    fun titleWeight_distinguishesUnreadFromRead() {
        assertEquals(FontWeight.SemiBold, notificationTitleWeight(isRead = false))
        assertEquals(FontWeight.Normal, notificationTitleWeight(isRead = true))
    }

    @Test
    fun unreadTone_pinsTheUnreadPalette() {
        var tone: NotificationRowTone? = null
        var wash: Color? = null
        var container: Color? = null
        var primary: Color? = null

        composeTestRule.setContent {
            EventQrTheme {
                tone = notificationRowTone(isRead = false)
                wash = UiPrimaryWash
                container = UiPrimaryContainer
                primary = UiPrimary
            }
        }

        val actual = requireNotNull(tone)
        assertEquals(wash, actual.background)
        assertEquals(container, actual.tile)
        assertEquals(primary, actual.timestampColor)
        assertEquals(FontWeight.SemiBold, actual.titleWeight)
    }

    @Test
    fun readTone_pinsTheReadPalette() {
        var tone: NotificationRowTone? = null
        var background: Color? = null
        var sunken: Color? = null
        var secondary: Color? = null

        composeTestRule.setContent {
            EventQrTheme {
                tone = notificationRowTone(isRead = true)
                background = UiBackground
                sunken = UiSurfaceSunken
                secondary = UiTextSecondary
            }
        }

        val actual = requireNotNull(tone)
        assertEquals(background, actual.background)
        assertEquals(sunken, actual.tile)
        assertEquals(secondary, actual.timestampColor)
        assertEquals(FontWeight.Normal, actual.titleWeight)
    }

    @Test
    fun unreadCountStrip_readsTheLoadedUnreadRows() {
        setScreen(
            listOf(
                notification(status = NotificationStatus.PENDING),
                notification(status = NotificationStatus.READ),
            ),
        )

        composeTestRule.onNodeWithText("1 unread").assertIsDisplayed()
    }

    @Test
    fun unreadCountStrip_usesTheOtherQuantity() {
        setScreen(
            listOf(
                notification(status = NotificationStatus.PENDING),
                notification(status = NotificationStatus.PENDING),
                notification(status = NotificationStatus.PENDING),
            ),
        )

        composeTestRule.onNodeWithText("3 unread").assertIsDisplayed()
    }

    @Test
    fun unreadCountStrip_isAbsentWhenNothingIsUnread() {
        setScreen(listOf(notification(status = NotificationStatus.READ)))

        composeTestRule
            .onNode(hasText("unread", substring = true))
            .assertDoesNotExist()
    }

    @Test
    fun notificationRow_invokesClickCallback() {
        var clicks = 0
        setCard(item = notification(), onClick = { clicks++ })

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, clicks)
    }

    @Test
    fun notificationRow_keepsTitleVisibleAlongsideStateDescription() {
        setCard(item = notification())

        composeTestRule.onNodeWithText("Registration confirmed").assertIsDisplayed()
        composeTestRule.onNode(hasStateDescription("Unread")).assertIsDisplayed()
    }

    @Test
    fun rejectionWithoutAnEventId_stillComposesARow() {
        setCard(item = notification(eventId = null, type = NotificationType.EVENT_REJECTED))

        composeTestRule.onNode(hasClickAction()).assertHasClickAction()
    }

    @Test
    fun approvedNotifications_useTheCheckMarkIcon() {
        var eventApprovedIcon = 0
        var scanApprovedIcon = 0
        var registrationConfirmedIcon = 0

        composeTestRule.setContent {
            EventQrTheme {
                eventApprovedIcon = resolveNotificationIcon(NotificationType.EVENT_APPROVED)
                scanApprovedIcon = resolveNotificationIcon(NotificationType.SCAN_APPROVED)
                registrationConfirmedIcon = resolveNotificationIcon(NotificationType.REGISTRATION_CONFIRMED)
            }
        }

        assertEquals(AppIcons.Success, eventApprovedIcon)
        assertEquals(AppIcons.Success, scanApprovedIcon)
        assertEquals(AppIcons.Success, registrationConfirmedIcon)
    }
    private fun notification(
        status: NotificationStatus = NotificationStatus.PENDING,
        readAt: Instant? = null,
        eventId: UUID? = UUID.randomUUID(),
        type: NotificationType = NotificationType.REGISTRATION_NEW,
    ) = NotificationResponse(
        notificationId = UUID.randomUUID(),
        eventId = eventId,
        recipientUserId = UUID.randomUUID(),
        title = "Registration confirmed",
        message = "Your QR code is ready.",
        status = status,
        readAt = readAt,
        createdAt = Instant.now(),
        notificationType = type,
    )

    private fun setCard(item: NotificationResponse, onClick: () -> Unit = {}) {
        composeTestRule.setContent {
            EventQrTheme {
                NotificationRow(item = item, navigable = true, onClick = onClick)
            }
        }
    }

    private fun setScreen(items: List<NotificationResponse>) {
        composeTestRule.setContent {
            EventQrTheme {
                NotificationsScreen(
                    notifications = items,
                    isLoading = false,
                    errorMessage = null,
                    actionFailureCount = 0,
                    onNotificationClick = {},
                    onRetryClick = {},
                    onActionRetry = {},
                    navigatesOnTap = { true },
                )
            }
        }
    }
}
