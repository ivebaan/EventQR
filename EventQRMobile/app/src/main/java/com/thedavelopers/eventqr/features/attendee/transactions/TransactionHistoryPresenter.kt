package com.thedavelopers.eventqr.features.attendee

import android.util.Log
import com.google.gson.reflect.TypeToken
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.rewards.model.dto.PointTransactionResponse
import com.thedavelopers.eventqr.features.transactions.TransactionHistoryRow
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID

class TransactionHistoryPresenter(
    private var view: TransactionHistoryContract.View?,
    private val repository: AttendeeRepository,
    private val sessionManager: SessionManager,
    /** The persisted last-known layer; null keeps the screen network-only (unit tests). */
    private val cache: LastKnownCache? = null,
) {
    private val scope = MainScope()
    private var job: Job? = null

    /** True once rows are on screen: from then on a snapshot would be older than the content. */
    private var painted = false

    fun detach() {
        job?.cancel()
        scope.cancel()
        view = null
    }

    /**
     * @param forceNetwork a pull-to-refresh or an explicit retry bypasses the persisted snapshot and
     *   always asks the network for the registrations too; opening the screen serves a fresh full
     *   answer already on hand.
     *
     * The persisted merged list paints first (skeleton only when there is none), then the two global
     * reads paint the list on their own, and the per-event fan-out is enrichment that lands afterwards
     * and repaints once, so the skeleton never waits on N + M round trips. An empty base list over
     * events that still owe ledger rows is the one case that waits for it.
     */
    fun load(forceNetwork: Boolean = false) {
        // Cancels the previous load and with it any enrichment still running, so a late fan-out
        // cannot repaint the screen over the rows of a newer load.
        job?.cancel()
        job = scope.launch {
            // Captured before the reads: an account switch in flight must not refile this answer
            // under whoever is signed in when it lands.
            val owner = cache?.currentOwner().orEmpty()
            val snapshot = if (forceNetwork || painted) null else persistedSnapshot()
            if (snapshot != null) {
                painted = true
                view?.showLoading(false)
                view?.renderTransactions(snapshot)
            } else {
                view?.showLoading(true)
            }
            val base = loadBase(forceNetwork)
            // An empty base list with events to read is not a settled shape: painting it now would
            // flash the zero-transactions empty state before the ledger rows land, so the skeleton
            // stays up until the enrichment settles the list. Zero events means it is settled.
            val awaitingLedgerRows = base.rows.isEmpty() && base.eventIds.isNotEmpty()
            if (!awaitingLedgerRows) paint(base.rows, base.errorMessage)

            // Enrichment can only add rows. Its own failures keep what is already shown and are
            // logged: the strip belongs to the base read, which the user can retry as a whole.
            val enriched = enrich(base)
            when {
                awaitingLedgerRows -> paint(enriched.rows, base.errorMessage)
                enriched.rows != base.rows -> {
                    view?.renderTransactions(enriched.rows)
                    // The repaint clears the strip a failed base read had raised: keep it up.
                    base.errorMessage?.let { view?.showError(it) }
                }
            }
            // Only a complete answer (both global reads and every per-event read) replaces the
            // snapshot: a partial merge would paint a list the server never confirmed.
            if (base.errorMessage == null && enriched.failure == null) persistSnapshot(enriched.rows, owner)
        }
    }

    /**
     * Stops the skeleton and shows [rows] with the base read's failure. Only a failure with nothing
     * renderable replaces the list; a failure over readable rows rides the in-flow strip.
     */
    private fun paint(rows: List<TransactionHistoryRow>, errorMessage: String?) {
        painted = true
        view?.showLoading(false)
        if (errorMessage == null || rows.isNotEmpty()) view?.renderTransactions(rows)
        errorMessage?.let { view?.showError(it) }
    }

    private suspend fun persistedSnapshot(): List<TransactionHistoryRow>? {
        val cache = cache ?: return null
        val rows = cache.read<List<CachedTransactionRow>>(
            CachedTransactionRow.KEY,
            CachedTransactionRow.LIST_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data?.mapNotNull { it.toRow() }
        return rows?.takeIf { it.isNotEmpty() }
    }

    private suspend fun persistSnapshot(rows: List<TransactionHistoryRow>, owner: String) {
        cache?.write(CachedTransactionRow.KEY, rows.map(CachedTransactionRow::of), owner)
    }

    /**
     * Scans come from the only global endpoint; point transactions are per event, so they need one
     * read per registered (or scanned) event, after the first paint.
     */
    private suspend fun loadBase(forceNetwork: Boolean): BaseRead {
        val cached = if (forceNetwork) null else RegistrationsCache.fresh(sessionManager.getUserId())
        val registrationsResult = cached?.let { NetworkResult.Success(it) } ?: repository.getMyRegistrations()
        val scansResult = repository.getMyTransactions()
        val registrations = if (registrationsResult is NetworkResult.Success) registrationsResult.data else emptyList()
        val scans = if (scansResult is NetworkResult.Success) scansResult.data else emptyList()
        var failure = mergeFailure(null, registrationsResult)
        failure = mergeFailure(failure, scansResult)

        val eventTitles = mutableMapOf<String, String>()
        registrations.forEach { registration ->
            registration.eventTitle?.takeIf { it.isNotBlank() }?.let { eventTitles[registration.eventId.toString()] = it }
        }
        scans.forEach { scan ->
            val eventId = scan.eventId.toString()
            if (eventId !in eventTitles) scan.eventTitle?.takeIf { it.isNotBlank() }?.let { eventTitles[eventId] = it }
        }

        val eventIds = (registrations.map { it.eventId.toString() } + scans.map { it.eventId.toString() }).distinct()
        // Only an event with an approved redemption outcome row can hold a scan-based redemption
        // ledger row, so only those events need the claimed-rewards read.
        val redemptionEventIds = scans.asSequence()
            .filter { it.isApprovedRedemptionOutcome() }
            .map { it.eventId.toString() }
            .distinct()
            .toList()
        return BaseRead(
            scans = scans,
            eventTitles = eventTitles,
            eventIds = eventIds,
            redemptionEventIds = redemptionEventIds,
            rows = mergeTransactionHistory(scans, emptyList(), eventTitles),
            errorMessage = failure,
        )
    }

    /**
     * Bounded fan-out: one round trip per event, six in flight at most. awaitAll keeps the result
     * order aligned with the event ids so the merge stays deterministic, and the merge is the same
     * one the base load uses, so the repainted list is the list the screen would have shown at once.
     *
     * [Enrichment.failure] is null only when every per-event read answered: it gates the snapshot
     * write, so a list missing one event's ledger rows is never persisted as the attendee's history.
     */
    private suspend fun enrich(base: BaseRead): Enrichment {
        if (base.eventIds.isEmpty() && base.redemptionEventIds.isEmpty()) return Enrichment(base.rows)
        val (pointResults, redemptionResults) = coroutineScope {
            val limit = Semaphore(MAX_PARALLEL_EVENT_READS)
            val points = base.eventIds.map { eventId ->
                async { limit.withPermit { repository.getMyPointTransactions(eventId) } }
            }
            val redemptions = base.redemptionEventIds.map { eventId ->
                async { limit.withPermit { repository.getMyRewardRedemptions(eventId) } }
            }
            points.awaitAll() to redemptions.awaitAll()
        }
        var failure: String? = null
        val pointTransactions = mutableListOf<PointTransactionResponse>()
        for (result in pointResults) {
            when (result) {
                is NetworkResult.Success -> pointTransactions += result.data
                is NetworkResult.Error, NetworkResult.Loading -> failure = mergeFailure(failure, result)
            }
        }
        val redemptionScanLogIds = mutableMapOf<UUID, UUID?>()
        for (result in redemptionResults) {
            when (result) {
                is NetworkResult.Success ->
                    result.data.forEach { redemptionScanLogIds[it.redemptionId] = it.redemptionScanLogId }
                is NetworkResult.Error, NetworkResult.Loading -> failure = mergeFailure(failure, result)
            }
        }
        if (failure != null) {
            Log.w(TAG, "per-event enrichment incomplete: ${failure.ifBlank { "a read did not complete" }}")
        }
        return Enrichment(
            rows = mergeTransactionHistory(base.scans, pointTransactions, base.eventTitles, redemptionScanLogIds),
            failure = failure,
        )
    }

    /** What the per-event fan-out settled: the full history, and whether every read answered. */
    private data class Enrichment(
        val rows: List<TransactionHistoryRow>,
        /** Null when every per-event read succeeded. */
        val failure: String? = null,
    )

    /** What the two global reads settle: the half of the history that needs no per-event read. */
    private data class BaseRead(
        val scans: List<TransactionResponse>,
        val eventTitles: Map<String, String>,
        val eventIds: List<String>,
        val redemptionEventIds: List<String>,
        val rows: List<TransactionHistoryRow>,
        /** Null when both global reads succeeded; [rows] still holds whatever loaded when it is not. */
        val errorMessage: String? = null,
    )

    /** First failure wins; a real message outranks the empty message of a Loading result. */
    private fun mergeFailure(current: String?, result: NetworkResult<*>): String? = when (result) {
        is NetworkResult.Success -> current
        is NetworkResult.Error -> if (current.isNullOrBlank()) result.message.ifBlank { current ?: "" } else current
        NetworkResult.Loading -> current ?: ""
    }
}

/**
 * Newest first. A point row is dropped only when it duplicates a row already in the list:
 * - its `sourceTransactionId` names a scan row (a scan award writes the scan's id), or
 * - its `sourceTransactionId` names a redemption whose `redemptionScanLogId` is non-null (the
 *   redemption came from a scan, so its approved outcome row already shows it).
 *
 * A direct claim has no scan log, writes no outcome row, and its ledger row is the only trace, so
 * it stays. No delta is used as a key, so a manual adjustment that happens to move the same points
 * survives.
 *
 * A scan-based redemption keeps both of its scan rows: the 0-point REWARD_REDEMPTION_SCAN tracking
 * row and the REWARD_REDEMPTION outcome row. Only its duplicate ledger (point) row is dropped; the
 * tracking row is real history (the attendee's pass was scanned) and stays.
 */
internal fun mergeTransactionHistory(
    scans: List<TransactionResponse>,
    pointTransactions: List<PointTransactionResponse>,
    eventTitlesById: Map<String, String> = emptyMap(),
    redemptionScanLogIds: Map<UUID, UUID?> = emptyMap(),
): List<TransactionHistoryRow> {
    val scanIds = scans.mapTo(HashSet()) { it.transactionId }
    val rows = mutableListOf<TransactionHistoryRow>()
    scans.mapTo(rows) { TransactionHistoryRow.Scan(it) }

    pointTransactions.forEach { point ->
        if (point.sourceTransactionId in scanIds) return@forEach
        // Null covers both "not a redemption" and "direct claim": only a scan-backed redemption is
        // already represented by an outcome row.
        if (redemptionScanLogIds[point.sourceTransactionId] != null) return@forEach
        rows += TransactionHistoryRow.Point(
            eventId = point.eventId.toString(),
            eventTitle = eventTitlesById[point.eventId.toString()],
            occurredAt = point.occurredAt,
            pointsDelta = point.pointsChanged,
            reason = point.reason,
        )
    }
    return rows.sortedByDescending { it.occurredAt ?: Instant.EPOCH }
}

private const val MAX_PARALLEL_EVENT_READS = 6

private const val TAG = "TransactionHistory"

private fun TransactionResponse.isApprovedRedemptionOutcome(): Boolean =
    transactionResult == TransactionResult.APPROVED &&
        transactionType == TransactionType.REWARD_REDEMPTION

/**
 * The persisted shape of one history row.
 *
 * Gson cannot rebuild the sealed [TransactionHistoryRow] interface (an interface has no
 * constructor), so the snapshot stores this flat DTO and the presenter maps it back to the exact
 * same `Scan` / `Point` rows. A `scan` row carries the whole [TransactionResponse]; a `point` row
 * carries its own five fields. Nothing else is stored: no QR value, no token.
 */
internal data class CachedTransactionRow(
    /** "scan" or "point". */
    val kind: String,
    val transaction: TransactionResponse? = null,
    val eventId: String? = null,
    val eventTitle: String? = null,
    val occurredAt: Instant? = null,
    val pointsDelta: Int = 0,
    val reason: String? = null,
) {
    fun toRow(): TransactionHistoryRow? = when (kind) {
        KIND_SCAN -> transaction?.let { TransactionHistoryRow.Scan(it) }
        KIND_POINT -> TransactionHistoryRow.Point(
            eventId = eventId.orEmpty(),
            eventTitle = eventTitle,
            occurredAt = occurredAt,
            pointsDelta = pointsDelta,
            reason = reason,
        )
        else -> null
    }

    companion object {
        /** The persisted key for this list; it lives here because the type does (see [AttendeeSnapshots]). */
        const val KEY = "attendee/transactions/history"

        const val KIND_SCAN = "scan"
        const val KIND_POINT = "point"

        val LIST_TYPE: Type = TypeToken.getParameterized(List::class.java, CachedTransactionRow::class.java).type

        fun of(row: TransactionHistoryRow): CachedTransactionRow = when (row) {
            is TransactionHistoryRow.Scan -> CachedTransactionRow(KIND_SCAN, transaction = row.transaction)
            is TransactionHistoryRow.Point -> CachedTransactionRow(
                kind = KIND_POINT,
                eventId = row.eventId,
                eventTitle = row.eventTitle,
                occurredAt = row.occurredAt,
                pointsDelta = row.pointsDelta,
                reason = row.reason,
            )
        }
    }
}
