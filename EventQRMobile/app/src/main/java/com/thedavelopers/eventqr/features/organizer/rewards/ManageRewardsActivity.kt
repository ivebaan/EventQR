package com.thedavelopers.eventqr.features.organizer.rewards

import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.icons.FieldIcons
import com.thedavelopers.eventqr.ui.theme.color
import com.thedavelopers.eventqr.ui.theme.dimenFloat
import com.thedavelopers.eventqr.ui.theme.dimenPx
import com.thedavelopers.eventqr.features.organizer.orgColors
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.features.organizer.tintBlock
import com.thedavelopers.eventqr.features.organizer.tintBlockArgb
import com.thedavelopers.eventqr.core.api.ApiConfig
import com.thedavelopers.eventqr.core.api.dto.ApiResponse
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.features.events.model.dto.EventResponse
import com.thedavelopers.eventqr.features.organizer.NAV_REWARDS
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.organizerEventDateLine
import com.thedavelopers.eventqr.features.organizer.approvedOnly
import com.thedavelopers.eventqr.features.organizer.pickerDateLabel
import com.thedavelopers.eventqr.features.organizer.pickerOrdered
import com.thedavelopers.eventqr.features.organizer.cardRes
import com.thedavelopers.eventqr.features.organizer.centeredEmptyState
import com.thedavelopers.eventqr.features.organizer.errorState
import com.thedavelopers.eventqr.features.organizer.eventsLoadErrorState
import com.thedavelopers.eventqr.features.organizer.formatCount
import com.thedavelopers.eventqr.features.organizer.intentEventId
import com.thedavelopers.eventqr.features.organizer.bottomNav
import com.thedavelopers.eventqr.features.organizer.emptyState
import com.thedavelopers.eventqr.features.organizer.organizerRefreshShell
import com.thedavelopers.eventqr.features.organizer.primaryButton
import com.thedavelopers.eventqr.features.organizer.resolveSelectedEvent
import com.thedavelopers.eventqr.features.organizer.saveSelectedEventId
import com.thedavelopers.eventqr.features.organizer.selectedEventId
import com.thedavelopers.eventqr.features.organizer.styledText
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import com.thedavelopers.eventqr.ui.components.PassHeadBinder
import com.thedavelopers.eventqr.ui.theme.applyEventQrSystemBarAppearance
import com.thedavelopers.eventqr.ui.theme.applyEventQrTopInsetPadding
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRequest
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

open class ManageRewardsActivity : AppCompatActivity() {
    private lateinit var repository: OrganizerRepository
    @VisibleForTesting
    internal lateinit var selectedEvent: OrganizerMvpEvent
    private lateinit var content: LinearLayout
    private lateinit var page: View
    private lateinit var stateHost: android.widget.FrameLayout
    private lateinit var rewardsEnabledSwitch: SwitchCompat
    private lateinit var rewardHost: LinearLayout
    private lateinit var addRewardChip: TextView
    private lateinit var statusLine: TextView
    private lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    /** Tests set this before the first rewards call to replace the network service. */
    @VisibleForTesting
    internal var rewardsServiceOverride: OrganizerRewardsService? = null
    private val rewardsService by lazy { rewardsServiceOverride ?: OrganizerRewardsApiProvider.get(this) }
    private var rewardDialog: AlertDialog? = null
    private var rewards: List<RewardResponse> = emptyList()
    private var redemptions: List<RewardRedemptionResponse> = emptyList()
    private var eventOptions: List<OrganizerMvpEvent> = emptyList()
    private var rewardsEnabled: Boolean = false
    private var eventsLoadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OrganizerRepository(this)

        setContentView(R.layout.activity_manage_rewards)
        applyEventQrSystemBarAppearance(lightStatusBars = false)
        content = findViewById(R.id.manageRewardsContent)
        page = findViewById(R.id.manageRewardsPage)
        stateHost = findViewById(R.id.manageRewardsStateHost)
        refreshLayout = findViewById(R.id.swipeRefreshRewards)
        rewardHost = findViewById(R.id.mrw_reward_host)
        statusLine = findViewById(R.id.mrw_status)
        addRewardChip = findViewById(R.id.mrw_add_reward)
        FieldIcons.apply(addRewardChip)
        addRewardChip.setOnClickListener { showRewardDialog(null) }
        rewardsEnabledSwitch = findViewById(R.id.mrw_rewards_switch)
        findViewById<LinearLayout>(R.id.bottomNavHost).addView(bottomNav(NAV_REWARDS))

