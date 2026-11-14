package com.thedavelopers.eventqr.features.organizer.idtemplate

import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.color
import com.thedavelopers.eventqr.ui.theme.configDpToPx
import com.thedavelopers.eventqr.ui.theme.dimenPx
import com.thedavelopers.eventqr.ui.theme.strong
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.lifecycleScope
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.features.idprinting.IdCardLayoutConfig
import com.thedavelopers.eventqr.features.organizer.*
import com.thedavelopers.eventqr.features.organizer.tintBlock
import com.thedavelopers.eventqr.features.organizer.tintBlockArgb
import com.thedavelopers.eventqr.features.registrations.RegistrationNumberFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * SDD Module 3.7 — Configure ID Display Fields.
 *
 * Deviation note (capstone defense): SRS UC-22 describes logo upload, color editing, and
 * predefined template selection; SDD 3.7 explicitly overrides it — "organizer cannot edit the
 * ID layout, design, colors, logo, or visual format." Only field visibility toggling is
 * implemented here. No color picker, logo upload, or template style selector exists in this
 * screen (no leftover scaffolding for those was present to remove).
 *
 * Architecture note: implemented with the programmatic View toolkit used by every other
 * organizer screen (team decision), not Compose as the SDD text implies.
 *
 * Layout proportions are driven by [IdCardLayoutConfig] so this preview stays
 * in sync with the print output from [com.thedavelopers.eventqr.features.idprinting.AndroidIdPrinter].
 */
class IdTemplateSettingsActivity : AppCompatActivity() {

    private var repositoryFactory: (Context) -> IdTemplateConfigRepository = { IdTemplateConfigRepository(it) }

    @VisibleForTesting
    internal lateinit var repository: IdTemplateConfigRepository

    /** Swapped before `create()` so the screen's load runs against a fake instead of the network. */
    @VisibleForTesting
    internal fun installRepositoryFactory(factory: (Context) -> IdTemplateConfigRepository) {
        repositoryFactory = factory
    }

    private lateinit var eventId: String
    private lateinit var content: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var saveButton: Button

    /** The four option rows, in [IdCardLayoutConfig.OPTIONAL_FIELDS] order, keyed by field. */
    private val checkboxViews = linkedMapOf<String, CheckBox>()

    /** True while [refreshCheckboxes] writes programmatic state, so no toggle reaches the preview. */
    private var bindingCheckboxes = false

    private val fieldStates = linkedMapOf(
        IdCardLayoutConfig.FIELD_ATTENDEE_ID to false,
        IdCardLayoutConfig.FIELD_ROLE to false,
        IdCardLayoutConfig.FIELD_EVENT_NAME to false,
        IdCardLayoutConfig.FIELD_EVENT_DATE to false,
    )

    // Preview sizes derived from shared config ratios × preview card dimensions
    private val previewQrSizeDp = (IdCardLayoutConfig.PREVIEW_WIDTH_DP * IdCardLayoutConfig.QR_SIZE_RATIO).roundToInt()
    private val previewMarginDp = (IdCardLayoutConfig.PREVIEW_WIDTH_DP * IdCardLayoutConfig.MARGIN_RATIO).roundToInt()
    private val previewLabelFontSp = (IdCardLayoutConfig.PREVIEW_HEIGHT_DP * IdCardLayoutConfig.LABEL_FONT_RATIO).roundToInt()
    private val previewNameFontSp = (IdCardLayoutConfig.PREVIEW_HEIGHT_DP * IdCardLayoutConfig.NAME_FONT_RATIO).roundToInt()
    private val previewEventNameFontSp = (IdCardLayoutConfig.PREVIEW_HEIGHT_DP * IdCardLayoutConfig.EVENT_NAME_FONT_RATIO).roundToInt()
    private val previewRoleFontSp = (IdCardLayoutConfig.PREVIEW_HEIGHT_DP * IdCardLayoutConfig.ROLE_FONT_RATIO).roundToInt()
    private val previewIdFontSp = (IdCardLayoutConfig.PREVIEW_HEIGHT_DP * IdCardLayoutConfig.ID_FONT_RATIO).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = repositoryFactory(this)
        eventId = intentEventId() ?: return showMissingEventScreen(getString(R.string.ui_organizer_id_template_title))

