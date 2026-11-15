package com.thedavelopers.eventqr.features.organizer.rewards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.RewardStatus
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The reward card's marks and its action pair (§5.1 inline pair + destructive text, §5.4 v1.62
 * status mark, §6.4 manage rewards).
 *
 * A row is one geometry with two tones and one quiet/loud rule: `Edit` (secondary tonal, flexible) and
 * `Remove` (quiet destructive text, wrap_content) share the 48dp inline box and one label baseline; the
 * AVAILABLE mark is a dot + word while the two exception states wear a bordered strong pill.
 *
 * Tones are read off rendered pixels, not off drawable objects: Robolectric reports the same
 * `GradientDrawable` colour for every state of a selector, so the drawn result is the only truth.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "xxxhdpi", shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManageRewardActionsTest {

    private fun themedContext(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_EventQR)

    private fun dp(id: Int): Int = themedContext().resources.getDimensionPixelSize(id)

    private val dpScale: Float get() = themedContext().resources.displayMetrics.density

    private fun color(id: Int): Int = ContextCompat.getColor(ApplicationProvider.getApplicationContext(), id)

    private fun reward(
        name: String = "Free T-shirt",
        status: RewardStatus = RewardStatus.ACTIVE,
        claimed: Long = 0L,
        total: Int? = null,
    ) = RewardResponse(
        rewardId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        name = name,
        pointsRequired = 50,
        status = status,
        claimedCount = claimed,
        totalQuantity = total,
    )

    /** A card bound by the real activity, measured at the 320dp width it gets on a 360dp screen. */
    private fun card(reward: RewardResponse = reward(), rewardsEnabled: Boolean = true): View {
        val activity = Robolectric.buildActivity(ManageRewardsActivity::class.java).create().get()
        val view = activity.rewardCard(reward, rewardsEnabled)
        view.measure(
            View.MeasureSpec.makeMeasureSpec((320 * dpScale).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** The plain layout, for the geometry the binding does not change. */
    private fun row(): View {
        val context = themedContext()
        val root = LayoutInflater.from(context).inflate(R.layout.item_manage_reward, LinearLayout(context), false)
        root.measure(
            View.MeasureSpec.makeMeasureSpec((320 * dpScale).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun View.button(id: Int): TextView = findViewById(id)

    private fun render(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    /** Offset of this view inside [root], so a pixel read off the card can be aimed at a child. */
    private fun View.offsetIn(root: View): IntArray {
        var x = 0
        var y = 0
        var view: View = this
        while (view !== root) {
            x += view.left
            y += view.top
            view = view.parent as View
        }
        return intArrayOf(x, y)
    }

    /** Colour at (x, y) pixels of the card bitmap, aimed [xDp]/[yDp] from [view]'s top-left. */
    private fun Bitmap.at(view: View, root: View, xDp: Float, yDp: Float): Int {
        val offset = view.offsetIn(root)
        return getPixel(offset[0] + (xDp * dpScale).toInt(), offset[1] + (yDp * dpScale).toInt())
    }

    // ---- the chevron: the card's edit affordance -----------------------------

    /** [View.right] is parent-relative, so a card-wide edge has to be read in the card's coordinates. */
    private fun View.absoluteRight(root: View): Int = offsetIn(root)[0] + width

    @Test
    fun theCardCarriesAChevronAndNoRemoveButton() {
        val root = card(reward(claimed = 3, total = 10))
        val chevron = root.findViewById<View>(R.id.mrw_reward_chevron)

        assertEquals(View.VISIBLE, chevron.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, chevron.importantForAccessibility)
        // Remove moved into the edit sheet (v1.64).
        assertNull(root.findViewById<View>(R.id.mrw_reward_remove))
    }

    @Test
    fun theTrackAndTheChevron_shareTheCardsRightContentEdge() {
        val root = card(reward(claimed = 10, total = 10))
        val chevron = root.findViewById<View>(R.id.mrw_reward_chevron)
        val contentEnd = root.width - root.paddingRight

        assertEquals(contentEnd, track(root).absoluteRight(root))
        assertEquals(contentEnd, chevron.absoluteRight(root))
    }
    // ---- the state mark: quiet normal, loud exception ---------------------

    @Test
    fun availableMark_isAQuietDotAndWordWithNoFill() {
        val root = card(reward(claimed = 3, total = 10))
        val badge = root.button(R.id.mrw_reward_badge)
        val dot = root.findViewById<View>(R.id.mrw_reward_status_dot)

        assertEquals("Available", badge.text.toString())
        assertEquals(View.VISIBLE, dot.visibility)
        // v1.63: a live reward is a success tone, not the info blue it used to wear.
        assertEquals(color(R.color.ui_success), dot.backgroundTintList?.defaultColor)
        // No pill: the mark carries no fill, no tint and no badge padding.
        assertNull(badge.background)
        assertNull(badge.backgroundTintList)
        assertEquals(0, badge.paddingLeft)
        assertEquals(0, badge.paddingRight)
        assertEquals(color(R.color.ui_text_secondary), badge.currentTextColor)
        // The dot itself is painted in the state's tone (6dp, sampled at its centre).
        assertEquals(color(R.color.ui_success), render(root).at(dot, root, 3f, 3f))
        // No blanket alpha anywhere on the card.
        assertEquals(1f, root.alpha, 0f)
    }

    @Test
    fun disabledMark_isAStrongBorderedPill_andTheBodyIsDimmedByInk() {
        val root = card(reward(status = RewardStatus.INACTIVE, claimed = 3, total = 10))
        val badge = root.button(R.id.mrw_reward_badge)
        val pixels = render(root)

        assertEquals("Disabled", badge.text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.mrw_reward_status_dot).visibility)

        // Strong pill: ui_surface_sunken fill inside a 1dp ui_outline_interactive boundary.
        assertEquals(color(R.color.ui_surface_sunken), pixels.at(badge, root, 6f, badge.height / dpScale / 2))
        assertEquals(color(R.color.ui_outline_interactive), pixels.at(badge, root, badge.width / dpScale / 2, 0.25f))
        assertEquals(color(R.color.ui_text_primary), badge.currentTextColor)

        // Dimmed body: secondary ink on the name and the glyph, and nothing hidden or faded.
        assertEquals(color(R.color.ui_text_secondary), root.button(R.id.mrw_reward_name).currentTextColor)
        assertEquals(
            color(R.color.ui_text_secondary),
            root.findViewById<ImageView>(R.id.imgRewardIcon).imageTintList?.defaultColor,
        )
        assertEquals(1f, root.alpha, 0f)
    }

    @Test
    fun outOfStockMark_isAStrongErrorPill_andTheBodyKeepsPrimaryInk() {
        val root = card(reward(claimed = 10, total = 10))
        val badge = root.button(R.id.mrw_reward_badge)
        val pixels = render(root)

        assertEquals(View.GONE, root.findViewById<View>(R.id.mrw_reward_status_dot).visibility)
        assertEquals(color(R.color.ui_error_container), pixels.at(badge, root, 6f, badge.height / dpScale / 2))
        assertEquals(color(R.color.ui_error), pixels.at(badge, root, badge.width / dpScale / 2, 0.25f))
        assertEquals(color(R.color.ui_on_error_container), badge.currentTextColor)

        // Only a disabled reward dims its body; an empty shelf is still a live reward.
        assertEquals(color(R.color.ui_text_primary), root.button(R.id.mrw_reward_name).currentTextColor)
    }

    @Test
    fun theDisabledWordNamesItsCause() {
        val root = card(reward(status = RewardStatus.INACTIVE), rewardsEnabled = true)
        assertEquals("Disabled", root.button(R.id.mrw_reward_badge).text.toString())

        // The event-level switch says why the card is dead; the pill tone is the same, the word is not.
        val offRoot = card(reward(), rewardsEnabled = false)
        val offBadge = offRoot.button(R.id.mrw_reward_badge)
        assertEquals("Rewards off", offBadge.text.toString())
        assertEquals(color(R.color.ui_text_primary), offBadge.currentTextColor)
        assertEquals(
            color(R.color.ui_surface_sunken),
            render(offRoot).at(offBadge, offRoot, 6f, offBadge.height / dpScale / 2),
        )
    }

    /** v1.64: the whole card edits (no Edit button); a dead card is tappable too. */
    @Test
    fun theWholeCardIsTheEditTarget_onEveryState() {
        val roots = listOf(
            card(reward(claimed = 3, total = 10)),
            card(reward(status = RewardStatus.INACTIVE, claimed = 3, total = 10)),
            card(reward(claimed = 3, total = 10), rewardsEnabled = false),
        )
        for (root in roots) {
            assertTrue(root.isClickable)
            assertTrue(root.isFocusable)
            assertEquals(R.id.mrw_reward_edit, root.id)
        }
    }

    // ---- the claims track -------------------------------------------------

    private fun track(root: View): ProgressBar = root.findViewById(R.id.mrw_reward_progress)

    @Test
    fun claimsTrack_readsTheRatio_andCarriesNoAccessibilityNode() {
        val partial = card(reward(claimed = 5, total = 10)).let { it to track(it) }
        assertEquals(View.VISIBLE, partial.second.visibility)
        assertEquals(10, partial.second.max)
        assertEquals(5, partial.second.progress)

        // v1.63: zero claims draws no track at all - an empty one reads as a divider across the card.
        val empty = card(reward(claimed = 0, total = 10)).let { it to track(it) }
        assertEquals(View.GONE, empty.second.visibility)

        // Full is the out-of-stock card: the track states the shelf, so it stays.
        val full = card(reward(claimed = 10, total = 10)).let { it to track(it) }
        assertEquals(View.VISIBLE, full.second.visibility)
        assertEquals(full.second.max, full.second.progress)

        // Decorative: the claimed line states the fact, so the bar is not a node of its own.
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, full.second.importantForAccessibility)
    }

    @Test
    fun claimsTrack_isHiddenForUnlimitedSupply_atZeroClaims_andForABlockedReward() {
        assertEquals(View.GONE, track(card(reward(claimed = 250, total = null))).visibility)
        assertEquals(View.GONE, track(card(reward(claimed = 0, total = 0))).visibility)
        assertEquals(View.GONE, track(card(reward(claimed = 0, total = 10))).visibility)
        assertEquals(View.GONE, track(card(reward(status = RewardStatus.INACTIVE, claimed = 3, total = 10))).visibility)
        assertEquals(View.GONE, track(card(reward(claimed = 3, total = 10), rewardsEnabled = false)).visibility)
    }

    @Test
    fun claimsTrack_paintsTheClampedRatio() {
        val partial = card(reward(claimed = 5, total = 10))
        val partialTrack = track(partial)
        val partialPixels = render(partial)
        val midY = dp(R.dimen.ui_progress_height) / dpScale / 2
        val width = partialTrack.width / dpScale

        assertEquals(color(R.color.ui_primary), partialPixels.at(partialTrack, partial, 0.5f, midY))
        assertEquals(color(R.color.ui_surface_sunken), partialPixels.at(partialTrack, partial, width - 0.5f, midY))

        // Nothing claimed: no track, and the claimed line above carries the "0/10" on its own.
        val empty = card(reward(claimed = 0, total = 10))
        assertEquals(View.GONE, track(empty).visibility)
    }

    // ---- accessibility + dynamic type ------------------------------------

    @Test
    fun theStatusWordIsTheLabel_andTheDotIsNotANode() {
        val available = card(reward(claimed = 1, total = 10))
        val badge = available.button(R.id.mrw_reward_badge)
        val dot = available.findViewById<View>(R.id.mrw_reward_status_dot)

        assertNull("the label is the text, not a description", badge.contentDescription)
        assertTrue(badge.text.isNotEmpty())
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, dot.importantForAccessibility)
        // The word stays a node of its own: the dot is decorative, the label is not excluded.
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, badge.importantForAccessibility)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, badge.importantForAccessibility)

        // The pill is the only node too, and its word is the whole cause - no repeat in a description.
        val rewardsOff = card(reward(), rewardsEnabled = false).button(R.id.mrw_reward_badge)
        assertEquals("Rewards off", rewardsOff.text.toString())
        assertNull(rewardsOff.contentDescription)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun atDoubleFontScale_nothingOverlapsTheNameOrClipsTheMarks() {
        // The longest label of the three is the error pill's, so every state is measured.
        val rewards = listOf(
            reward(name = "Free commemorative t-shirt, limited run", claimed = 5, total = 10),
            reward(name = "Free commemorative t-shirt, limited run", status = RewardStatus.INACTIVE, claimed = 5, total = 10),
            reward(name = "Free commemorative t-shirt, limited run", claimed = 10, total = 10),
        )
        for (subject in rewards) {
            val root = card(subject)
            val name = root.button(R.id.mrw_reward_name)
            val badge = root.button(R.id.mrw_reward_badge)
            val textColumn = name.parent as View
            val statusColumn = (badge.parent as View).parent as View
            val headerRow = statusColumn.parent as View

            // The name ellipsizes on one line; the status column keeps its own space beside it.
            assertEquals(1, name.maxLines)
            assertTrue(
                "status starts at ${statusColumn.left} but the text column ends at ${textColumn.right}",
                statusColumn.left >= textColumn.right,
            )
            // No state pushes its mark past the card: the header row is the right edge.
            assertTrue(
                "status ends at ${statusColumn.right} in a ${headerRow.width} wide row",
                statusColumn.right <= headerRow.width,
            )
            // The word is not clipped by its own box: the badge is at least two lines tall when it wraps.
            assertTrue(badge.measuredHeight >= badge.layout.height)
            assertTrue(badge.measuredHeight >= dp(R.dimen.ui_badge_height))

            // Nothing is painted past the card's own box.
            assertEquals(root.measuredWidth, root.width)
            assertEquals(root.measuredHeight, root.height)
        }
    }
}