        val head = findViewById<View>(R.id.passHead)
        head.findViewById<View>(R.id.passHeadIdentity).applyEventQrTopInsetPadding()
        PassHeadBinder.bindCompact(
            root = head,
            title = getString(R.string.manage_rewards_title),
        )

        // Before an event is resolved (e.g. the event list failed to load) a pull retries that load instead.
        refreshLayout.setOnRefreshListener {
            // Pull-to-refresh bypasses the shared snapshot: the user asked for fresh rows.
            repository.invalidateEvents()
            if (::selectedEvent.isInitialized) loadRewards(showInitialLoading = false) else loadEvents()
        }
        // Nothing to refresh until an event is resolved; re-enabled below once the event list loads.
        refreshLayout.isEnabled = false

        loadEvents()
    }

    private fun loadEvents() {
        // A newer reload (swipe/retry) replaces the in-flight one, so a read that started before the
        // snapshot was invalidated cannot repaint stale picker rows when it lands.
        eventsLoadJob?.cancel()
        eventsLoadJob = lifecycleScope.launch { onEventsLoaded(repository.loadApprovedOrganizerEvents()) }
    }

    @VisibleForTesting
    internal fun onEventsLoaded(eventsLoad: OrganizerMvpLoad<List<OrganizerMvpEvent>>) {
        refreshLayout.isRefreshing = false
        // A failed fetch is not "no events": show the error with Retry (pull-to-refresh retries too).
        if (eventsLoad.source == OrganizerMvpDataSource.ERROR) {
            refreshLayout.isEnabled = true
            showState(
                eventsLoadErrorState(eventsLoad.message) {
                    if (refreshLayout.isRefreshing) return@eventsLoadErrorState
                    refreshLayout.isRefreshing = true
                    stateHost.removeAllViews()
                    stateHost.visibility = View.GONE
                    loadEvents()
                },
            )
            return
        }
        eventOptions = eventsLoad.data
        val requestedEventId = intentEventId() ?: selectedEventId().takeIf { it.isNotBlank() }
        // Falls back to the saved, then the first, manageable event when nothing was requested.
        val resolvedEvent = resolveSelectedEvent(eventOptions, requestedEventId)

        val hasEvent = resolvedEvent != null
        refreshLayout.isEnabled = hasEvent

        if (resolvedEvent != null) {
            selectedEvent = resolvedEvent
            rewardsEnabled = selectedEvent.rewardsStatus.equals("Enabled", ignoreCase = true)
            page.visibility = View.VISIBLE
            stateHost.visibility = View.GONE
            buildScreen()
            loadRewards()
            refreshRewardsEnabledFromServer()
        } else {
            showState(
                centeredEmptyState(
                    iconRes = AppIcons.Reward,
                    title = getString(R.string.manage_rewards_no_events_title),
                    subtext = getString(R.string.manage_rewards_no_events_subtext),
                ),
            )
        }
    }

    /** One transient state (load error / no event) above the page; the page itself stays in XML. */
    private fun showState(state: View) {
        stateHost.removeAllViews()
        stateHost.addView(state)
        stateHost.visibility = View.VISIBLE
        page.visibility = View.GONE
    }

    @VisibleForTesting
    internal fun buildScreen() {
        bindSelector()
        rewardsEnabledSwitch.setOnCheckedChangeListener(null)
        rewardsEnabledSwitch.isChecked = rewardsEnabled
        rewardsEnabledSwitch.setOnCheckedChangeListener { _, checked -> setRewardsEnabled(checked) }
        rewardHost.removeAllViews()
        // renderRewards() owns the reveal; a switch must not keep the previous event's chip.
        addRewardChip.visibility = View.GONE
    }


    /**
     * The event selector is the shared searchable field (plan 5.2 "Event picker"). It is fed the
     * approved set in nearest-date order; picking one swaps the screen to that event.
     */
    private fun bindSelector() {
        val picker = findViewById<EventPickerField>(R.id.event_picker_field)
        picker.setOnEventSelectedListener { id ->
            eventOptions.firstOrNull { it.id == id }?.let { onEventChosen(it) }
        }
        picker.setOptions(
            eventOptions.approvedOnly().pickerOrdered().map {
                EventPickerOption(
                    id = it.id,
                    title = it.title.ifBlank { getString(R.string.common_untitled_event) },
                    meta = it.pickerDateLabel(),
                )
            },
            selectedEvent.id,
        )
    }

    private fun onEventChosen(event: OrganizerMvpEvent) {
        if (event.id == selectedEvent.id) return
        selectedEvent = event
        rewardsEnabled = event.rewardsStatus.equals("Enabled", ignoreCase = true)
        saveSelectedEventId(event.id)
        loadRewards()
        refreshRewardsEnabledFromServer()
    }
    private fun bindEventSummary() {
        rewardsEnabledSwitch.setOnCheckedChangeListener(null)
        rewardsEnabledSwitch.isChecked = rewardsEnabled
        rewardsEnabledSwitch.setOnCheckedChangeListener { _, checked -> setRewardsEnabled(checked) }
    }

    /** Inline failure channel above the list, mirroring the scan-purposes screen (§6.8). */
    private fun showStatus(message: CharSequence?) {
        if (message.isNullOrBlank()) return
        statusLine.text = message
        statusLine.visibility = View.VISIBLE
    }

    @VisibleForTesting
    internal fun loadRewards(showInitialLoading: Boolean = true) {
        // A refresh can arrive before the event list has resolved an event; there is nothing to load yet.
        if (!::selectedEvent.isInitialized) {
            refreshLayout.isRefreshing = false
            return
        }
        if (showInitialLoading && !refreshLayout.isRefreshing) {
            statusLine.visibility = View.GONE
            rewardHost.removeAllViews()
            addRewardChip.visibility = View.GONE
            rewardHost.addView(styledText(getString(R.string.manage_rewards_loading), R.dimen.type_body_medium, false, orgColors.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dimenPx(R.dimen.space_xl), 0, dimenPx(R.dimen.space_xl))
            })
        }

        val eventId = selectedEvent.id
        lifecycleScope.launch {
            try {
                val rewardsResponse = rewardsService.getRewards(eventId)
                val redemptionsResponse = rewardsService.getClaimedRewards(eventId)
                if (selectedEvent.id != eventId) {
                    refreshLayout.isRefreshing = false
                    return@launch
                }
                if (!rewardsResponse.success) {
                    throw IllegalStateException(rewardsResponse.message ?: getString(R.string.manage_rewards_load_failed))
                }
                rewards = rewardsResponse.data.orEmpty().sortedBy { it.name.lowercase() }
                redemptions = redemptionsResponse.data.orEmpty()
                refreshLayout.isRefreshing = false
                renderRewards()
            } catch (error: Exception) {
                if (selectedEvent.id != eventId) return@launch
                refreshLayout.isRefreshing = false
                rewardHost.removeAllViews()
                addRewardChip.visibility = View.GONE
                rewardHost.addView(errorState(error.message ?: getString(R.string.manage_rewards_load_failed)) { loadRewards() })
            }
        }
    }

    private fun renderRewards() {
        bindEventSummary()
        statusLine.visibility = View.GONE
        rewardHost.removeAllViews()
        addRewardChip.visibility = if (rewards.isEmpty()) View.GONE else View.VISIBLE
        val enabled = rewardsEnabled
        if (rewards.isEmpty()) {
            rewardHost.addView(
                emptyState(
                    iconRes = AppIcons.Reward,
                    title = getString(R.string.manage_rewards_empty_title),
                    subtext = getString(R.string.manage_rewards_empty_subtext),
                    actionLabel = getString(R.string.manage_rewards_add_reward),
                    onAction = { showRewardDialog(null) },
                    primary = true,
                ),
            )
            return
        }
        rewards.forEach { reward ->
            rewardHost.addView(rewardCard(reward, enabled))
        }
    }

    /**
     * One reward card from `item_manage_reward.xml`.
     *
     * The state mark is quiet while the reward can be redeemed and loud while it cannot (§5.4, v1.62):
     * a 6dp success dot plus the word for AVAILABLE, a bordered strong pill for the two exceptions. A
     * blocked body drops to secondary ink instead of a blanket alpha, which would push its own text
     * under 4.5:1 (0.5 x ui_text_secondary over ui_surface is 2.21:1) and would dim the pill that has
     * to stay the loudest thing on the card. The action pair quiets with it (v1.63): `Edit` loses its
     * tonal fill and drops to ui_text_secondary ink, which is 6.62:1 on ui_surface - still AA, still a
     * 48dp control, still the same sheet.
     */
    @VisibleForTesting
    internal fun rewardCard(reward: RewardResponse, rewardsEnabled: Boolean): View {
        val stockState = RewardCardState.of(reward)
        val mark = rewardBadge(reward, rewardsEnabled)
        val row = LayoutInflater.from(this).inflate(R.layout.item_manage_reward, rewardHost, false)

        val name = row.findViewById<TextView>(R.id.mrw_reward_name)
        name.text = reward.name
        row.findViewById<TextView>(R.id.mrw_reward_points).text =
            resources.getQuantityString(R.plurals.manage_rewards_cost_pts, reward.pointsRequired, formatCount(reward.pointsRequired))
        row.findViewById<TextView>(R.id.mrw_reward_stock).text = rewardClaimedText(this, stockState)

        bindStatusMark(row, mark)
        bindClaimsTrack(row, stockState, mark)

        // Dimmed by ink, never by alpha: the two role inks both clear AA on the card surface.
        val dimmed = mark.blocksRedemption
        name.setTextColor(color(if (dimmed) R.color.ui_text_secondary else R.color.ui_text_primary))
        row.findViewById<ImageView>(R.id.imgRewardIcon).imageTintList =
            ColorStateList.valueOf(color(if (dimmed) R.color.ui_text_secondary else R.color.ui_primary))

        row.findViewById<View>(R.id.mrw_reward_duplicate_note).visibility =
            if (reward.allowDuplicateClaims) View.VISIBLE else View.GONE

        // Tapping the card edits the reward (no Edit button); TalkBack announces the action.
        row.setOnClickListener { showRewardDialog(reward) }
        ViewCompat.replaceAccessibilityAction(
            row,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.manage_rewards_edit),
            null,
        )

        return row
    }

    /** AVAILABLE wears the quiet mark; a state that blocks redemption wears a bordered strong pill. */
    private fun bindStatusMark(row: View, mark: RewardBadge) {
        val badge = row.findViewById<TextView>(R.id.mrw_reward_badge)
        val dot = row.findViewById<View>(R.id.mrw_reward_status_dot)
        // The word is the only node and the whole message ("Rewards off" names its cause), so the
        // label text is the accessibility text - no content description repeating it.
        badge.setText(mark.labelRes)
        when (mark) {
            // No fill, no pill: 6dp ui_success dot (the §5.4 tone for a live state) + the word in
            // ui_text_secondary, 6.62:1 on ui_surface. The dot is decorative; the word is the label.
            RewardBadge.AVAILABLE -> {
                badge.background = null
                badge.backgroundTintList = null
                badge.setTextColor(color(R.color.ui_text_secondary))
                badge.setPadding(0, 0, 0, 0)
                dot.visibility = View.VISIBLE
            }
            RewardBadge.REWARDS_OFF, RewardBadge.DISABLED -> {
                bindStrongPill(badge, R.drawable.bg_ui_badge_strong_neutral, R.color.ui_text_primary)
                dot.visibility = View.GONE
            }
            RewardBadge.OUT_OF_STOCK -> {
                bindStrongPill(badge, R.drawable.bg_ui_badge_strong_error, R.color.ui_on_error_container)
                dot.visibility = View.GONE
            }
        }
    }

    /** Fill + 1dp boundary come from the drawable, so the style's background tint must be cleared. */
    private fun bindStrongPill(badge: TextView, drawableRes: Int, inkRes: Int) {
        badge.setBackgroundResource(drawableRes)
        badge.backgroundTintList = null
        badge.setTextColor(color(inkRes))
        val padH = dimenPx(R.dimen.ui_badge_pad_h)
        badge.setPadding(padH, 0, padH, 0)
    }

    /**
     * The claims track. Decorative to TalkBack - the claimed line above it states the fact - and
     * hidden while there is no total to draw (unlimited, or a zero supply), at zero claims (an empty
     * track is just a tonal rule) and on a disabled reward. A full track is the out-of-stock fact, so
     * that state keeps it.
     */
    private fun bindClaimsTrack(row: View, state: RewardCardState, mark: RewardBadge) {
        val track = row.findViewById<ProgressBar>(R.id.mrw_reward_progress)
        val fraction = state.claimsTrackFraction
        val total = state.totalQuantity
        if (fraction == null || total == null || mark.blocksRedemption) {
            track.visibility = View.GONE
            return
        }
        track.max = total
        track.progress = (fraction * total).roundToInt()
        track.visibility = View.VISIBLE
    }
    private fun buildActionButton(
        label: String,
        iconRes: Int,
        bgColor: Int,
        textColor: Int,
        iconTint: Int,
        outlined: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_md))
            // A destructive trigger keeps a 1dp ui_error boundary at radius 4; everything else is
            // a flat tinted block (§5.1).
            background = androidx.core.content.ContextCompat.getDrawable(
                context,
                if (outlined) R.drawable.bg_ui_button_destructive else R.drawable.bg_ui_block_r4,
            )?.mutate()
            if (!outlined) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
            }
            minimumHeight = dimenPx(R.dimen.ui_button_height_inline)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(ImageView(this@ManageRewardsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dimenPx(R.dimen.icon_md), dimenPx(R.dimen.icon_md)).apply {
                    marginEnd = dimenPx(R.dimen.space_6)
                }
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
                contentDescription = null
            })

            addView(styledText(label, R.dimen.ui_type_body, true, textColor).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    @VisibleForTesting
    internal fun showRewardDialog(reward: RewardResponse?) {
        // No event resolved yet (event list still loading or failed): there is no event to create against.
        if (!::selectedEvent.isInitialized) return
        val isEdit = reward != null
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dimenPx(R.dimen.space_20), dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_20), 0)
        }
        val titleInput = EditText(this).apply {
            id = com.thedavelopers.eventqr.R.id.mrw_reward_title_input
            hint = getString(R.string.manage_rewards_title_hint)
            setText(reward?.name.orEmpty())
            isSingleLine = true
        }
        val pointsInput = EditText(this).apply {
            id = com.thedavelopers.eventqr.R.id.mrw_reward_points_input
            hint = getString(R.string.manage_rewards_points_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(reward?.pointsRequired?.toString().orEmpty())
            isSingleLine = true
        }
        val quantityInput = EditText(this).apply {
            id = com.thedavelopers.eventqr.R.id.mrw_reward_quantity_input
            inputType = InputType.TYPE_CLASS_NUMBER
            // Total supply, never the remaining stock; blank means unlimited.
            setText(reward?.totalQuantity?.toString().orEmpty())
            hint = getString(R.string.manage_rewards_quantity_hint)
            isSingleLine = true
        }
        val descriptionInput = EditText(this).apply {
            id = com.thedavelopers.eventqr.R.id.mrw_reward_description_input
            hint = getString(R.string.manage_rewards_description_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(reward?.description.orEmpty())
            minLines = 2
        }
        val duplicateSwitch = SwitchCompat(this).apply {
            id = com.thedavelopers.eventqr.R.id.mrw_reward_duplicate_switch
            text = getString(R.string.manage_rewards_allow_duplicate_claims)
            isChecked = reward?.allowDuplicateClaims == true
            setTextColor(color(R.color.text_primary))
            setPadding(dimenPx(R.dimen.space_xxs), dimenPx(R.dimen.space_sm), dimenPx(R.dimen.space_xxs), dimenPx(R.dimen.space_sm))
        }
        form.addView(fieldLabel(getString(R.string.manage_rewards_reward_title)))
        form.addView(titleInput)
        form.addView(fieldLabel(getString(R.string.manage_rewards_points_cost)))
        form.addView(pointsInput)
        form.addView(fieldLabel(getString(R.string.manage_rewards_total_quantity)))
        form.addView(quantityInput)
        form.addView(fieldLabel(getString(R.string.manage_rewards_description)))
        form.addView(descriptionInput)
        form.addView(fieldLabel(getString(R.string.manage_rewards_settings)))
        form.addView(duplicateSwitch)
        val removeAction = if (reward != null) {
            // Remove lives in the edit sheet (v1.64): quiet destructive text, then the usual confirm.
            Button(this, null, 0, R.style.Widget_EventQR_Ui_Button_Destructive_Text_Inline).apply {
                id = com.thedavelopers.eventqr.R.id.mrw_reward_remove
                setText(R.string.manage_rewards_remove)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                contentDescription = getString(R.string.manage_rewards_remove_named, reward.name)
                form.addView(
                    this,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dimenPx(R.dimen.space_24) },
                )
            }
        } else {
            null
        }

        // §5.12: one dialog base; the form slots between the title and the right-aligned text actions.
        val root = layoutInflater.inflate(R.layout.dialog_base, null, false) as LinearLayout
        root.findViewById<TextView>(R.id.dialogTitle).setText(
            if (isEdit) R.string.manage_rewards_edit_reward else R.string.manage_rewards_create_reward,
        )
        root.findViewById<View>(R.id.dialogBody).visibility = View.GONE
        val actions = root.findViewById<LinearLayout>(R.id.dialogActions)
        form.setPadding(0, dimenPx(R.dimen.space_md), 0, 0)
        // Scrolls when the form outgrows the window (small phone, keyboard up); the actions stay pinned below.
        root.addView(
            ScrollView(this).apply { addView(form) },
            root.indexOfChild(actions),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        rewardDialog?.dismiss()
        val dialog = AlertDialog.Builder(this).setView(root).create()
        rewardDialog = dialog

        val positive = root.findViewById<Button>(R.id.dialogPositive)
        positive.setText(if (isEdit) R.string.manage_scan_purposes_save else R.string.manage_rewards_create)
        positive.setOnClickListener {
            val title = titleInput.text.toString().trim()
            val points = pointsInput.text.toString().trim().toIntOrNull()
            val quantity = resolveRewardQuantity(quantityInput.text.toString(), reward?.let { it.totalQuantity?.toString().orEmpty() })
            titleInput.error = if (title.isBlank()) getString(R.string.manage_rewards_title_required) else null
            pointsInput.error = if (points == null || points <= 0) getString(R.string.manage_rewards_points_invalid) else null
            quantityInput.error = if (quantity is RewardQuantity.Invalid) getString(R.string.manage_rewards_quantity_invalid) else null
            val firstInvalid = listOf(titleInput, pointsInput, quantityInput).firstOrNull { it.error != null }
            if (firstInvalid != null || points == null) {
                firstInvalid?.requestFocus()
                return@setOnClickListener
            }
            saveReward(
                reward,
                title,
                points,
                quantity,
                resolveRewardDescription(descriptionInput.text.toString(), reward?.description, isEdit),
                duplicateSwitch.isChecked,
                dialog,
            )
        }

        root.findViewById<Button>(R.id.dialogNegative).apply {
            setText(R.string.request_event_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        removeAction?.setOnClickListener {
            dialog.dismiss()
            confirmDeleteReward(reward!!)
        }

        dialog.show()
    }

    private fun fieldLabel(label: String): TextView = styledText(label, R.dimen.type_body_compact, true, orgColors.text).apply {
        setPadding(0, dimenPx(R.dimen.space_md), 0, dimenPx(R.dimen.space_xs))
    }

    private fun saveReward(
        existingReward: RewardResponse?,
        title: String,
        points: Int,
        quantity: RewardQuantity,
        description: String?,
        allowDuplicateClaims: Boolean,
        dialog: AlertDialog,
    ) {
        val eventId = selectedEvent.id
        val request = buildRewardRequest(UUID.fromString(eventId), title, points, quantity, description, allowDuplicateClaims)
        // The dialog stays open (keeping the typed values) until the server accepts. While the request runs it
        // cannot be saved again or dismissed (Cancel/back/outside), so input is not lost and no duplicate create
        // can be started from a reopened dialog.
        setRewardDialogBusy(dialog, busy = true)
        lifecycleScope.launch {
            try {
                val response = if (existingReward == null) {
                    rewardsService.createReward(eventId, request)
                } else {
                    rewardsService.updateReward(eventId, existingReward.rewardId.toString(), request)
                }
                if (!response.success) throw IllegalStateException(response.message ?: getString(R.string.manage_rewards_save_failed))
                dialog.dismiss()
                Toast.makeText(this@ManageRewardsActivity, response.message ?: getString(R.string.manage_rewards_saved), Toast.LENGTH_SHORT).show()
                loadRewards()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                setRewardDialogBusy(dialog, busy = false)
                val serverMessage = (error as? retrofit2.HttpException)?.let { com.thedavelopers.eventqr.core.api.parseHttpErrorMessage(it) }
                // The dialog stays open, so the failure belongs inside it: the body slot becomes the error line.
                dialog.findViewById<TextView>(R.id.dialogBody)?.apply {
                    setTextColor(color(R.color.ui_error))
                    text = serverMessage ?: error.message ?: getString(R.string.manage_rewards_save_failed)
                    visibility = View.VISIBLE
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
            }
        }
    }

    private fun setRewardDialogBusy(dialog: AlertDialog, busy: Boolean) {
        dialog.setCancelable(!busy)
        dialog.findViewById<Button>(R.id.dialogPositive)?.isEnabled = !busy
        dialog.findViewById<Button>(R.id.dialogNegative)?.isEnabled = !busy
        dialog.findViewById<Button>(R.id.mrw_reward_remove)?.isEnabled = !busy
    }

    override fun onDestroy() {
        // Avoid leaking the dialog window when the activity goes away (e.g. rotation) mid-edit or mid-save.
        rewardDialog?.dismiss()
        rewardDialog = null
        super.onDestroy()
    }

    private fun confirmDeleteReward(reward: RewardResponse) {
        // §5.12: `dialog_base` with the verb as the destructive commit.
        val root = layoutInflater.inflate(R.layout.dialog_base, null, false)
        val dialog = AlertDialog.Builder(this).setView(root).create()
        root.findViewById<TextView>(R.id.dialogTitle).setText(R.string.manage_rewards_remove_reward)
        root.findViewById<TextView>(R.id.dialogBody).text =
            getString(R.string.manage_rewards_remove_confirm, reward.name, selectedEvent.title)
        root.findViewById<Button>(R.id.dialogNegative).apply {
            setText(R.string.request_event_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        root.findViewById<Button>(R.id.dialogPositive).apply {
            setText(R.string.staff_remove)
            setBackgroundResource(R.drawable.bg_ui_button_destructive_commit)
            setTextColor(ContextCompat.getColor(context, R.color.ui_on_primary))
            setOnClickListener {
                dialog.dismiss()
                deleteReward(reward)
            }
        }
        dialog.show()
    }

    private fun deleteReward(reward: RewardResponse) {
        val eventId = selectedEvent.id
        lifecycleScope.launch {
            try {
                val response = rewardsService.deleteReward(eventId, reward.rewardId.toString())
                if (!response.success) throw IllegalStateException(response.message ?: getString(R.string.manage_rewards_remove_failed))
                Toast.makeText(this@ManageRewardsActivity, response.message ?: getString(R.string.manage_rewards_removed), Toast.LENGTH_SHORT).show()
                loadRewards()
            } catch (error: Exception) {
                showStatus(error.message ?: getString(R.string.manage_rewards_remove_failed))
            }
        }
    }

    private fun refreshRewardsEnabledFromServer() {
        val eventId = selectedEvent.id
        lifecycleScope.launch {
            try {
                val response = rewardsService.getRewardSettings(eventId)
                if (selectedEvent.id != eventId) return@launch
                if (response.success && response.data != null) {
                    rewardsEnabled = response.data
                    bindEventSummary()
                    renderRewards()
                }
            } catch (_: Exception) {
                // Server truth will be re-synced on the next refresh; keep current local state.
            }
        }
    }

    private fun setRewardsEnabled(enabled: Boolean) {
        val eventId = selectedEvent.id
        lifecycleScope.launch {
            try {
                val response = rewardsService.updateRewardSettings(eventId, RewardSettingsRequest(enabled))
                if (selectedEvent.id != eventId) return@launch
                if (!response.success) throw IllegalStateException(response.message ?: getString(R.string.manage_rewards_settings_failed))
                response.data?.let { rewardsEnabled = it.rewardsEnabled }
                bindEventSummary()
                renderRewards()
                Toast.makeText(
                    this@ManageRewardsActivity,
                    getString(if (rewardsEnabled) R.string.manage_rewards_reward_redemption_enabled_for_this_e else R.string.manage_rewards_reward_redemption_disabled_for_this),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Exception) {
                rewardsEnabledSwitch.setOnCheckedChangeListener(null)
                rewardsEnabledSwitch.isChecked = rewardsEnabled
                rewardsEnabledSwitch.setOnCheckedChangeListener { _, checked -> setRewardsEnabled(checked) }
                showStatus(
                    getString(
                        R.string.manage_rewards_update_settings_failed,
                        error.message ?: getString(R.string.common_try_again_lower),
                    ),
                )
            }
        }
    }
}

@VisibleForTesting
internal interface OrganizerRewardsService {
    @GET("organizer/events/{eventId}/rewards")
    suspend fun getRewards(@Path("eventId") eventId: String): ApiResponse<List<RewardResponse>>

    @GET("organizer/events/{eventId}/reward-settings")
    suspend fun getRewardSettings(@Path("eventId") eventId: String): ApiResponse<Boolean>

    @PATCH("organizer/events/{eventId}/reward-settings")
    suspend fun updateRewardSettings(
        @Path("eventId") eventId: String,
        @Body request: RewardSettingsRequest,
    ): ApiResponse<EventResponse>

    @POST("organizer/events/{eventId}/rewards")
    suspend fun createReward(
        @Path("eventId") eventId: String,
        @Body request: RewardRequest,
    ): ApiResponse<RewardResponse>

    @PATCH("organizer/events/{eventId}/rewards/{rewardId}")
    suspend fun updateReward(
        @Path("eventId") eventId: String,
        @Path("rewardId") rewardId: String,
        @Body request: RewardRequest,
    ): ApiResponse<RewardResponse>

    @DELETE("organizer/events/{eventId}/rewards/{rewardId}")
    suspend fun deleteReward(
        @Path("eventId") eventId: String,
        @Path("rewardId") rewardId: String,
    ): ApiResponse<Unit>

    @GET("organizer/events/{eventId}/claimed-rewards")
    suspend fun getClaimedRewards(@Path("eventId") eventId: String): ApiResponse<List<RewardRedemptionResponse>>
}

data class RewardSettingsRequest(
    val enabled: Boolean,
)

private object OrganizerRewardsApiProvider {
    @Volatile
    private var service: OrganizerRewardsService? = null

    fun get(context: Context): OrganizerRewardsService {
        return service ?: synchronized(this) {
            service ?: build(context.applicationContext).also { service = it }
        }
    }

    private fun build(context: Context): OrganizerRewardsService {
        val client = com.thedavelopers.eventqr.core.api.ApiClient.newHttpClient(context)
        return Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(sharedGson()))
            .build()
            .create(OrganizerRewardsService::class.java)
    }
}







