package com.thedavelopers.eventqr.features.organizer.rewards

import android.content.Context
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.RewardStatus
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRequest
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse

/**
 * Pure stock math for the organizer reward card. `stockQuantity` is the REMAINING stock and is never
 * used here; the ratio is always claimed vs the immutable total supply.
 */
data class RewardCardState(
    val claimedCount: Long,
    val totalQuantity: Int?,
) {
    val unlimited: Boolean get() = totalQuantity == null
    val outOfStock: Boolean get() = totalQuantity != null && claimedCount >= totalQuantity

    /**
     * claimed / total, clamped to 0..1 - the claims track's only input. Null when there is no track to
     * draw: unlimited supply, or a total of 0 (a zero-supply reward has no ratio to state).
     */
    val claimedFraction: Float?
        get() = totalQuantity?.takeIf { it > 0 }?.let { (claimedCount.toFloat() / it).coerceIn(0f, 1f) }

    /**
     * What the claims track paints. Null at zero claims as well as when there is no ratio at all: an
     * empty track is a 1.22:1 tonal rule across the card that reads as a divider, so at 0 the claimed
     * line states the fact alone and the track appears only once it has a sliver to show (v1.63). A
     * full track is the out-of-stock fact and stays.
     */
    val claimsTrackFraction: Float?
        get() = claimedFraction?.takeIf { claimedCount > 0 }

    companion object {
        fun of(reward: RewardResponse) = RewardCardState(reward.claimedCount, reward.totalQuantity)
    }
}

/**
 * The card's one status mark (§5.4: the quiet mark for the state the user expects, the strong bordered
 * pill for the exceptions). REWARDS_OFF and DISABLED wear the same neutral pill and differ only in the
 * word, because "Disabled" alone never says which thing is off: the event-level Rewards switch or the
 * reward's own server status (v1.63).
 */
enum class RewardBadge(val labelRes: Int) {
    AVAILABLE(R.string.manage_rewards_badge_available),
    OUT_OF_STOCK(R.string.reward_details_out_of_stock),
    REWARDS_OFF(R.string.manage_rewards_badge_rewards_off),
    DISABLED(R.string.manage_rewards_badge_disabled);

    /** The two states that block redemption: the body dims and the action pair quiets with it. */
    val blocksRedemption: Boolean get() = this == REWARDS_OFF || this == DISABLED
}

/**
 * The mark for a reward, derived from the event switch, the reward's own status and its stock. Pure, so
 * the cause behind a disabled card is unit-testable without an Activity.
 */
fun rewardBadge(reward: RewardResponse, rewardsEnabled: Boolean): RewardBadge = when {
    !rewardsEnabled -> RewardBadge.REWARDS_OFF
    reward.status == RewardStatus.INACTIVE -> RewardBadge.DISABLED
    RewardCardState.of(reward).outOfStock -> RewardBadge.OUT_OF_STOCK
    else -> RewardBadge.AVAILABLE
}

/** Outcome of validating the quantity field of the reward dialog. */
sealed interface RewardQuantity {
    /** Blank input: unlimited supply. */
    data object Unlimited : RewardQuantity
    data class Limited(val total: Int) : RewardQuantity
    /** Edit only: the field was not touched, so stock is left exactly as it is on the server. */
    data object Unchanged : RewardQuantity
    data object Invalid : RewardQuantity
}

fun parseRewardQuantity(raw: String): RewardQuantity {
    val text = raw.trim()
    if (text.isEmpty()) return RewardQuantity.Unlimited
    val value = text.toIntOrNull() ?: return RewardQuantity.Invalid
    return if (value < 0) RewardQuantity.Invalid else RewardQuantity.Limited(value)
}

/** Parses the field; on edit, text equal to the prefilled [original] means "unchanged". */
fun resolveRewardQuantity(raw: String, original: String?): RewardQuantity =
    if (original != null && raw.trim() == original.trim()) RewardQuantity.Unchanged else parseRewardQuantity(raw)

/**
 * Description rules (backend: null/absent keeps, "" clears):
 * create -> blank omitted; edit -> cleared sends "", unchanged re-sends the existing text, otherwise the new text.
 */
fun resolveRewardDescription(input: String, existing: String?, isEdit: Boolean): String? {
    val trimmed = input.trim()
    return when {
        trimmed.isNotEmpty() -> trimmed
        isEdit && !existing.isNullOrBlank() -> ""
        else -> null
    }
}

/**
 * Builds the create/update body. Never sends remaining stock. Quantity: Limited -> totalQuantity only,
 * Unlimited -> unlimitedStock=true only, Unchanged/null -> neither (server leaves stock alone).
 */
fun buildRewardRequest(
    eventId: java.util.UUID,
    title: String,
    points: Int,
    quantity: RewardQuantity,
    description: String?,
    allowDuplicateClaims: Boolean,
): RewardRequest = RewardRequest(
    eventId = eventId,
    name = title,
    pointsRequired = points,
    allowDuplicateClaims = allowDuplicateClaims,
    description = description,
    totalQuantity = (quantity as? RewardQuantity.Limited)?.total,
    unlimitedStock = if (quantity is RewardQuantity.Unlimited) true else null,
)

/** "5/10 claimed" for a limited reward; "Unlimited" (no ratio) otherwise. Never uses the remaining stock. */
fun rewardClaimedText(context: Context, state: RewardCardState): String =
    if (state.totalQuantity == null) {
        context.getString(R.string.manage_rewards_unlimited)
    } else {
        context.getString(
            R.string.manage_rewards_claimed_ratio,
            String.format("%,d", state.claimedCount),
            String.format("%,d", state.totalQuantity),
        )
    }
