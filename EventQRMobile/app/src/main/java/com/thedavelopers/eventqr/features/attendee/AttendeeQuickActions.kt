package com.thedavelopers.eventqr.features.attendee

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.ui.icons.FieldIcons

/**
 * The attendee quick-action chips (§6.2): the dashboard's own `QUICK ACTIONS` strip, declared in
 * `activity_user_dashboard.xml`. This file owns the behaviour, so the three destinations and the
 * 20dp glyphs are defined once: the dashboard binds its chips through
 * [configureAttendeeQuickActions], and the chips resolve their screens through the pure
 * [attendeeQuickActionDestination]. Home is the strip's only host - the attendee Profile reaches
 * `My passes` through its own bar item now (§5.7's five-item rule), so no row reads this map.
 */
internal val attendeeQuickActionChipIds = intArrayOf(
    R.id.btnMyPasses,
    R.id.btnTransactionHistory,
    R.id.btnRequestEventCard,
)

/** Pure id-to-destination mapping for the chips; unmapped ids resolve to null (no-op). */
internal fun attendeeQuickActionDestination(chipId: Int): Class<out AppCompatActivity>? = when (chipId) {
    R.id.btnMyPasses -> RegisteredEventsActivity::class.java
    R.id.btnTransactionHistory -> AttendeeTransactionsActivity::class.java
    R.id.btnRequestEventCard -> RequestEventActivity::class.java
    else -> null
}

/**
 * Wires every chip the host inflated: the 20dp compound glyph (a compound drawable would otherwise
 * lay out at the vector's 24dp intrinsic size, §5.6) and the click that opens
 * [attendeeQuickActionDestination]. A host without the chips is a no-op.
 */
fun AppCompatActivity.configureAttendeeQuickActions() {
    attendeeQuickActionChipIds.forEach { id ->
        val chip = findViewById<TextView>(id) ?: return@forEach
        FieldIcons.apply(chip)
        val destination = attendeeQuickActionDestination(id) ?: return@forEach
        chip.setOnClickListener { startActivity(Intent(this, destination)) }
    }
}