        content = organizerShell(
            title = getString(R.string.ui_organizer_id_template_title),
            showBack = true,
        )

        content.addView(lockedFieldsCard().apply {
            id = com.thedavelopers.eventqr.R.id.idt_locked_card
        })
        content.addView(toggleCard().apply {
            id = com.thedavelopers.eventqr.R.id.idt_toggles_card
        })
        previewContainer = LinearLayout(this).apply {
            id = com.thedavelopers.eventqr.R.id.idt_preview_container
            orientation = LinearLayout.VERTICAL
        }
        content.addView(previewContainer)
        renderPreview()

        statusView = styledText("", R.dimen.type_body_compact, false).apply {
            id = com.thedavelopers.eventqr.R.id.idt_status
            setPadding(dimenPx(R.dimen.space_xs), dimenPx(R.dimen.space_sm), dimenPx(R.dimen.space_xs), 0)
            // A save answer appears here without moving focus: announce it when it lands.
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        content.addView(statusView)

        saveButton = primaryButton(getString(R.string.ui_organizer_id_template_save)) { saveConfig() }.apply {
            id = com.thedavelopers.eventqr.R.id.idt_save_button
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dimenPx(R.dimen.control_height_50),
            ).apply { setMargins(0, dimenPx(R.dimen.space_md), 0, dimenPx(R.dimen.space_xl)) }
        }
        content.addView(saveButton)

