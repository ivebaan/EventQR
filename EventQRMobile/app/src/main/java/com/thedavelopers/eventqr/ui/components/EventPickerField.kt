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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
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
 * accessibility contract; tapping it opens the full list without a keyboard, typing filters, and
 * the chevron slot toggles the list.
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
    private var suppressRevert = false
    private var openedByFocus = false
    private var onSelected: ((String) -> Unit)? = null
    private var announceRunnable: Runnable? = null

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

        input.showSoftInputOnFocus = false
        input.setRawInputType(InputType.TYPE_CLASS_TEXT)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.threshold = 0
        input.isSaveEnabled = false
        input.freezesText = false
        input.dropDownWidth = LayoutParams.MATCH_PARENT
        input.setDropDownBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_ui_menu))
        input.dropDownVerticalOffset = dimenPx(R.dimen.space_xs)
        input.setAdapter(adapter)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (programmatic) return
                renderTrailing()
                meta.visibility = View.GONE
                // Filter synchronously so the popup never shows a stale list while the framework's
                // own Filter runs on its worker thread.
                val query = s?.toString().orEmpty()
                adapter.applyQuery(query)
                if (input.hasFocus() && !input.isPopupShowing && query.isNotBlank()) {
                    input.setDropDownHeight(dropDownHeightPx())
                    input.showDropDown()
                }
                scheduleResultsAnnouncement()
            }
        })

        input.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                meta.visibility = View.GONE
                if (!loading) {
                    openedByFocus = true
                    post { openedByFocus = false }
                    openList()
                }
            } else {
                dismissList()
                revertText()
            }
        }

        input.setOnClickListener {
            if (!isEnabled || loading) return@setOnClickListener
            // The tap that focused the field already opened the list; do not close it again.
            if (openedByFocus) return@setOnClickListener
            if (input.isPopupShowing) {
                // Second tap: hand over to the keyboard. Keep the query, select it so typing replaces it.
                suppressRevert = true
                input.dismissDropDown()
                suppressRevert = false
                input.selectAll()
                showKeyboard()
            } else {
                openList()
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
            if (!selecting && !suppressRevert) revertText()
        }

        trailing.setOnClickListener {
            if (!isEnabled || loading) return@setOnClickListener
            if (hasQuery()) {
                programmatic = true
                input.setText("", false)
                programmatic = false
                meta.visibility = View.GONE
                renderTrailing()
                openList()
            } else if (input.isPopupShowing) {
                input.dismissDropDown()
            } else {
                openList()
            }
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
        setLoading(false)
        programmatic = true
        input.setText(selected?.title.orEmpty(), false)
        programmatic = false
        meta.text = selected?.meta.orEmpty()
        meta.visibility = if (selected?.meta.isNullOrBlank() || input.hasFocus()) View.GONE else View.VISIBLE
        ViewCompat.setStateDescription(input, selected?.title)
        renderTrailing()
    }

    /** Loading state: the field shows the loading copy, is disabled and never opens the list. */
    fun setLoading(isLoading: Boolean) {
        loading = isLoading
        if (isLoading) {
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
        announceRunnable?.let { removeCallbacks(it) }
        announceRunnable = null
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable? {
        dismissList()
        return super.onSaveInstanceState()
    }

    private fun hasQuery(): Boolean {
        val text = input.text?.toString().orEmpty()
        return text.isNotEmpty() && text != selectedTitle
    }

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

    private fun openList() {
        if (!isEnabled || loading || options.isEmpty()) return
        // Every open starts from the full list: a selection must not leave the adapter filtered to
        // the chosen row (the classic "reopen shows only the selected item" bug).
        adapter.setOptions(options, selectedId)
        input.setDropDownHeight(dropDownHeightPx())
        if (!input.isPopupShowing) input.showDropDown()
    }

    private fun dismissList() {
        if (input.isPopupShowing) input.dismissDropDown()
    }

    private fun select(option: EventPickerOption) {
        selecting = true
        selectedId = option.id
        selectedTitle = option.title
        adapter.setOptions(options, selectedId)
        programmatic = true
        input.setText(option.title, false)
        programmatic = false
        meta.text = option.meta.orEmpty()
        meta.visibility = View.GONE
        ViewCompat.setStateDescription(input, option.title)
        renderTrailing()
        hideKeyboard()
        dismissList()
        selecting = false
        // The host may rebuild its whole tree: run after the popup has gone.
        post { onSelected?.invoke(option.id) }
    }

    /** Reverts an unsubmitted query to the selected event's name; never auto-selects. */
    private fun revertText() {
        if (loading) return
        programmatic = true
        input.setText(selectedTitle.orEmpty(), false)
        programmatic = false
        meta.text = options.firstOrNull { it.id == selectedId }?.meta.orEmpty()
        meta.visibility = if (meta.text.isNullOrBlank() || input.hasFocus()) View.GONE else View.VISIBLE
        renderTrailing()
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

    /** Height cap: 50% of the screen, clamped to the space below the field minus the IME/nav inset. */
    private fun dropDownHeightPx(): Int {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val screenHeight = resources.displayMetrics.heightPixels
        val insets = ViewCompat.getRootWindowInsets(this)
        val ime = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val nav = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val below = screenHeight - (location[1] + height) - maxOf(ime, nav) - dimenPx(R.dimen.space_8)
        val cap = (screenHeight * 0.5f).toInt()
        val minTwoRows = dimenPx(R.dimen.ui_touch_min) * 2
        return maxOf(minTwoRows, minOf(cap, below))
    }
}

/** AutoCompleteTextView with the picker's always-filterable contract (plan 5.2 "Event picker"). */
class EventPickerInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.autoCompleteTextViewStyle,
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {
    override fun enoughToFilter(): Boolean = true
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
            row.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            return row
        }
        title.text = option.title
        title.setTextColor(ContextCompat.getColorStateList(context, R.color.ui_menu_option_text))
        meta.text = option.meta.orEmpty()
        meta.visibility = if (option.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        row.isActivated = option.id == selectedId
        row.isSelected = option.id == selectedId
        row.isClickable = true
        row.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.isSelected = host.isSelected
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
