package com.thedavelopers.eventqr.features.attendee

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.ui.icons.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.abs

/**
 * Profile (§6.2): everything scrolls, the identity is one TalkBack node, and the two sections are
 * `list_row_icon` rows bound through [com.thedavelopers.eventqr.ui.components.ListRowBinder]:
 * ACCOUNT (Edit profile only) above ACTIVITY. Every role reaches this screen, so it holds no
 * portal switch.
 *
 * The screen's only load is a network call, so [render] drives the pre-load pass through Retry:
 * `renderProfile(null)` runs synchronously from the cached session values, before the request
 * resolves, exactly as it does on the first frame in production.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AttendeeProfileScreenTest {

    private fun launch(
        role: AccountRole? = AccountRole.ATTENDEE,
        name: String = "Juan Dela Cruz",
        phone: String? = null,
    ): AttendeeProfileActivity {
        SessionManager(ApplicationProvider.getApplicationContext()).apply {
            saveRole(role)
            updateProfile(name, phone, "juan@example.com")
        }
        return Robolectric.buildActivity(AttendeeProfileActivity::class.java).create().get()
    }

    /** Runs the synchronous half of `loadProfile()`: identity, account rows, skeleton. */
    private fun render(activity: AttendeeProfileActivity) {
        activity.findViewById<View>(R.id.btnProfileRetry).performClick()
    }

    private fun View.find(id: Int): View = findViewById(id)

    private fun measure(view: View, widthPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun View.isInsideScrollView(): Boolean {
        var node: View? = this
        while (node != null) {
            if (node is ScrollView) return true
            node = node.parent as? View
        }
        return false
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun View.isDescendantOf(id: Int): Boolean {
        var node: View? = parent as? View
        while (node != null) {
            if (node.id == id) return true
            node = node.parent as? View
        }
        return false
    }

    // -- Structure ---------------------------------------------------------------

    @Test
    fun identity_isInsideTheScrollView_andTheRolePillIsDeleted() {
        val activity = launch()

        assertTrue(
            "the identity block must scroll with the page, not sit above it",
            activity.findViewById<View>(R.id.headerProfile).isInsideScrollView(),
        )
        assertNotNull(activity.findViewById<View>(R.id.swipeRefreshProfile))

        assertEquals(
            "the role pill is deleted: every role reaches this screen, so it described nothing",
            0,
            activity.resources.getIdentifier("txtProfileRole", "id", activity.packageName),
        )
        // Positive control: the absence check above cannot pass vacuously.
        assertTrue(activity.resources.getIdentifier("txtProfileName", "id", activity.packageName) != 0)
    }

    /**
     * v1.41: the page opens on the shared compact pass head. It is a sibling *above*
     * `swipeRefreshProfile`, not inside its ScrollView - that is what pins it while the page moves -
     * and it is the title-only variant: the role eyebrow stays retired (v1.40), so its slot stays
     * `INVISIBLE` and row0 keeps its spacing, and a profile has no result set, so no count.
     */
    @Test
    fun profileHead_isThePinnedCompactBandAboveTheScroll() {
        val activity = launch()

        val head = activity.findViewById<ViewGroup>(R.id.passHead)
        val scroll = activity.findViewById<View>(R.id.swipeRefreshProfile)
        assertNotNull(head)
        assertNotNull(scroll)
        assertSame("the head and the scroll share one parent", head.parent, scroll.parent)
        assertFalse("the head must not scroll with the page", head.isInsideScrollView())
        val siblings = (head.parent as ViewGroup).children()
        assertTrue(
            "the head is pinned above the scroll it titles",
            siblings.indexOf(head) < siblings.indexOf(scroll),
        )

        assertEquals(
            "the title is the page's own label, not the person's name",
            activity.getString(R.string.ui_attendee_profile_title),
            (head.find(R.id.passHeadTitle) as TextView).text.toString(),
        )
        assertEquals(
            "the role eyebrow stays retired (v1.40)",
            View.INVISIBLE,
            head.find(R.id.passHeadEyebrow).visibility,
        )
        assertEquals(
            "a profile has no result set, so the count slot stays GONE",
            View.GONE,
            head.find(R.id.passHeadTitleCount).visibility,
        )
    }

    @Test
    fun activitySection_isOneCardWithFourRowsInOrder() {
        val activity = launch()

        val card = activity.findViewById<ViewGroup>(R.id.cardActivityMenu)
        val stack = card.getChildAt(0) as ViewGroup
        assertEquals(
            listOf(R.id.rowMyPasses, R.id.rowTransactionHistory, R.id.rowClaimedRewards, R.id.rowMyEventRequests),
            stack.children().map { it.id },
        )

        val metaRes = listOf(
            R.string.ui_attendee_profile_meta_passes,
            R.string.ui_attendee_profile_meta_history,
            R.string.ui_attendee_profile_meta_rewards,
            R.string.ui_attendee_profile_meta_requests,
        )
        val expected = listOf(
            R.string.ui_attendee_my_passes to AppIcons.Pass,
            R.string.ui_attendee_transaction_history to AppIcons.Logs,
            R.string.ui_attendee_claimed_rewards to AppIcons.Reward,
            R.string.ui_attendee_my_event_requests to AppIcons.Request,
        )
        stack.children().forEachIndexed { index, item ->
            val (titleRes, icon) = expected[index]
            assertEquals(
                "row ${index + 1} title",
                activity.getString(titleRes),
                (item.find(R.id.listRowTitle) as TextView).text.toString(),
            )
            assertEquals(
                "row ${index + 1} meta",
                activity.getString(metaRes[index]),
                (item.find(R.id.listRowMeta) as TextView).text.toString(),
            )
            assertEquals(
                "row ${index + 1} glyph",
                icon,
                shadowOf((item.find(R.id.listRowIcon) as ImageView).drawable).createdFromResId,
            )
        }
        // My passes leads: the dashboard's first chip, and the top-of-mind action.
        assertEquals(
            "My passes is the first row of the card",
            activity.getString(R.string.ui_attendee_my_passes),
            (stack.getChildAt(0).find(R.id.listRowTitle) as TextView).text.toString(),
        )
        // Stock divider: every row but the last one.
        (0 until 3).forEach { index ->
            assertEquals(
                "row ${index + 1} carries the hairline",
                View.VISIBLE,
                stack.getChildAt(index).find(R.id.listRowDivider).visibility,
            )
        }
        assertEquals(View.GONE, stack.getChildAt(3).find(R.id.listRowDivider).visibility)
    }

    private fun classShape(view: View): List<String> =
        if (view is ViewGroup) listOf(view.javaClass.name) + view.children().flatMap { classShape(it) }
        else listOf(view.javaClass.name)

    /**
     * The My passes row is not a special case inside its card: it is the same `list_row_icon`
     * include riding the same card, so it wears the same title style, chevron, 40dp tile, 72dp floor
     * and 76dp-inset hairline as its siblings. Compared against a sibling inflated on the same
     * screen, so a hand-built row cannot pass as "close enough".
     */
    @Test
    fun myPassesRow_isTheSameListRowComponentAsItsSiblings() {
        val activity = launch()
        render(activity)

        val row = activity.findViewById<View>(R.id.rowMyPasses)
        val sibling = activity.findViewById<View>(R.id.rowTransactionHistory)

        assertEquals(
            "the row is the shared list_row_icon include, not bespoke markup",
            classShape(sibling),
            classShape(row),
        )
        assertEquals(
            "the include keeps the row's own wrapping layout params",
            sibling.javaClass,
            row.javaClass,
        )

        val title = row.find(R.id.listRowTitle) as TextView
        val siblingTitle = sibling.find(R.id.listRowTitle) as TextView
        assertEquals("same label size", siblingTitle.textSize, title.textSize, 0f)
        assertEquals("same label ink", siblingTitle.currentTextColor, title.currentTextColor)
        assertEquals("same label line cap", siblingTitle.maxLines, title.maxLines)

        val chevron = row.find(R.id.listRowChevron) as ImageView
        assertEquals("one trailing thing, the chevron", View.VISIBLE, chevron.visibility)
        assertEquals(
            "the same chevron as its siblings",
            shadowOf((sibling.find(R.id.listRowChevron) as ImageView).drawable).createdFromResId,
            shadowOf(chevron.drawable).createdFromResId,
        )

        val container = row.find(R.id.listRowContainer) as ViewGroup
        assertEquals(
            "the 72dp row floor, so the card keeps one rhythm",
            activity.resources.getDimensionPixelSize(R.dimen.ui_row_height),
            container.minimumHeight,
        )
        assertEquals(
            "the 40dp leading tile",
            (sibling.find(R.id.listRowTile) as View).layoutParams.width,
            (row.find(R.id.listRowTile) as View).layoutParams.width,
        )
        val info = AccessibilityNodeInfo.obtain()
        container.onInitializeAccessibilityNodeInfo(info)
        assertEquals(
            "the row is a Button for TalkBack, like its siblings",
            android.widget.Button::class.java.name,
            info.className.toString(),
        )

        val divider = row.find(R.id.listRowDivider) as View
        assertEquals("a hairline between the rows", View.VISIBLE, divider.visibility)
        assertEquals(
            "the divider is inset to the text edge, not the icon",
            activity.resources.getDimensionPixelSize(R.dimen.ui_divider_inset),
            (divider.layoutParams as ViewGroup.MarginLayoutParams).marginStart,
        )
        assertEquals(
            "one hairline thick",
            (sibling.find(R.id.listRowDivider) as View).layoutParams.height,
            divider.layoutParams.height,
        )
    }

    /**
     * History is a screen, not a number: the row keeps its chevron and never prints a count. The
     * balance is per (event, attendee), so a "0" here would be a lie - and one that only shows up
     * after the load, which is why both states are checked.
     */
    @Test
    fun transactionHistory_keepsTheChevron_andNeverPrintsAValue() {
        val activity = launch()
        render(activity)

        fun assertChevronOnly(stage: String) {
            val row = activity.findViewById<View>(R.id.rowTransactionHistory)
            assertEquals(
                "$stage: the value slot stays hidden",
                View.GONE,
                row.find(R.id.listRowValue).visibility,
            )
            assertEquals(
                "$stage: the value slot stays empty, not a zero",
                "",
                (row.find(R.id.listRowValue) as TextView).text.toString(),
            )
            assertEquals(
                "$stage: the row opens a screen, so it keeps its chevron",
                View.VISIBLE,
                row.find(R.id.listRowChevron).visibility,
            )
        }

        assertChevronOnly("while the profile loads")
        activity.findViewById<View>(R.id.layoutProfileSections).visibility = View.VISIBLE
        assertChevronOnly("once the sections land")
    }

    // -- Account: Edit profile is the whole card, for every role -----------------

    @Test
    fun accountSection_isEditProfileOnlyForEveryRole() {
        // A staff or organizer account holds a second portal, and this screen still shows one row:
        // the dashboard role tabs are the portal switch now.
        listOf(AccountRole.ATTENDEE, AccountRole.STAFF, AccountRole.ORGANIZER).forEach { role ->
            val activity = launch(role = role)
            render(activity)

            val account = activity.findViewById<ViewGroup>(R.id.cardAccountMenu)
            val stack = account.getChildAt(0) as ViewGroup

            assertEquals(
                "${role.name}: ACCOUNT holds Edit profile and nothing else",
                listOf(R.id.rowEditProfile),
                stack.children().map { it.id },
            )
            assertEquals(
                "${role.name}: the Switch portal row is deleted, whatever portal the account holds",
                0,
                activity.resources.getIdentifier("rowSwitchPortal", "id", activity.packageName),
            )
            assertEquals(
                "${role.name}: the only row carries no divider",
                View.GONE,
                stack.find(R.id.rowEditProfile).find(R.id.listRowDivider).visibility,
            )
        }
    }

    // -- TalkBack ----------------------------------------------------------------

    @Test
    fun rowsAreButtons_andTheIdentityCarriesTheNameAsAHeading() {
        val activity = launch(role = AccountRole.ORGANIZER, phone = "+639171234567")
        render(activity)

        val rows = listOf(
            R.id.rowMyPasses,
            R.id.rowTransactionHistory,
            R.id.rowClaimedRewards,
            R.id.rowMyEventRequests,
            R.id.rowEditProfile,
        )
        rows.forEach { id ->
            val name = activity.resources.getResourceEntryName(id)
            val container = activity.findViewById<View>(id).find(R.id.listRowContainer)
            val info = AccessibilityNodeInfo.obtain()
            container.onInitializeAccessibilityNodeInfo(info)
            assertEquals(
                "$name must be a Button for TalkBack",
                android.widget.Button::class.java.name,
                info.className.toString(),
            )
        }

        val header = activity.findViewById<View>(R.id.headerProfile)
        assertTrue("the identity block must be a heading", ViewCompat.isAccessibilityHeading(header))
        assertEquals("Juan Dela Cruz, juan@example.com", header.contentDescription.toString())
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            activity.findViewById<View>(R.id.txtProfileInitial).importantForAccessibility,
        )
        assertEquals(
            "the avatar tile reads nothing on its own",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            (activity.findViewById<View>(R.id.txtProfileInitial).parent as View).importantForAccessibility,
        )
        listOf(R.id.eyebrowActivity, R.id.eyebrowAccount).forEach { id ->
            assertTrue(
                "${activity.resources.getResourceEntryName(id)} must be a heading",
                ViewCompat.isAccessibilityHeading(activity.findViewById<View>(id)),
            )
        }
    }

    // -- Edit profile meta -------------------------------------------------------

    @Test
    fun editProfile_printsThePhoneOrTheFallbackLine() {
        val withPhone = launch(phone = "+639171234567")
        render(withPhone)
        val phoneRow = withPhone.findViewById<View>(R.id.rowEditProfile)
        assertEquals("+639171234567", (phoneRow.find(R.id.listRowMeta) as TextView).text.toString())
        assertEquals(View.VISIBLE, phoneRow.find(R.id.listRowMeta).visibility)

        // A fresh session file: a blank phone still prints its line, so the row height is the same
        // with or without the number.
        val empty = launch(phone = null)
        render(empty)
        val emptyRow = empty.findViewById<View>(R.id.rowEditProfile)
        assertEquals(
            empty.getString(R.string.ui_attendee_profile_meta_edit),
            (emptyRow.find(R.id.listRowMeta) as TextView).text.toString(),
        )
        assertEquals(View.VISIBLE, emptyRow.find(R.id.listRowMeta).visibility)
    }

    // -- Large font --------------------------------------------------------------

    @Test
    fun identityEmail_isOneLineAtTheDefaultScale() {
        val activity = launch()
        render(activity)

        assertEquals(1, (activity.findViewById<View>(R.id.txtProfileEmail) as TextView).maxLines)
        assertEquals(2, (activity.findViewById<View>(R.id.txtProfileName) as TextView).maxLines)
        assertEquals(
            1,
            (activity.findViewById<View>(R.id.rowTransactionHistory).find(R.id.listRowTitle) as TextView).maxLines,
        )
    }

    @Test
    @Config(
        sdk = [35],
        shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class],
        fontScale = 2.0f,
    )
    fun emailAndRowTitles_takeASecondLineAboveFontScaleOnePointThree() {
        val activity = launch()
        render(activity)

        assertEquals(2, (activity.findViewById<View>(R.id.txtProfileEmail) as TextView).maxLines)
        assertEquals(
            "a real title must not be cut to an ellipsis at 2.0",
            2,
            (activity.findViewById<View>(R.id.rowTransactionHistory).find(R.id.listRowTitle) as TextView).maxLines,
        )
    }

    // -- Skeleton ----------------------------------------------------------------

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun skeleton_mirrorsTheSectionsItReplaces() {
        val activity = launch()
        render(activity)

        val skeleton = activity.findViewById<ViewGroup>(R.id.skeletonLoading)
        assertEquals(View.VISIBLE, skeleton.visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutProfileSections).visibility)
        assertEquals(
            "the identity block stays on screen, so the skeleton is the sections only",
            activity.getString(R.string.ui_skeleton_loading),
            skeleton.contentDescription.toString(),
        )
        // Two eyebrow bars and the 1 + 4 row stacks, in the loaded order (ACCOUNT first). Each
        // stack carries the loaded card's hairlines between its rows, so a 4-row stack is rows + 3
        // dividers.
        assertEquals(4, skeleton.childCount)
        val accountRows = skeleton.getChildAt(1) as ViewGroup
        val activityRows = skeleton.getChildAt(3) as ViewGroup
        assertEquals(1, accountRows.childCount)
        assertEquals(4 + 3, activityRows.childCount)

        val token = activity.resources.getDimensionPixelSize(R.dimen.ui_skeleton_row_height)
        val rowHeight = activity.resources.getDimensionPixelSize(R.dimen.ui_row_height)
        val tile = activity.resources.getDimensionPixelSize(R.dimen.ui_row_tile)
        val skeletonRows = (activityRows.children() + accountRows.children()).filterIsInstance<ViewGroup>()
        assertEquals("four ACTIVITY rows and one ACCOUNT row", 5, skeletonRows.size)
        skeletonRows.forEach { skeletonRow ->
            assertEquals("every skeleton row is the loaded row height", token, skeletonRow.layoutParams.height)
            assertEquals(
                "the placeholder tile is the loaded row's 40dp tile",
                tile,
                skeletonRow.getChildAt(0).layoutParams.width,
            )
        }

        // The loaded row keeps the same spec: the 72dp floor and the 40dp tile. Real text metrics
        // are measured against those below.
        assertEquals(token, rowHeight)
        activity.findViewById<View>(R.id.layoutProfileSections).visibility = View.VISIBLE
        val loadedRow = activity.findViewById<View>(R.id.cardActivityMenu).find(R.id.rowTransactionHistory)
        assertEquals(rowHeight, loadedRow.find(R.id.listRowContainer).minimumHeight)
        assertEquals(tile, loadedRow.find(R.id.listRowTile).layoutParams.width)

        // Static chrome: Sign out lives outside the skeleton region, so it is on screen from the
        // first frame instead of popping in after the load.
        val chrome = activity.findViewById<View>(R.id.cardSignOut)
        assertEquals(
            "cardSignOut stays visible while the skeleton shows",
            View.VISIBLE,
            chrome.visibility,
        )
        assertFalse(
            "cardSignOut must not be inside the skeleton region",
            chrome.isDescendantOf(R.id.layoutProfileSections),
        )

        // Measured, both surfaces at 360dp wide. A loaded row with its one-line meta lands on 76dp
        // (14 + 26 title + 2 + 20 meta), so each 72dp skeleton row carries one 4dp grid step and the
        // stack repeats the loaded card's three hairlines: 4 x 72 + 4 x 4 + 3 x 1 = 307dp, the loaded
        // card's height. §5.11 allows 4dp of drift, so the card delta is asserted against that.
        val density = activity.resources.displayMetrics.density
        val width = (360f * density).toInt()
        val tolerance = (4 * density).toInt()
        val sections = activity.findViewById<ViewGroup>(R.id.layoutProfileSections)
        measure(skeleton, width)
        measure(sections, width)

        listOf(accountRows to R.id.cardAccountMenu, activityRows to R.id.cardActivityMenu).forEach { (stack, cardId) ->
            val card = activity.findViewById<ViewGroup>(cardId)
            val name = activity.resources.getResourceEntryName(cardId)
            val cardDelta = abs(card.measuredHeight - stack.measuredHeight)
            assertTrue("$name: the loaded card must actually measure", card.measuredHeight > 0)
            assertTrue("$name: the skeleton stack must actually measure", stack.measuredHeight > 0)
            assertTrue(
                "$name: the skeleton card must land within 4dp of the loaded card ($cardDelta px)",
                cardDelta <= tolerance,
            )
        }
    }

    // -- Sign out and the version footer -----------------------------------------

    @Test
    fun accountLeadsActivity_andSignOutClosesThePage() {
        val activity = launch()

        val sections = activity.findViewById<ViewGroup>(R.id.layoutProfileSections)
        val ids = sections.children().map { it.id }
        val account = ids.indexOf(R.id.eyebrowAccount)
        val activityEyebrow = ids.indexOf(R.id.eyebrowActivity)
        assertTrue("ACCOUNT leads the sections", account >= 0)
        assertTrue("ACTIVITY follows ACCOUNT", activityEyebrow > account)
        assertEquals("ACCOUNT's card sits directly under its eyebrow", account + 1, ids.indexOf(R.id.cardAccountMenu))
        assertEquals(
            "ACTIVITY's card sits directly under its eyebrow",
            activityEyebrow + 1,
            ids.indexOf(R.id.cardActivityMenu),
        )

        val signOut = activity.findViewById<View>(R.id.cardSignOut)
        val columnIds = (signOut.parent as ViewGroup).children().map { it.id }
        assertTrue(
            "Sign out comes after both sections",
            columnIds.indexOf(R.id.cardSignOut) > columnIds.indexOf(R.id.layoutProfileSections),
        )
        assertFalse(
            "Sign out must not be inside the sections the skeleton replaces",
            signOut.isDescendantOf(R.id.layoutProfileSections),
        )
    }

    @Test
    fun signOut_isTheFilledDestructiveBlockWithAWhiteLabel() {
        val activity = launch()

        val signOut = activity.findViewById<TextView>(R.id.cardSignOut)
        assertEquals(activity.getString(R.string.ui_attendee_sign_out), signOut.text.toString())
        assertEquals(
            "the filled block: the same ui_error red the outline already used, now as the fill",
            R.drawable.bg_ui_button_destructive_commit,
            shadowOf(signOut.background).createdFromResId,
        )
        assertEquals(
            "the closing block is full width, not a label-sized orphan",
            ViewGroup.LayoutParams.MATCH_PARENT,
            signOut.layoutParams.width,
        )
        assertTrue(
            "the 52dp full-width height, above the 48dp touch floor",
            signOut.minimumHeight >= activity.resources.getDimensionPixelSize(R.dimen.ui_button_height),
        )
        val info = AccessibilityNodeInfo.obtain()
        signOut.onInitializeAccessibilityNodeInfo(info)
        assertEquals(
            "Sign out is a button, not a bare label",
            android.widget.Button::class.java.name,
            info.className.toString(),
        )

        signOut.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog)
        // The confirm dialog is unchanged: title, the shared question, Cancel and Sign out.
        val texts = dialog.window!!.decorView.allTexts()
        assertTrue(
            "the dialog keeps its shared title",
            texts.contains(activity.getString(R.string.common_sign_out)),
        )
        assertTrue(
            "the dialog keeps its question",
            texts.contains(activity.getString(R.string.attendee_profile_are_you_sure_you_want_to_sign_out)),
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNotNull(
            "cancelling leaves the session alone",
            SessionManager(ApplicationProvider.getApplicationContext()).getEmail(),
        )
        assertFalse(activity.isFinishing)
    }

    @Test
    fun signOut_resolvesToTheErrorFillAndTheWhiteLabelTokens() {
        val activity = launch()

        val signOut = activity.findViewById<TextView>(R.id.cardSignOut)
        val states = signOut.background as StateListDrawable
        fun fillOf(state: IntArray): Int {
            states.setState(state)
            return (states.current as GradientDrawable).color!!.defaultColor
        }

        assertEquals(
            "the default fill is the ui_error token, resolved on the built view",
            ContextCompat.getColor(activity, R.color.ui_error),
            fillOf(intArrayOf(android.R.attr.state_enabled)),
        )
        assertEquals(
            "the press state stays visible on the red: ui_on_error_container",
            ContextCompat.getColor(activity, R.color.ui_on_error_container),
            fillOf(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)),
        )
        assertEquals(
            "the label is the white on-error token",
            ContextCompat.getColor(activity, R.color.ui_on_primary),
            signOut.currentTextColor,
        )
        assertEquals(
            "and that token is white, so the red is legible",
            android.graphics.Color.WHITE,
            signOut.currentTextColor,
        )
        // Disabled is the neutral fill, so the white label cannot stay: the family swaps it for the
        // same tone the primary button uses (the sign-out call disables the block mid-request).
        signOut.isEnabled = false
        assertEquals(
            "a disabled block is not white-on-pale-grey",
            ContextCompat.getColor(activity, R.color.ui_outline_interactive),
            signOut.currentTextColor,
        )
    }

    // -- My passes: a row in ACTIVITY, not a strip of chips ----------------------

    /**
     * The correction that retired the copied chip strip: Home keeps its three chips, and the
     * profile reaches the same screen through one ACTIVITY row, in the row language of its
     * siblings. `btnMyPasses` / `attendeeQuickActions` therefore resolve to nothing on this page.
     */
    @Test
    fun myPasses_isARowInTheActivityCard_andTheQuickActionStripIsGone() {
        val activity = launch()

        assertNotNull(
            "My passes lives in the ACTIVITY card",
            activity.findViewById<View>(R.id.cardActivityMenu).find(R.id.rowMyPasses),
        )
        assertEquals(
            "the strip's container is not part of this screen",
            null,
            activity.findViewById<View>(R.id.attendeeQuickActions),
        )
        assertEquals(
            "and neither is its chip, so the two cannot disagree about what the page offers",
            null,
            activity.findViewById<View>(R.id.btnMyPasses),
        )
        assertEquals(
            "no QUICK ACTIONS eyebrow survives on the profile",
            null,
            activity.findViewById<View>(R.id.eyebrowQuickActions),
        )
    }

    @Test
    fun myPassesRow_opensRegisteredEventsThroughTheDashboardChipMapping() {
        val activity = launch()
        render(activity)

        assertEquals(
            "the row and the dashboard chip resolve the same destination",
            attendeeQuickActionDestination(R.id.btnMyPasses),
            RegisteredEventsActivity::class.java,
        )

        activity.findViewById<View>(R.id.rowMyPasses).find(R.id.listRowContainer).performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(
            "My passes opens the registered-events screen",
            RegisteredEventsActivity::class.java.name,
            started.component?.className,
        )
        assertEquals("with no extras, exactly like the chip", null, started.extras)
    }

    @Test
    fun versionFooter_isDeletedNotHidden() {
        val activity = launch()

        assertEquals(
            "the version footer is gone from the profile, id included",
            0,
            activity.resources.getIdentifier("txtProfileVersion", "id", activity.packageName),
        )
        assertEquals(
            "and its string is unused, so it is deleted with the view",
            0,
            activity.resources.getIdentifier("ui_attendee_version", "string", activity.packageName),
        )
        // Positive control: the page still ends on Sign out.
        assertNotNull(activity.findViewById<View>(R.id.cardSignOut))
    }
}
