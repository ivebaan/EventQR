package com.thedavelopers.eventqr.features.organizer.rewards

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.RewardStatus
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RewardCardStateTest {
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    private fun reward(claimed: Long, total: Int?, remaining: Int?) = RewardResponse(
        rewardId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        name = "Coffee",
        description = "A free coffee",
        pointsRequired = 100,
        status = RewardStatus.ACTIVE,
        stockQuantity = remaining,
        claimedCount = claimed,
        totalQuantity = total,
    )

    @Test
    fun fiveOfTen_showsRatioAndIsNotOutOfStock() {
        val state = RewardCardState.of(reward(claimed = 5, total = 10, remaining = 5))
        assertEquals("5/10 claimed", rewardClaimedText(context, state))
        assertFalse(state.outOfStock)
    }

    @Test
    fun tenOfTen_isOutOfStock() {
        val state = RewardCardState.of(reward(claimed = 10, total = 10, remaining = 0))
        assertEquals("10/10 claimed", rewardClaimedText(context, state))
        assertTrue(state.outOfStock)
    }

    @Test
    fun remainingStockIsNeverUsedAsTheTotal() {
        // Remaining stock of 5 must not be read as "5 total": 5 claimed of 10 is half, not sold out.
        val state = RewardCardState.of(reward(claimed = 5, total = 10, remaining = 5))
        assertFalse(state.outOfStock)
        assertEquals(10, state.totalQuantity)
    }

    @Test
    fun claimedFraction_isClampedAndNullWithoutATrack() {
        assertEquals(0.5f, RewardCardState.of(reward(claimed = 5, total = 10, remaining = 5)).claimedFraction)
        assertEquals(0f, RewardCardState.of(reward(claimed = 0, total = 10, remaining = 10)).claimedFraction)
        assertEquals(1f, RewardCardState.of(reward(claimed = 10, total = 10, remaining = 0)).claimedFraction)
        // Over-claimed (server data drift) still paints a full track, never past 100%.
        assertEquals(1f, RewardCardState.of(reward(claimed = 14, total = 10, remaining = 0)).claimedFraction)
        // Unlimited supply and a zero supply have no ratio to draw.
        assertNull(RewardCardState.of(reward(claimed = 250, total = null, remaining = null)).claimedFraction)
        assertNull(RewardCardState.of(reward(claimed = 0, total = 0, remaining = 0)).claimedFraction)
    }

    @Test
    fun unlimited_showsUnlimitedWithNoRatio_andNeverOutOfStock() {
        val state = RewardCardState.of(reward(claimed = 250, total = null, remaining = null))
        assertEquals("Unlimited", rewardClaimedText(context, state))
        assertTrue(state.unlimited)
        assertFalse(state.outOfStock)
    }

    // ---- v1.63: the cause behind a disabled card, and the track's zero-claim rule

    @Test
    fun theEventSwitchOff_isRewardsOff_whileTheRewardsOwnStatusIsDisabled() {
        val live = reward(claimed = 3, total = 10, remaining = 7)

        // The switch disables the whole event's redemption: the word names that, not the reward.
        assertEquals(RewardBadge.REWARDS_OFF, rewardBadge(live, rewardsEnabled = false))
        assertEquals("Rewards off", context.getString(rewardBadge(live, rewardsEnabled = false).labelRes))

        // The reward's own server status is the other cause, and keeps the plain word.
        val inactive = live.copy(status = RewardStatus.INACTIVE)
        assertEquals(RewardBadge.DISABLED, rewardBadge(inactive, rewardsEnabled = true))
        assertEquals("Disabled", context.getString(rewardBadge(inactive, rewardsEnabled = true).labelRes))

        // Both are blocked; both are derived without an Activity.
        assertTrue(RewardBadge.REWARDS_OFF.blocksRedemption)
        assertTrue(RewardBadge.DISABLED.blocksRedemption)
    }

    @Test
    fun availableAndOutOfStock_areUnaffectedByTheCauseSplit() {
        val live = reward(claimed = 3, total = 10, remaining = 7)
        assertEquals(RewardBadge.AVAILABLE, rewardBadge(live, rewardsEnabled = true))
        assertEquals(context.getString(rewardBadge(live, true).labelRes), "Available")
        assertFalse(RewardBadge.AVAILABLE.blocksRedemption)

        val soldOut = reward(claimed = 10, total = 10, remaining = 0)
        assertEquals(RewardBadge.OUT_OF_STOCK, rewardBadge(soldOut, rewardsEnabled = true))
        assertEquals(context.getString(rewardBadge(soldOut, true).labelRes), context.getString(R.string.reward_details_out_of_stock))
        assertFalse(RewardBadge.OUT_OF_STOCK.blocksRedemption)

        // An inactive reward that is also sold out stays a status exception, not an inventory one.
        assertEquals(RewardBadge.DISABLED, rewardBadge(soldOut.copy(status = RewardStatus.INACTIVE), rewardsEnabled = true))
    }

    @Test
    fun claimsTrackFraction_isHiddenAtZeroClaims_andUnchangedOtherwise() {
        // Partial and full paint exactly what claimedFraction paints.
        assertEquals(0.5f, RewardCardState.of(reward(claimed = 5, total = 10, remaining = 5)).claimsTrackFraction)
        assertEquals(1f, RewardCardState.of(reward(claimed = 10, total = 10, remaining = 0)).claimsTrackFraction)
        assertEquals(1f, RewardCardState.of(reward(claimed = 14, total = 10, remaining = 0)).claimsTrackFraction)
        // Zero claims: no sliver, so no track (an empty one reads as a divider).
        assertNull(RewardCardState.of(reward(claimed = 0, total = 10, remaining = 10)).claimsTrackFraction)
        // Unlimited, a zero total and no ratio at all stay hidden too.
        assertNull(RewardCardState.of(reward(claimed = 250, total = null, remaining = null)).claimsTrackFraction)
        assertNull(RewardCardState.of(reward(claimed = 0, total = 0, remaining = 0)).claimsTrackFraction)
    }

    @Test
    fun cost_isShownAsPoints_notCurrency() {
        val resources = context.resources
        assertEquals("100 pts", resources.getQuantityString(R.plurals.manage_rewards_cost_pts, 100, "100"))
        assertEquals("1 pt", resources.getQuantityString(R.plurals.manage_rewards_cost_pts, 1, "1"))
    }

    @Test
    fun editRequest_sendsTotalQuantity_notRemainingStock_andKeepsDescription() {
        val existing = reward(claimed = 5, total = 10, remaining = 5)
        val request = buildRewardRequest(
            eventId = existing.eventId,
            title = existing.name,
            points = existing.pointsRequired,
            quantity = RewardQuantity.Limited(10),
            description = resolveRewardDescription("A free coffee", existing.description, true),
            allowDuplicateClaims = false,
        )
        assertEquals(10, request.totalQuantity)
        assertNull(request.stockQuantity)
        assertEquals("A free coffee", request.description)

        val json = sharedGson().toJsonTree(request).asJsonObject
        assertEquals(10, json.get("totalQuantity").asInt)
        assertFalse(json.has("stockQuantity"))
        assertFalse(json.has("unlimitedStock"))
        assertEquals("A free coffee", json.get("description").asString)
    }

    @Test
    fun blankQuantity_meansUnlimited() {
        assertEquals(RewardQuantity.Unlimited, parseRewardQuantity(""))
        assertEquals(RewardQuantity.Unlimited, parseRewardQuantity("   "))

        val request = buildRewardRequest(UUID.randomUUID(), "Sticker", 5, RewardQuantity.Unlimited, resolveRewardDescription("", null, false), false)
        assertNull(request.totalQuantity)
        assertEquals(true, request.unlimitedStock)
        assertNull("blank description is omitted so the server keeps it", request.description)
        val json = sharedGson().toJsonTree(request).asJsonObject
        assertEquals(true, json.get("unlimitedStock").asBoolean)
        assertFalse(json.has("totalQuantity"))
    }

    @Test
    fun quantityValidation_requiresNonNegativeInteger() {
        assertEquals(RewardQuantity.Limited(0), parseRewardQuantity("0"))
        assertEquals(RewardQuantity.Limited(25), parseRewardQuantity(" 25 "))
        assertEquals(RewardQuantity.Invalid, parseRewardQuantity("-1"))
        assertEquals(RewardQuantity.Invalid, parseRewardQuantity("abc"))
        assertEquals(RewardQuantity.Invalid, parseRewardQuantity("1.5"))
    }

    @Test
    fun response_parsesClaimedCountAndTotalQuantity() {
        val json = """{"rewardId":"${UUID.randomUUID()}","eventId":"${UUID.randomUUID()}","name":"Mug",
            "pointsRequired":50,"status":"ACTIVE","stockQuantity":3,"claimedCount":7,"totalQuantity":10}"""
        val parsed = sharedGson().fromJson(json, RewardResponse::class.java)
        assertEquals(7L, parsed.claimedCount)
        assertEquals(10, parsed.totalQuantity)
        assertEquals(3, parsed.stockQuantity)
    }

    private val gson = sharedGson()
    private fun json(q: RewardQuantity) =
        gson.toJsonTree(buildRewardRequest(UUID.randomUUID(), "Mug", 10, q, null, false)).asJsonObject

    @Test
    fun json_blankQuantity_sendsUnlimitedTrueAndNoTotal() {
        val o = json(parseRewardQuantity(""))
        assertEquals(true, o.get("unlimitedStock").asBoolean)
        assertFalse(o.has("totalQuantity"))
        assertFalse(o.has("stockQuantity"))
    }

    @Test
    fun json_number_sendsTotalAndNoUnlimitedFlag() {
        val o = json(parseRewardQuantity("40"))
        assertEquals(40, o.get("totalQuantity").asInt)
        assertFalse(o.has("unlimitedStock"))
    }

    @Test
    fun json_untouchedQuantityOnEdit_sendsNeitherSoStockIsUnchanged() {
        assertEquals(RewardQuantity.Unchanged, resolveRewardQuantity("10", "10"))
        assertEquals(RewardQuantity.Unchanged, resolveRewardQuantity("", ""))
        val o = json(RewardQuantity.Unchanged)
        assertFalse(o.has("totalQuantity"))
        assertFalse(o.has("unlimitedStock"))
        assertFalse(o.has("stockQuantity"))
        assertEquals(RewardQuantity.Limited(12), resolveRewardQuantity("12", "10"))
        assertEquals(RewardQuantity.Unlimited, resolveRewardQuantity("", "10"))
    }

    @Test
    fun description_clearedSendsEmptyString_unchangedKept_createBlankOmitted() {
        assertEquals("", resolveRewardDescription("  ", "Old text", isEdit = true))
        assertEquals("Old text", resolveRewardDescription("Old text", "Old text", isEdit = true))
        assertEquals("New", resolveRewardDescription("New", "Old text", isEdit = true))
        assertNull(resolveRewardDescription("", null, isEdit = true))
        assertNull(resolveRewardDescription("", "Old", isEdit = false))

        val cleared = gson.toJsonTree(
            buildRewardRequest(UUID.randomUUID(), "Mug", 10, RewardQuantity.Unchanged, resolveRewardDescription("", "Old", true), false),
        ).asJsonObject
        assertEquals("", cleared.get("description").asString)
        val omitted = gson.toJsonTree(
            buildRewardRequest(UUID.randomUUID(), "Mug", 10, RewardQuantity.Unlimited, resolveRewardDescription("", null, false), false),
        ).asJsonObject
        assertFalse(omitted.has("description"))
    }

    @Test
    fun response_withNullCounts_parsesSafely() {
        val j = """{"rewardId":"${UUID.randomUUID()}","eventId":"${UUID.randomUUID()}","name":"Mug",
            "pointsRequired":50,"status":"ACTIVE","claimedCount":null,"totalQuantity":null}"""
        val parsed = gson.fromJson(j, RewardResponse::class.java)
        assertEquals(0L, parsed.claimedCount)
        assertNull(parsed.totalQuantity)
    }
}
