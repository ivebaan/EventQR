package com.thedavelopers.eventqr.ui.components

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.dimenFloat
import com.thedavelopers.eventqr.ui.theme.dimenPx
import com.thedavelopers.eventqr.ui.theme.integer

/** One row of the event picker: the id the host resolves, the displayed name and an optional meta line. */
data class EventPickerOption(
    val id: String,
    val title: String,
    val meta: String? = null,
)

/**
 * The picker's filter (plan 5.2 "Event picker"): case-insensitive substring over the displayed
 * name only, trimmed query, blank shows every option. Pure so it can be unit tested without a view.
 */
internal fun filterEventOptions(options: List<EventPickerOption>, query: String): List<EventPickerOption> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return options
    return options.filter { it.title.contains(trimmed, ignoreCase = true) }
}

/**
 * Searchable event selector shared by the organizer Attendees, Reports and Rewards pages
 * (plan 5.2 "Event picker"). One focusable node - the text input - carries the query and the
 * accessibility contract; any tap or focus gain clears the query, raises the keyboard and drops
 * the full list under it, typing filters, and the hint keeps naming the chosen event. The chosen
 * title only returns to the text when the user leaves without choosing (v1.58).
 */
class EventPickerField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val input: EventPickerInput
    private val meta: TextView
    private val trailing: ImageView
    private val adapter = EventPickerAdapter(context)

    private var options: List<EventPickerOption> = emptyList()
    private var selectedId: String? = null
    private var selectedTitle: String? = null
    private var loading = false
    private var programmatic = false
    private var selecting = false
    /** True from the first keystroke until the query is submitted or reverted (v1.58). */
    private var userEdited = false
    /**
     * Set by a revert: the window can hand focus straight back to the input afterwards (it is often
     * the only focusable view), and that gain must not read as a fresh search (v1.58).
     */
    private var awaitingUserGesture = false
    private var onSelected: ((String) -> Unit)? = null
    private var announceRunnable: Runnable? = null
    private var appliedDropDownHeight = -1
    private val dismissExitCheck = Runnable { onPopupDismissedForGood() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_ui_field)
        addStatesFromChildren()
        isFocusable = false
        isClickable = false
        minimumHeight = dimenPx(R.dimen.ui_organizer_selector_height)
        setPadding(dimenPx(R.dimen.space_16), dimenPx(R.dimen.space_8), dimenPx(R.dimen.space_4), dimenPx(R.dimen.space_8))
        if (id == NO_ID) id = R.id.event_picker_field

        LayoutInflater.from(context).inflate(R.layout.view_event_picker_field, this, true)
        input = findViewById(R.id.eventPickerInput)
        meta = findViewById(R.id.eventPickerMeta)
        trailing = findViewById(R.id.eventPickerTrailing)

        input.showSoftInputOnFocus = true
        input.setRawInputType(InputType.TYPE_CLASS_TEXT)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.threshold = 0
        input.isSaveEnabled = false
        input.freezesText = false
        // The popup tracks the host box - its width and its left edge - not the narrower text input.
        input.dropDownAnchor = R.id.event_picker_field
        input.setDropDownBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_ui_menu))
        input.dropDownVerticalOffset = dimenPx(R.dimen.space_xs)
        input.setAdapter(adapter)
        applyHint()

        // The IME appears after the list: refit the popup when the insets change, keeping the query,
        // the focus and the text (plan 5.2 "Event picker").
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            if (input.isPopupShowing && dropDownHeightPx() != appliedDropDownHeight) resizeDropDown()
            insets
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (programmatic) return
                userEdited = true
                awaitingUserGesture = false
                renderTrailing()
                meta.visibility = View.GONE
                // Filter synchronously so the popup never shows a stale list while the framework's
                // own Filter runs on its worker thread.
                val query = s?.toString().orEmpty()
                adapter.applyQuery(query)
                if (input.hasFocus()) {
                    applyDropDownGeometry()
                    input.showDropDown()
                }
                scheduleResultsAnnouncement()
            }
        })

        input.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                meta.visibility = View.GONE
                // A focus the user asked for starts a fresh search; the focus the window hands back
                // straight after a revert leaves the field at rest on the title (v1.58).
                if (!loading) {
                    if (awaitingUserGesture) awaitingUserGesture = false else openForSearch()
                }
            } else {
                dismissList()
                revertText()
            }
        }

        input.onUserGesture = { awaitingUserGesture = false }

        input.setOnClickListener {
            if (!isEnabled || loading) return@setOnClickListener
            // A tap inside a query in progress keeps the query: only a fresh open starts empty.
            if (input.isPopupShowing && hasQuery()) return@setOnClickListener
            openForSearch()
        }

        input.onBack = {
            if (!input.isPopupShowing && !hasQuery()) {
                false
            } else {
                revertText()
                dismissList()
                hideKeyboard()
                true
            }
        }

        input.setOnItemClickListener(AdapterView.OnItemClickListener { _, _, position, _ ->
            val option = adapter.optionAt(position) ?: return@OnItemClickListener
            select(option)
        })

        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!done) return@setOnEditorActionListener false
            val matches = adapter.selectableMatches()
            if (matches.size == 1) select(matches.first()) else hideKeyboard()
            true
        }

        input.setOnDismissListener {
            // The popup also dismisses and re-shows itself whenever it refits (IME insets, a filter
            // shrinking the list), so a dismissal alone is not an exit: check once the pass is done
            // and only react if the list really stayed closed under a live query (v1.58).
            if (selecting || !isAttachedToWindow) return@setOnDismissListener
            removeCallbacks(dismissExitCheck)
            post(dismissExitCheck)
        }

        trailing.setOnClickListener {
            if (!isEnabled || loading) return@setOnClickListener
            // Same open as the field itself: clear the query, focus, raise the IME, drop the list.
            openForSearch()
        }

        val role = context.getString(R.string.ui_organizer_select_role)
        ViewCompat.setAccessibilityDelegate(input, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.roleDescription = role
                info.isEditable = true
                info.stateDescription = selectedTitle
                info.addAction(AccessibilityActionCompat.ACTION_EXPAND)
                info.addAction(AccessibilityActionCompat.ACTION_COLLAPSE)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                AccessibilityActionCompat.ACTION_EXPAND.id -> {
                    openList()
                    true
                }
                AccessibilityActionCompat.ACTION_COLLAPSE.id -> {
                    dismissList()
                    true
                }
                else -> super.performAccessibilityAction(host, action, args)
            }
        })

        setLoading(true)
    }

    /** Feeds the full option list and the current selection. [selectedId] is resolved by the host. */
    fun setOptions(options: List<EventPickerOption>, selectedId: String?) {
        this.options = options
        this.selectedId = selectedId?.takeIf { id -> options.any { it.id == id } }
        val selected = options.firstOrNull { it.id == this.selectedId }
        selectedTitle = selected?.title
        adapter.setOptions(options, this.selectedId)
        // A host may reload while the user is typing: the query wins, never the chosen title.
        if (input.hasFocus() && userEdited) {
            adapter.applyQuery(input.text?.toString().orEmpty())
        } else {
            userEdited = false
            programmatic = true
            input.setText(selected?.title.orEmpty(), false)
            programmatic = false
        }
        setLoading(false)
        applyHint()
        meta.text = selected?.meta.orEmpty()
        meta.visibility = if (selected?.meta.isNullOrBlank() || input.hasFocus()) View.GONE else View.VISIBLE
        ViewCompat.setStateDescription(input, selected?.title)
        renderTrailing()
    }

    /** Loading state: the field shows the loading copy, is disabled and never opens the list. */
    fun setLoading(isLoading: Boolean) {
        loading = isLoading
        if (isLoading) {
            userEdited = false
            input.isEnabled = false
            programmatic = true
            input.setText(context.getString(R.string.ui_event_picker_loading), false)
            programmatic = false
            meta.visibility = View.GONE
            trailing.alpha = dimenFloat(R.dimen.alpha_disabled)
        } else {
            input.isEnabled = isEnabled
            trailing.alpha = 1f
        }
        applyHint()
        renderTrailing()
    }

    fun setOnEventSelectedListener(listener: (String) -> Unit) {
        onSelected = listener
    }

    /** Closes an open list. Call before the host removes this view's tree. */
    fun dismissPopup() {
        dismissList()
    }

    /** Disabling the field disables the input too, so a tap cannot open the list. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (childCount == 0) return
        findViewById<View>(R.id.eventPickerInput)?.let { it.isEnabled = enabled && !loading }
        if (!enabled) dismissList()
    }

    override fun onDetachedFromWindow() {
        dismissList()
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        announceRunnable?.let { removeCallbacks(it) }
        announceRunnable = null
        removeCallbacks(dismissExitCheck)
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable? {
        dismissList()
        return super.onSaveInstanceState()
    }

    /** True only for text the user typed and has not chosen or left behind (v1.58). */
    private fun hasQuery(): Boolean = userEdited && input.text?.isNotEmpty() == true

    private fun renderTrailing() {
        if (loading) {
            trailing.setImageResource(AppIcons.Expand)
            trailing.contentDescription = null
            trailing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            trailing.isClickable = false
            return
        }
        if (hasQuery()) {
            trailing.setImageResource(AppIcons.Close)
            trailing.contentDescription = context.getString(R.string.ui_event_picker_clear)
            trailing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            trailing.setImageResource(AppIcons.Expand)
            trailing.contentDescription = null
            trailing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        trailing.isClickable = true
    }

    /**
     * Keyboard-first open (plan 5.2 "Event picker"): the query starts empty - the hint keeps naming
     * the chosen event - the field takes focus, the IME comes up and the full list drops under it.
     * A tap on the field and a tap on the chevron both land here.
     */
    private fun openForSearch() {
        if (!isEnabled || loading) return
        clearQuery()
        if (!input.hasFocus()) input.requestFocus()
        showKeyboard()
        openList()
    }

    /** Empties a query with no filter, announce or meta side effect, and restores the full list. */
    private fun clearQuery() {
        userEdited = false
        programmatic = true
        input.setText("", false)
        programmatic = false
        meta.visibility = View.GONE
        adapter.setOptions(options, selectedId)
        renderTrailing()
    }

    private fun openList() {
        if (!isEnabled || loading || options.isEmpty()) return
        // Every open starts from the full list: a selection must not leave the adapter filtered to
        // the chosen row (the classic "reopen shows only the selected item" bug).
        adapter.setOptions(options, selectedId)
        applyDropDownGeometry()
        input.showDropDown()
    }

    /** Popup width tracks the host box and its height the rows that fit under it (plan 5.2). */
    private fun applyDropDownGeometry() {
        val hostWidth = width
        if (hostWidth > 0) input.dropDownWidth = hostWidth
        appliedDropDownHeight = dropDownHeightPx()
        input.setDropDownHeight(appliedDropDownHeight)
    }

    /** Re-shows the live popup: the framework updates it in place, so query, focus and text stay put. */
    private fun resizeDropDown() {
        if (!input.isPopupShowing) return
        applyDropDownGeometry()
        input.showDropDown()
    }

    private fun dismissList() {
        if (input.isPopupShowing) input.dismissDropDown()
    }

    private fun select(option: EventPickerOption) {
        selecting = true
        userEdited = false
        selectedId = option.id
        selectedTitle = option.title
        adapter.setOptions(options, selectedId)
        programmatic = true
        input.setText(option.title, false)
        programmatic = false
        applyHint()
        meta.text = option.meta.orEmpty()
        meta.visibility = View.GONE
        ViewCompat.setStateDescription(input, option.title)
        renderTrailing()
        hideKeyboard()
        dismissList()
        selecting = false
        // Focus stays on the input on purpose: clearFocus() hands it straight back to the only
        // focusable view on a short screen, and that gain would open a search over the new
        // selection. The next tap clears through the click path anyway (v1.58).
        // The host may rebuild its whole tree: run after the popup has gone.
        post { onSelected?.invoke(option.id) }
    }

    /**
     * A dismissal that outlived its own pass - the popup refits and re-shows within one pass, so
     * only a list that stayed closed counts. If the field kept focus it would sit on a half-typed
     * query with no list: put the query back to the chosen title (v1.58).
     */
    private fun onPopupDismissedForGood() {
        if (selecting || !isAttachedToWindow || !input.isEnabled) return
        if (input.isPopupShowing || !input.hasFocus() || !hasQuery()) return
        hideKeyboard()
        revertText()
    }

    /** Reverts an unsubmitted query to the selected event's name; never auto-selects. */
    private fun revertText() {
        if (loading) return
        userEdited = false
        awaitingUserGesture = true
        programmatic = true
        input.setText(selectedTitle.orEmpty(), false)
        programmatic = false
        applyHint()
        meta.text = options.firstOrNull { it.id == selectedId }?.meta.orEmpty()
        meta.visibility = if (meta.text.isNullOrBlank() || input.hasFocus()) View.GONE else View.VISIBLE
        renderTrailing()
    }

    /** The hint always names the chosen event, so a cleared search field still says what is selected. */
    private fun applyHint() {
        input.hint = selectedTitle ?: context.getString(R.string.common_select_event)
    }

    private fun showKeyboard() {
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun scheduleResultsAnnouncement() {
        announceRunnable?.let { removeCallbacks(it) }
        val query = input.text?.toString().orEmpty()
        if (query.isBlank()) return
        val runnable = Runnable {
            val count = filterEventOptions(options, query).size
            input.announceForAccessibility(
                resources.getQuantityString(R.plurals.ui_event_picker_results, count, count),
            )
        }
        announceRunnable = runnable
        postDelayed(runnable, integer(R.integer.motion_announce_ms).toLong())
    }

    /**
     * Popup height: at most five rows, wrapping to the rows it has, never taller than the space
     * under the host. The IME shows up after the popup, so that space is recomputed every insets
     * change; the floor of two rows keeps the list under the field (it scrolls instead of flipping).
     */
    private fun dropDownHeightPx(): Int {
        val rowMin = dimenPx(R.dimen.ui_touch_min)
        val cap = maxOf(
            rowMin * MIN_VISIBLE_ROWS,
            minOf(rowMin * MAX_VISIBLE_ROWS, spaceBelowPx()),
        )
        return minOf(cap, rowsHeightPx(cap, rowMin))
    }

    /** Measured height of the rows that will show, stopping at [cap] so a long list costs five rows. */
    private fun rowsHeightPx(cap: Int, rowMin: Int): Int {
        val rowWidth = width.takeIf { it > 0 } ?: input.width
        if (rowWidth <= 0) return cap
        val widthSpec = View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var total = 0
        for (position in 0 until minOf(adapter.count, MAX_VISIBLE_ROWS)) {
            val row = adapter.getView(position, null, this)
            row.measure(widthSpec, heightSpec)
            total += row.measuredHeight
            if (total >= cap) return cap
        }
        return if (total == 0) rowMin else total
    }

    /** Space between the host's bottom edge and the last visible pixel, minus one `space_8` gap. */
    private fun spaceBelowPx(): Int {
        val root = rootView
        val host = IntArray(2)
        getLocationOnScreen(host)
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val bottomInRoot = host[1] - origin[1] + height
        val insets = ViewCompat.getRootWindowInsets(this)
        val blocked = maxOf(
            insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
            insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0,
        )
        return root.height - bottomInRoot - blocked - dimenPx(R.dimen.space_8)
    }

    private companion object {
        /** Rows the popup shows at most. */
        const val MAX_VISIBLE_ROWS = 5

        /** Floor that keeps the list under the host even on a short screen: it scrolls instead. */
        const val MIN_VISIBLE_ROWS = 2
    }
}

/** AutoCompleteTextView with the picker's always-filterable contract (plan 5.2 "Event picker"). */
class EventPickerInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.autoCompleteTextViewStyle,
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    /** Back before the IME sees it: the picker reverts a live query instead of closing alone. */
    var onBack: (() -> Boolean)? = null

    /** The user's own touch or key, as opposed to focus the window hands back on its own. */
    var onUserGesture: (() -> Unit)? = null

    override fun enoughToFilter(): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserGesture?.invoke()
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        onUserGesture?.invoke()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && onBack?.invoke() == true) return true
        return super.onKeyPreIme(keyCode, event)
    }

    // The picker's select() writes the text itself; a framework write here would re-filter
    // the list before the click listener resolves the tapped row.
    override fun replaceText(text: CharSequence?) = Unit
}

