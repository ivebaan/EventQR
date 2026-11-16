package com.thedavelopers.eventqr.features.attendee

import com.thedavelopers.eventqr.core.util.UiStrings
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.rewards.RewardAdapter
import com.thedavelopers.eventqr.features.rewards.model.dto.PointBalanceResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import com.thedavelopers.eventqr.ui.components.PassHeadBinder
import com.thedavelopers.eventqr.ui.components.asFilterChips
import com.thedavelopers.eventqr.ui.theme.applyEventQrSystemBarAppearance
import com.thedavelopers.eventqr.ui.theme.applyEventQrTopInsetPadding
import kotlinx.coroutines.launch
import java.time.Instant

open class AttendeeRewardsActivity : AppCompatActivity(), RewardsContract.View {
    /** Test seam: the screen's network reads are the repository's. */
    internal var repositoryOverride: AttendeeRepository? = null

    private lateinit var presenter: RewardsPresenter
    private lateinit var repository: AttendeeRepository
    private lateinit var adapter: RewardAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorContainer: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView
    private lateinit var emptyEventsText: View
    private lateinit var emptyNotRegisteredText: View
    private lateinit var emptyRewardsText: EventQrEmptyState
    private lateinit var eventPicker: EventPickerField
    private lateinit var balanceText: TextView
    private lateinit var rewardsSectionTitle: TextView
    private lateinit var claimsAction: TextView
    private lateinit var rewardsRecycler: RecyclerView
    private lateinit var rewardsBalanceCard: View
    private lateinit var layoutRewardsContent: View
    private lateinit var skeletonEventName: View
    private lateinit var layoutRewardsSkeleton: View