        loadConfig()
    }

    private fun lockedFieldsCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg))
        tintBlock(R.color.ui_surface, R.drawable.bg_ui_block_r16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, dimenPx(R.dimen.space_lg)) }

        addView(styledText(getString(R.string.ui_organizer_id_template_always_included), R.dimen.type_body, true, color(R.color.text_primary)).apply {
            setPadding(0, 0, 0, dimenPx(R.dimen.space_md))
        })

        addView(createLockedRow(
            title = getString(R.string.ui_organizer_id_template_qr_code),
            subtitle = getString(R.string.ui_organizer_id_template_always_shown),
            iconRes = AppIcons.Scan,
        ))

        addView(createDivider())

        addView(createLockedRow(
            title = getString(R.string.ui_organizer_id_template_attendee_name),
            subtitle = getString(R.string.ui_organizer_id_template_always_shown),
            iconRes = AppIcons.Profile,
        ))
    }

    private fun createLockedRow(title: String, subtitle: String, iconRes: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dimenPx(R.dimen.space_sm), 0, dimenPx(R.dimen.space_sm))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            // Left Icon Container
            addView(ImageView(this@IdTemplateSettingsActivity).apply {
                setImageResource(iconRes)
                setColorFilter(color(R.color.text_body))
                tintBlock(R.color.ui_surface_sunken, R.drawable.bg_ui_block_r10)
                setPadding(dimenPx(R.dimen.space_9), dimenPx(R.dimen.space_9), dimenPx(R.dimen.space_9), dimenPx(R.dimen.space_9))
                layoutParams = LinearLayout.LayoutParams(dimenPx(R.dimen.tile_38), dimenPx(R.dimen.tile_38))
            })

            // Middle Text
            addView(LinearLayout(this@IdTemplateSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dimenPx(R.dimen.space_md), 0, dimenPx(R.dimen.space_sm), 0)
                }
                addView(styledText(title, R.dimen.type_body, true, color(R.color.text_primary)))
                addView(styledText(subtitle, R.dimen.type_caption, false, color(R.color.text_secondary)).apply {
                    setPadding(0, dimenPx(R.dimen.space_xxs), 0, 0)
                })
            })

            // Right Locked Badge
            addView(LinearLayout(this@IdTemplateSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tintBlock(R.color.ui_surface_sunken, R.drawable.bg_ui_block_r10)
                setPadding(dimenPx(R.dimen.space_sm), dimenPx(R.dimen.space_xs), dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_xs))

                addView(ImageView(this@IdTemplateSettingsActivity).apply {
                    setImageResource(AppIcons.Lock)
                    setColorFilter(color(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(dimenPx(R.dimen.icon_md), dimenPx(R.dimen.icon_md)).apply {
                        marginEnd = dimenPx(R.dimen.space_xs)
                    }
                })
                addView(
                    styledText(
                        getString(R.string.ui_organizer_id_template_locked),
                        R.dimen.type_overline,
                        true,
                        color(R.color.text_secondary),
                    ),
                )
            })
        }

    private fun toggleCard(): LinearLayout = LinearLayout(this).apply {
        tag = TOGGLES_TAG
        orientation = LinearLayout.VERTICAL
        setPadding(dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg))
        tintBlock(R.color.ui_surface, R.drawable.bg_ui_block_r16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, dimenPx(R.dimen.space_lg)) }

        buildToggleCardContent(this)
    }

    private fun buildToggleCardContent(container: LinearLayout) {
        container.removeAllViews()
        checkboxViews.clear()

        val title = styledText(getString(R.string.ui_organizer_id_template_show_on_printed_id), R.dimen.type_body, true, color(R.color.text_primary))
        ViewCompat.setAccessibilityHeading(title, true)
        title.setPadding(0, 0, 0, dimenPx(R.dimen.space_md))
        container.addView(title)

        IdCardLayoutConfig.OPTIONAL_FIELDS.forEachIndexed { index, field ->
            container.addView(createToggleRow(field))

            if (index < IdCardLayoutConfig.OPTIONAL_FIELDS.lastIndex) {
                // 20dp mark + 12dp drawablePadding = space_xxl 32dp: the rule starts on the label edge.
                container.addView(createDivider(dimenPx(R.dimen.space_xxl)))
            }
        }
    }

    /**
     * One option as the shared `Widget.EventQR.Ui.CheckBox` (§5.2): 20dp mark, 12dp gap, 48dp touch,
     * label in `ui_text_primary`. `match_parent` so the whole row is the target, and the checkbox owns
     * the toggle itself - no parent click handler, and no chevron, because these rows do not navigate.
     */
    private fun createToggleRow(field: String): CheckBox =
        CheckBox(this, null, 0, R.style.Widget_EventQR_Ui_CheckBox).apply {
            tag = field
            text = IdCardLayoutConfig.displayName(field)
            isChecked = fieldStates.getValue(field)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnCheckedChangeListener { _, isChecked ->
                if (bindingCheckboxes) return@setOnCheckedChangeListener
                fieldStates[field] = isChecked
                renderPreview()
            }
            checkboxViews[field] = this
        }

    /** Hairline rule. [startInsetPx] aligns it to a row's text edge; 0 keeps it full bleed. */
    private fun createDivider(startInsetPx: Int = 0): View = View(this).apply {
        setBackgroundColor(color(R.color.surface_muted))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimenPx(R.dimen.stroke_hairline),
        ).apply {
            if (startInsetPx > 0) marginStart = startInsetPx
        }
    }

    private fun renderPreview() {
        previewContainer.removeAllViews()

        val outerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_20))
            tintBlock(R.color.ui_surface, R.drawable.bg_ui_block_r16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dimenPx(R.dimen.space_lg)) }

            addView(styledText(getString(R.string.ui_organizer_id_template_preview_title), R.dimen.type_body, true, color(R.color.text_primary)).apply {
                setPadding(0, 0, 0, dimenPx(R.dimen.space_md))
            })
        }

        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dimenPx(R.dimen.space_20), dimenPx(R.dimen.space_20), dimenPx(R.dimen.space_20), dimenPx(R.dimen.space_20))
            tintBlock(R.color.ui_background, R.drawable.bg_ui_block_r10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg), dimenPx(R.dimen.space_lg))
            tintBlock(R.color.ui_surface, R.drawable.bg_ui_block_r16)
            layoutParams = LinearLayout.LayoutParams(
                configDp(IdCardLayoutConfig.PREVIEW_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // 1. QR Code section
        val qrSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dimenPx(R.dimen.space_md)) }

            addView(styledText(getString(R.string.ui_organizer_id_template_qr_code).uppercase(), R.dimen.type_micro, true, color(R.color.chart_5), align = Gravity.CENTER_HORIZONTAL).apply {
                includeFontPadding = false
                setPadding(0, 0, 0, dimenPx(R.dimen.space_6))
            })

            val qrBox = LinearLayout(this@IdTemplateSettingsActivity).apply {
                gravity = Gravity.CENTER
                tintBlock(R.color.ui_primary_container, R.drawable.bg_ui_block_r10)
                layoutParams = LinearLayout.LayoutParams(configDp(previewQrSizeDp), configDp(previewQrSizeDp)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(ImageView(this@IdTemplateSettingsActivity).apply {
                    setImageResource(AppIcons.QrFrameCorner)
                    layoutParams = LinearLayout.LayoutParams(configDp(previewQrSizeDp - 20), configDp(previewQrSizeDp - 20))
                })
            }
            addView(qrBox)
        }
        cardView.addView(qrSection)

        // 2. Attendee Name (ALWAYS shown)
        cardView.addView(previewField(
            label = getString(R.string.ui_organizer_id_template_attendee_name).uppercase(),
            value = getString(R.string.ui_organizer_id_template_sample_name),
            valueSizeSp = previewNameFontSp,
            isBoldValue = true,
        ))

        // 3. Role
        if (fieldStates.getValue(IdCardLayoutConfig.FIELD_ROLE)) {
            cardView.addView(previewField(
                label = IdCardLayoutConfig.displayName(IdCardLayoutConfig.FIELD_ROLE).uppercase(),
                value = IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_ROLE),
                valueSizeSp = previewRoleFontSp,
                isBoldValue = true,
            ))
        }

        // 4. Event Name
        if (fieldStates.getValue(IdCardLayoutConfig.FIELD_EVENT_NAME)) {
            cardView.addView(createPreviewDivider())
            val displayEventTitle = intentEventTitle()?.takeIf { it.isNotBlank() }
                ?: IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_EVENT_NAME)
            cardView.addView(previewField(
                label = IdCardLayoutConfig.displayName(IdCardLayoutConfig.FIELD_EVENT_NAME).uppercase(),
                value = displayEventTitle,
                valueSizeSp = previewEventNameFontSp,
                isBoldValue = true,
            ))
        }

        // 5. Attendee ID
        if (fieldStates.getValue(IdCardLayoutConfig.FIELD_ATTENDEE_ID)) {
            cardView.addView(createPreviewDivider())
            val formattedId = RegistrationNumberFormatter.format(
                IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_ATTENDEE_ID).toIntOrNull()
            ) ?: "#012"
            cardView.addView(previewField(
                label = IdCardLayoutConfig.displayName(IdCardLayoutConfig.FIELD_ATTENDEE_ID).uppercase(),
                value = formattedId,
                valueSizeSp = previewIdFontSp,
                isBoldValue = true,
            ))
        }

        // 6. Event Date
        if (fieldStates.getValue(IdCardLayoutConfig.FIELD_EVENT_DATE)) {
            cardView.addView(createPreviewDivider())
            cardView.addView(previewField(
                label = IdCardLayoutConfig.displayName(IdCardLayoutConfig.FIELD_EVENT_DATE).uppercase(),
                value = IdCardLayoutConfig.sampleValue(IdCardLayoutConfig.FIELD_EVENT_DATE),
                valueSizeSp = previewIdFontSp,
                isBoldValue = true,
            ))
        }

        // Bottom pill handle bar
        cardView.addView(View(this).apply {
            tintBlock(R.color.ui_hairline, R.drawable.bg_ui_block_r4)
            layoutParams = LinearLayout.LayoutParams(dimenPx(R.dimen.icon_tile_md), dimenPx(R.dimen.bar_thin)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dimenPx(R.dimen.space_md), 0, dimenPx(R.dimen.space_xs))
            }
        })

        previewBox.addView(cardView)
        outerCard.addView(previewBox)
        previewContainer.addView(outerCard)
    }

    private fun previewField(
        label: String,
        value: String,
        valueSizeSp: Int = 14,
        isBoldValue: Boolean = true,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dimenPx(R.dimen.space_3), 0, dimenPx(R.dimen.space_3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(configText(label, previewLabelFontSp, true, color(R.color.chart_5), align = Gravity.CENTER_HORIZONTAL).apply {
            includeFontPadding = false
        })
        addView(configText(value, valueSizeSp, isBoldValue, color(R.color.text_primary), align = Gravity.CENTER_HORIZONTAL).apply {
            includeFontPadding = false
            setPadding(0, dimenPx(R.dimen.space_xxs), 0, 0)
        })
    }

    private fun createPreviewDivider(): View = View(this).apply {
        setBackgroundColor(color(R.color.surface_muted))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimenPx(R.dimen.stroke_hairline),
        ).apply {
            setMargins(dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_xs), dimenPx(R.dimen.space_md), dimenPx(R.dimen.space_xs))
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            when (val result = repository.fetchConfig(eventId)) {
                is NetworkResult.Success -> {
                    result.data.visibleFields.filterNotNull().forEach { field ->
                        if (fieldStates.containsKey(field)) fieldStates[field] = true
                    }
                    refreshCheckboxes()
                    renderPreview()
                }

                is NetworkResult.Error -> {
                    // Older servers 404 when no template was saved yet: show the default (QR + name) card.
                    if ((result.throwable as? retrofit2.HttpException)?.code() == 404) {
                        refreshCheckboxes()
                        renderPreview()
                    } else {
                        showStatus(result.message, isError = true)
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun saveConfig() {
        val visibleFields = IdCardLayoutConfig.OPTIONAL_FIELDS.filter { fieldStates.getValue(it) }
        saveButton.isEnabled = false
        lifecycleScope.launch {
            when (val result = withContext(NonCancellable) { repository.saveConfig(eventId, visibleFields) }.also { ensureActive() }) {
                is NetworkResult.Success -> showStatus(getString(R.string.ui_organizer_id_template_saved), isError = false)
                is NetworkResult.Error -> showStatus(result.message, isError = true)
                NetworkResult.Loading -> Unit
            }
            saveButton.isEnabled = true
        }
    }

    /**
     * Pushes [fieldStates] into the four rows after a load. The listener is suppressed while these
     * programmatic writes land, and the rows are never rebuilt, so TalkBack focus stays where it was.
     */
    private fun refreshCheckboxes() {
        bindingCheckboxes = true
        checkboxViews.forEach { (field, checkBox) -> checkBox.isChecked = fieldStates.getValue(field) }
        bindingCheckboxes = false
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusView.text = message
        statusView.setTextColor(if (isError) orgColors.error else orgColors.success)
    }

    /**
     * Config-derived dp -> px for the ID preview geometry ([IdCardLayoutConfig] widths, ratios). These numbers mirror the printed
     * card, they are not design tokens, so they intentionally bypass the `R.dimen` scale.
     */
    private fun configDp(value: Int): Int = configDpToPx(value)

    /** Same as [configDp] for the preview font sizes (sp) derived from [IdCardLayoutConfig] ratios. */
    private fun configText(value: String, sizeSp: Int, bold: Boolean, color: Int, align: Int? = null): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            if (bold) strong()
            includeFontPadding = true
            align?.let { gravity = it }
        }

    companion object {
        private const val TOGGLES_TAG = "id_display_toggles"
    }
}