/**
 * Adapter over [EventPickerOption] with the picker's substring filter. An empty result is rendered
 * as one non-clickable "No matching events" row instead of an empty popup.
 */
private class EventPickerAdapter(private val context: Context) : BaseAdapter(), Filterable {

    private var all: List<EventPickerOption> = emptyList()
    private var shown: List<EventPickerOption> = emptyList()
    private var selectedId: String? = null
    private var noMatch = false

    fun setOptions(options: List<EventPickerOption>, selectedId: String?) {
        all = options
        shown = options
        this.selectedId = selectedId
        noMatch = false
        notifyDataSetChanged()
    }

    /** Synchronous substring filter; [openList] resets to the full list on every open. */
    fun applyQuery(query: String) {
        shown = filterEventOptions(all, query)
        noMatch = shown.isEmpty() && query.isNotBlank()
        notifyDataSetChanged()
    }

    fun optionAt(position: Int): EventPickerOption? = shown.getOrNull(position)

    fun selectableMatches(): List<EventPickerOption> = shown

    override fun getCount(): Int = shown.size + if (noMatch) 1 else 0

    override fun getItem(position: Int): Any? = optionAt(position)

    override fun getItemId(position: Int): Long = optionAt(position)?.id?.hashCode()?.toLong() ?: -1L