    private val eventOptions = mutableListOf<EventPickerOption>()
    private var selectedEventId: String? = null
    private var attendeeUserId: String? = null
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_rewards)
        applyEventQrSystemBarAppearance(lightStatusBars = false)
        findViewById<View>(R.id.passHeadIdentity).applyEventQrTopInsetPadding()
        configureAttendeeBottomNav(AttendeeBottomNavItem.REWARDS)
        PassHeadBinder.bindCompact(
            findViewById(R.id.passHead),
            title = getString(R.string.ui_attendee_rewards),
        )
        findViewById<EventQrEmptyState>(R.id.layoutNoRegisteredEvents).apply {
            setIcon(AppIcons.Reward)
            setContent(
                getString(R.string.common_no_registered_events_yet),
                getString(R.string.user_rewards_register_for_an_event_to_start_earni),
            )
        }
        findViewById<EventQrEmptyState>(R.id.layoutNotRegisteredForEvent).apply {
            setIcon(AppIcons.Reward)
            setContent(
                getString(R.string.user_rewards_not_registered_for_this_event),
                getString(R.string.user_rewards_register_for_this_event_to_see_its_re),
            )
        }
        findViewById<EventQrEmptyState>(R.id.layoutRewardsEmpty).apply {
            setIcon(AppIcons.Reward)
            setContent(
                getString(R.string.user_rewards_no_rewards_available_yet),
                getString(R.string.user_rewards_check_back_later_for_rewards_for_thi),
            )
        }

        repository = repositoryOverride ?: AttendeeRepository(this)
        presenter = RewardsPresenter(this, repository, UiStrings(this))
        adapter = RewardAdapter { reward ->
            val currentEventId = selectedEventId.orEmpty()
            if (currentEventId.isBlank()) {
                // Defensive only: rows render after an event is selected, so no toast can be reached.
                return@RewardAdapter
            }

            val detailsIntent = Intent(this, RewardDetailsActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, currentEventId)
                .putExtra(EXTRA_REWARD_ID, reward.rewardId.toString())
                .putExtra(EXTRA_REWARD_NAME, reward.name)
                .putExtra(EXTRA_REWARD_POINTS, reward.pointsRequired)
                .putExtra(EXTRA_REWARD_STATUS, reward.status.name)
            // A null stock is explicitly "unlimited"; an absent extra means "unknown" to the receiver.
            val stock = reward.stockQuantity
            if (stock != null) {
                detailsIntent.putExtra(EXTRA_REWARD_STOCK, stock)
            } else {
                detailsIntent.putExtra(EXTRA_REWARD_STOCK_UNLIMITED, true)
            }
            startActivity(detailsIntent)
        }

        swipeRefresh = findViewById(R.id.swipeRefreshRewards)
        errorContainer = findViewById(R.id.errorRewardsContainer)
        errorText = findViewById(R.id.txtRewardsError)
        retryButton = findViewById(R.id.btnRewardsRetry)
        emptyEventsText = findViewById(R.id.layoutNoRegisteredEvents)
        emptyNotRegisteredText = findViewById(R.id.layoutNotRegisteredForEvent)
        emptyRewardsText = findViewById(R.id.layoutRewardsEmpty)
        eventPicker = findViewById(R.id.event_picker_field)
        layoutRewardsContent = findViewById(R.id.layoutRewardsContent)
        balanceText = findViewById(R.id.txtRewardsBalance)
        rewardsSectionTitle = findViewById(R.id.txtRewardsSectionTitle)
        claimsAction = findViewById(R.id.txtMyClaims)
        rewardsRecycler = findViewById(R.id.recyclerRewards)
        rewardsBalanceCard = findViewById(R.id.cardRewardsBalance)

        skeletonEventName = findViewById(R.id.skeletonEventName)
        layoutRewardsSkeleton = findViewById(R.id.layoutRewardsSkeleton)

        rewardsRecycler.apply {
            layoutManager = LinearLayoutManager(this@AttendeeRewardsActivity)
            adapter = this@AttendeeRewardsActivity.adapter
        }

        claimsAction.setOnClickListener { openClaimedRewards() }

        // The chip row is the section switcher (§6.2): Available (this screen), Claimed, History.
        findViewById<View>(R.id.txtRewardsHistory).setOnClickListener {
            startActivity(Intent(this, AttendeeTransactionsActivity::class.java))
        }
        val availableChip = findViewById<TextView>(R.id.chipAvailable)
        availableChip.isSelected = true
        asFilterChips(availableChip, claimsAction, findViewById(R.id.txtRewardsHistory))

        retryButton.setOnClickListener { refreshRewardsPreservingSelection() }
        swipeRefresh.setOnRefreshListener { refreshRewardsPreservingSelection() }

        // A pick resolves the id against the loaded options; the event already on screen is a no-op
        // (its rewards are loaded already). The popup is closed before the content rebuild.
        eventPicker.setOnEventSelectedListener { id ->
            val option = eventOptions.firstOrNull { it.id == id } ?: return@setOnEventSelectedListener
            if (option.id == selectedEventId) return@setOnEventSelectedListener
            eventPicker.dismissPopup()
            loadSelectedEventRewards(option)
        }

        sessionManager = SessionManager(this)
        attendeeUserId = sessionManager.getUserId()
        loadRegisteredEvents()
    }

    override fun onDestroy() {
        presenter.detach()
        super.onDestroy()
    }

    override fun showLoading(isLoading: Boolean) {
        val showSkeleton = isLoading && !swipeRefresh.isRefreshing
        if (isLoading) {
            errorContainer.visibility = View.GONE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            emptyRewardsText.visibility = View.GONE

            skeletonEventName.visibility = if (showSkeleton) View.VISIBLE else View.GONE

            balanceText.visibility = View.VISIBLE

            rewardsRecycler.visibility = View.GONE
            layoutRewardsSkeleton.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        } else {
            skeletonEventName.visibility = View.GONE

            balanceText.visibility = View.VISIBLE

            layoutRewardsSkeleton.visibility = View.GONE

            swipeRefresh.isRefreshing = false
        }
    }

    override fun showMessage(message: String) {
        // No rewards read reports here: failures arrive through showError(), which renders the
        // in-flow error block. AttendeeView declares the member, so the screen keeps the override.
    }

    override fun showError(message: String) {
        swipeRefresh.isRefreshing = false
        errorContainer.visibility = View.VISIBLE
        errorText.text = message.ifBlank { getString(R.string.ui_attendee_rewards_load_failed) }
        errorText.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        emptyEventsText.visibility = View.GONE
        emptyRewardsText.visibility = View.GONE

        skeletonEventName.visibility = View.GONE

        balanceText.visibility = View.VISIBLE

        layoutRewardsSkeleton.visibility = View.GONE
        rewardsRecycler.visibility = View.GONE
        rewardsBalanceCard.visibility = View.GONE
    }

    override fun showBalance(balance: PointBalanceResponse) {
        rewardsBalanceCard.visibility = View.VISIBLE
        balanceText.text = balance.pointsBalance.toString()
    }

    override fun renderRewards(items: List<RewardResponse>, claimedRewardIds: Set<String>) {
        swipeRefresh.isRefreshing = false
        adapter.submitItems(items)
        errorContainer.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        rewardsBalanceCard.visibility = View.VISIBLE

        skeletonEventName.visibility = View.GONE

        balanceText.visibility = View.VISIBLE

        layoutRewardsSkeleton.visibility = View.GONE
        emptyRewardsText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rewardsRecycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        renderRewardsEmptyCopy(allClaimed = items.isEmpty() && claimedRewardIds.isNotEmpty())
    }

    /**
     * A list emptied only by claims must not read "No rewards available yet": the rewards exist,
     * this attendee holds them. Point that state at the Claimed tab instead.
     */
    private fun renderRewardsEmptyCopy(allClaimed: Boolean) {
        if (allClaimed) {
            emptyRewardsText.setContent(
                getString(R.string.ui_attendee_rewards_all_claimed_title),
                getString(R.string.ui_attendee_rewards_all_claimed_body),
            )
            emptyRewardsText.setAction(getString(R.string.ui_attendee_claimed_rewards)) { openClaimedRewards() }
        } else {
            emptyRewardsText.setContent(
                getString(R.string.user_rewards_no_rewards_available_yet),
                getString(R.string.user_rewards_check_back_later_for_rewards_for_thi),
            )
            emptyRewardsText.setAction(null, null)
        }
    }

    private fun openClaimedRewards() {
        val intent = Intent(this, ClaimedRewardsActivity::class.java)
        selectedEventId?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_EVENT_ID, it) }
        startActivity(intent)
    }

    private fun refreshRewardsPreservingSelection() {
        val previousEventId = selectedEventId
        if (previousEventId.isNullOrBlank()) {
            loadRegisteredEvents(forceNetwork = true)
            return
        }
        loadRegisteredEvents(preferredEventId = previousEventId, forceNetwork = true)
    }

    /**
     * @param forceNetwork pull-to-refresh and Retry always ask the network; an open serves a fresh
     *   full `registrations/me` answer already on hand (dashboard/my passes likely just fetched it).
     */
    private fun loadRegisteredEvents(
        preferredEventId: String? = selectedEventId ?: intent.getStringExtra(EXTRA_EVENT_ID).orEmpty().ifBlank { null },
        forceNetwork: Boolean = false,
    ) {
        errorContainer.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        emptyEventsText.visibility = View.GONE
        emptyRewardsText.visibility = View.GONE
        rewardsRecycler.visibility = View.GONE
        rewardsBalanceCard.visibility = View.VISIBLE
        claimsAction.visibility = View.GONE

        // A fresh full answer for this user is served as is: the dashboard or my passes just fetched
        // it, so opening Rewards must not ask again. Pull-to-refresh/Retry always ask.
        val cachedRegistrations = if (forceNetwork) null else RegistrationsCache.fresh(sessionManager.getUserId())

        // If the cache already says the user has no registered events, paint that state at once
        // instead of putting a skeleton in front of an answer we hold.
        val cacheSaysNoRegistrations = cachedRegistrations?.none {
            it.status != RegistrationStatus.CANCELLED &&
                it.status != RegistrationStatus.NO_SHOW &&
                it.eventId.toString().isNotBlank()
        } == true

        // The reward-list slot keeps its skeleton until this load settles. It must not depend on the
        // registrations answer: a fresh process-cache hit resolves the events at once, and a body
        // painted before the reward read lands would show an empty list and a blank balance.
        val showSkeleton = !swipeRefresh.isRefreshing && !cacheSaysNoRegistrations
        // The picker precedes this load: it shows its own loading copy until the events answer.
        eventPicker.setLoading(true)
        skeletonEventName.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        balanceText.visibility = View.VISIBLE
        layoutRewardsSkeleton.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        layoutRewardsContent.visibility = if (cacheSaysNoRegistrations) View.GONE else View.VISIBLE
        emptyNotRegisteredText.visibility = View.GONE

        if (cacheSaysNoRegistrations) {
            swipeRefresh.isRefreshing = false
            selectedEventId = null
            emptyEventsText.visibility = View.VISIBLE
            claimsAction.visibility = View.GONE
        }

        lifecycleScope.launch {
            val registrationsResult = cachedRegistrations?.let { NetworkResult.Success(it) }
                ?: repository.getMyRegistrations()
            when (registrationsResult) {
                is NetworkResult.Success -> {
                    val loaded = registrationsResult.data
                        .filter { it.status != RegistrationStatus.CANCELLED && it.status != RegistrationStatus.NO_SHOW }
                        .sortedByDescending { it.registeredAt ?: Instant.EPOCH }
                        .distinctBy { it.eventId }
                        .mapNotNull { registration ->
                            val eventId = registration.eventId.toString().takeIf { it.isNotBlank() }
                            val title = registration.eventTitle?.takeIf { it.isNotBlank() }
                                ?: registration.eventId.toString()
                            // The old dropdown row carried the title alone: no meta line to keep.
                            if (eventId == null) null else EventPickerOption(id = eventId, title = title)
                        }

                    eventOptions.clear()
                    eventOptions.addAll(loaded)

                    if (eventOptions.isEmpty()) {
                        swipeRefresh.isRefreshing = false
                        selectedEventId = null
                        eventPicker.setOptions(emptyList(), null)
                        emptyEventsText.visibility = View.VISIBLE
                        layoutRewardsContent.visibility = View.GONE
                        // No event means no reward read will ever run to hide the slot's skeleton.
                        skeletonEventName.visibility = View.GONE
                        layoutRewardsSkeleton.visibility = View.GONE
                        claimsAction.visibility = View.GONE
                        return@launch
                    }

                    layoutRewardsContent.visibility = View.VISIBLE
                    claimsAction.visibility = View.VISIBLE
                    emptyEventsText.visibility = View.GONE

                    val initialIndex = resolveRewardsEventIndex(
                        eventOptions.map { it.id },
                        preferredEventId,
                        requestedEventId = intent.getStringExtra(EXTRA_EVENT_ID),
                    )
                    if (initialIndex == null) {
                        // An explicit event was requested but the attendee has no live registration for
                        // it: never fall back to another event's rewards and balance. The field keeps
                        // every option and shows no selection.
                        eventPicker.setOptions(eventOptions, null)
                        showNotRegisteredEventState()
                        return@launch
                    }

                    loadSelectedEventRewards(eventOptions[initialIndex])
                }

                is NetworkResult.Error -> {
                    swipeRefresh.isRefreshing = false
                    // A failed fetch must not leave the field on its loading copy.
                    eventPicker.setOptions(eventOptions, selectedEventId)
                    showError(
                        registrationsResult.message.ifBlank {
                            getString(R.string.ui_attendee_registered_events_load_failed)
                        },
                    )
                }

                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Explicit event id with no live registration for it: no balance, rewards or claims for that
     * event, but the event picker stays usable so the attendee can open one they are registered
     * for. Never picks another event on its own.
     */
    private fun showNotRegisteredEventState() {
        swipeRefresh.isRefreshing = false
        selectedEventId = null
        layoutRewardsContent.visibility = View.VISIBLE
        skeletonEventName.visibility = View.GONE
        layoutRewardsSkeleton.visibility = View.GONE
        emptyEventsText.visibility = View.GONE
        emptyRewardsText.visibility = View.GONE
        rewardsRecycler.visibility = View.GONE
        rewardsBalanceCard.visibility = View.GONE
        rewardsSectionTitle.visibility = View.GONE
        claimsAction.visibility = View.GONE
        findViewById<View>(R.id.chipAvailable).visibility = View.GONE
        findViewById<View>(R.id.txtRewardsHistory).visibility = View.GONE
        emptyNotRegisteredText.visibility = View.VISIBLE
    }

    /**
     * One path for every event on screen: it owns the selection, keeps the picker's options and
     * current id in step (the single place both change) and asks the presenter for that event.
     */
    private fun loadSelectedEventRewards(option: EventPickerOption) {
        selectedEventId = option.id
        eventPicker.setOptions(eventOptions, option.id)
        skeletonEventName.visibility = View.GONE
        emptyNotRegisteredText.visibility = View.GONE
        rewardsSectionTitle.visibility = View.VISIBLE
        claimsAction.visibility = View.VISIBLE
        findViewById<View>(R.id.chipAvailable).visibility = View.VISIBLE
        findViewById<View>(R.id.txtRewardsHistory).visibility = View.VISIBLE
        presenter.load(option.id, attendeeUserId)
    }

}
