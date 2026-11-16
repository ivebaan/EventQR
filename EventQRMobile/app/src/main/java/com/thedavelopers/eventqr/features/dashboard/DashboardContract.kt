package com.thedavelopers.eventqr.features.dashboard

import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary

interface DashboardContract {
    interface View {
        fun showLoading(isLoading: Boolean)
        fun showSummary(summary: DashboardSummary)
        /**
         * The deferred "rewards claimed" answer: it lands after the rest of the screen painted, so only
         * that one pass-head cell changes. Never called when the fan-out failed (the cell keeps `--`).
         */
        fun showRewardsCount(count: Long)
        fun showError(message: String)
        fun showMessage(message: String)
        /**
         * A source inside the dashboard failed while the rest of the screen loaded. §5.16 collapses
         * every such failure to one sentence in the in-flow strip; [source] is for the log only.
         */
        fun showPartialError(source: String, message: String)
        fun openSection(title: String, message: String)
        fun updateHeader(role: String?, name: String?)
    }
}