    override fun hasStableIds(): Boolean = false

    /** The no-match row is a message, not an item: the list must not offer it as selectable. */
    override fun isEnabled(position: Int): Boolean = optionAt(position) != null

    override fun areAllItemsEnabled(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_event_picker_row, parent, false)
        val option = optionAt(position)
        val title = row.findViewById<TextView>(R.id.eventPickerRowTitle)
        val meta = row.findViewById<TextView>(R.id.eventPickerRowMeta)
        if (option == null) {
            title.setText(R.string.ui_event_picker_no_match)
            title.setTextColor(ContextCompat.getColor(context, R.color.ui_text_secondary))
            meta.visibility = View.GONE
            row.isActivated = false
            row.isSelected = false
            row.isClickable = false
            row.isFocusable = false
            row.isLongClickable = false
            row.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Drop the delegate a recycled real row carried, so this row is not read as a button.
            ViewCompat.setAccessibilityDelegate(row, null)
            return row
        }
        title.text = option.title
        title.setTextColor(ContextCompat.getColorStateList(context, R.color.ui_menu_option_text))
        meta.text = option.meta.orEmpty()
        meta.visibility = if (option.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        row.isActivated = option.id == selectedId
        row.isSelected = option.id == selectedId
        // The popup's ListView owns the tap: a clickable row would consume the gesture itself and
        // the framework's item click would never fire from a touch.
        row.isClickable = false
        row.isFocusable = false
        row.isLongClickable = false
        row.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.isSelected = host.isSelected
                // AbsListView skips its own item delegate when the row already has one, so the
                // click action it would have added has to come from here.
                info.isClickable = true
                info.addAction(AccessibilityActionCompat.ACTION_CLICK)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                    val list = host.parent as? AbsListView
                    val position = list?.getPositionForView(host) ?: -1
                    if (list != null && position >= 0) {
                        return list.performItemClick(host, position, list.getItemIdAtPosition(position))
                    }
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
        return row
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val filtered = filterEventOptions(all, constraint?.toString().orEmpty())
            return FilterResults().apply {
                values = filtered
                count = filtered.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            shown = (results.values as? List<EventPickerOption>).orEmpty()
            noMatch = shown.isEmpty() && !constraint.isNullOrBlank()
            notifyDataSetChanged()
        }
    }
}
