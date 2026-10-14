package io.github.alien2003.pulsestages.e2e.safety

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a read is charged to the [ReadBudget].
 * - [BUDGETED]: waits for room in the 15-minute window and is refused once the run's (or this step's) budget is spent;
 *   every read of the snapshots before the run, the shadow pipeline and the preparation of the real write.
 * - [RESERVED]: waits for room in the window but is never refused; for reads whose cost earlier steps left over for
 *   them (the "after" snapshot, the checks after the real write and the UI tour). The shadow step's cap keeps that room.
 * - [RECORDED]: neither waits nor is refused, only recorded; for the cleanup (it must never be held back by the suite's
 *   own budget) and for the verification reads while the test record exists (nothing may wait while it lives).
 */
enum class ReadCharging {
    BUDGETED,
    RESERVED,
    RECORDED,
}

/**
 * Keeps the suite's Health Connect reads within a share of the quota of the app (the instrumentation runs as the app,
 * so its reads count against the app's quota). The defaults assume the BACKGROUND quota, the lower one (AOSP defaults
 * 1000 reads per 15 min and 8000 per day in the background, twice that in the foreground; docs/health-connect.md §7.2):
 * an instrumentation without a visible activity may well be counted as a background caller. Every real read call of
 * the suite is charged here ([ReadCharging] says how):
 * - at most [per15Min] calls in any 15-minute window: a call that would exceed it waits (the wait is streamed to the
 *   runner as progress);
 * - at most [limit] calls in the whole run ([total], or less for one step with [capThisProcess]): a budgeted call
 *   beyond it is refused with [HealthDataException.QuotaExceeded] (the pipeline under test treats that like a real
 *   rate limit and stops, which the shadow test then reports).
 *
 * The charges are appended to `e2e/read-ledger.tsv`, so the windows span the separate instrumentation processes of one
 * run; the runner keeps the totals of earlier runs on this phone and passes a smaller [total] (and waits 15 minutes)
 * when they were recent. Reads made by the app's own screens during the UI tour are not seen here: the UI tour waits
 * for room for them first and charges that room afterwards.
 */
class ReadBudget private constructor(
    private val ledger: File,
    /** Calls allowed in any 15-minute window. */
    val per15Min: Int,
    /** Calls allowed in the whole run. */
    val total: Int,
) {
    private class Entry(
        val atMs: Long,
        val cost: Int,
        val runId: String,
    )

    private val entries = ArrayList<Entry>()

    private val platformErrors = AtomicInteger(0)
    private val refusals = AtomicInteger(0)

    @Volatile
    private var processCap: Int = Int.MAX_VALUE

    /** Real rate-limit errors Health Connect returned to the suite. */
    val platformQuotaErrors: Int get() = platformErrors.get()

    /** Calls refused because [limit] was reached. */
    val refusedCalls: Int get() = refusals.get()

    /** Calls a budgeted read may bring this run's total up to: [total], or the cap of this step. */
    val limit: Int get() = minOf(total, processCap)

    init {
        readLedger(ledger).forEach { entries += Entry(it.atMs, it.cost, it.runId) }
    }

    /**
     * Caps the budgeted reads of this process (one step) at [maxSpent] calls of the run in all, so that the steps
     * after it keep their share: the shadow pipeline may not read into what the "after" snapshot, the real write and
     * the UI tour need.
     */
    fun capThisProcess(maxSpent: Int) {
        processCap = maxSpent.coerceAtLeast(0)
    }

    /** Calls charged in this run so far. */
    fun spent(): Int = synchronized(entries) { entries.filter { it.runId == E2e.runId }.sumOf { it.cost } }

    /** Calls charged (by any run) in the 15 minutes before [nowMs]. */
    fun spentInWindow(nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(entries) { entries.filter { it.atMs > nowMs - WINDOW_MS }.sumOf { it.cost } }

    /** Budgeted calls still allowed in this run (and step). */
    fun remaining(): Int = (limit - spent()).coerceAtLeast(0)

    /**
     * Charges [cost] calls for [who] as [charging] says: a budgeted call is refused with
     * [HealthDataException.QuotaExceeded] when the [limit] is reached; budgeted and reserved calls wait until they fit
     * into the 15-minute window; recorded calls are only recorded.
     */
    suspend fun acquire(
        cost: Int,
        who: String,
        charging: ReadCharging = ReadCharging.BUDGETED,
    ) {
        if (charging == ReadCharging.BUDGETED && spent() + cost > limit) {
            refusals.incrementAndGet()
            throw HealthDataException.QuotaExceeded("e2e read budget of $limit calls for this run is used up ($who)")
        }
        if (charging != ReadCharging.RECORDED) awaitWindow(cost, who)
        charge(cost, who)
    }

    /** Charges [cost] calls that were already made (the extra pages of a read) without waiting. */
    fun charge(
        cost: Int,
        who: String,
    ) {
        if (cost <= 0) return
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries += Entry(now, cost, E2e.runId)
            // The ledger is bookkeeping: a failed append must never fail a read (least of all the cleanup's).
            runCatching {
                ledger.parentFile?.mkdirs()
                ledger.appendText("$now\t$cost\t${E2e.runId}\t$who\n")
            }
        }
    }

    /** Waits until [calls] calls fit into the 15-minute window (the UI tour leaves room for the app's own reads). */
    suspend fun awaitHeadroom(
        calls: Int,
        who: String,
    ) = awaitWindow(calls, who)

    /** Notes a real rate-limit error of Health Connect. */
    fun onPlatformQuotaError() {
        platformErrors.incrementAndGet()
    }

    private suspend fun awaitWindow(
        calls: Int,
        who: String,
    ) {
        // More than the whole window can never fit: wait for an empty window instead.
        val cost = calls.coerceAtMost(per15Min)
        var announced = false
        while (true) {
            val now = System.currentTimeMillis()
            val waitMs =
                synchronized(entries) {
                    val window = entries.filter { it.atMs > now - WINDOW_MS }.sortedBy { it.atMs }
                    var used = window.sumOf { it.cost }
                    if (used + cost <= per15Min) return
                    // The time at which enough of the oldest charges have left the window.
                    var releaseAt = now
                    for (entry in window) {
                        used -= entry.cost
                        releaseAt = entry.atMs + WINDOW_MS
                        if (used + cost <= per15Min) break
                    }
                    releaseAt - now + SAFETY_MARGIN_MS
                }
            if (!announced) {
                E2e.progress(
                    "throttling Health Connect reads ($who): ${spentInWindow()} of $per15Min in 15 min used, " +
                        "waiting ${waitMs / SECOND_MS} s",
                )
                announced = true
            }
            delay(waitMs.coerceIn(SECOND_MS, MAX_SINGLE_WAIT_MS))
        }
    }

    /** Defaults, the ledger file and the process-wide instance. */
    companion object {
        /** Instrumentation argument overriding [per15Min]. */
        const val ARG_PER_15_MIN: String = "e2eReadsPer15Min"

        /** Instrumentation argument overriding [total]. */
        const val ARG_TOTAL: String = "e2eReadBudget"

        /** 60 % of the background 15-minute read quota (1000), 30 % of the foreground one (2000). */
        const val DEFAULT_PER_15_MIN: Int = 600

        /** Half of the background daily read quota (8000), a quarter of the foreground one (16000). */
        const val DEFAULT_TOTAL: Int = 4000

        /** File name of the ledger inside the e2e directory; kept (pruned to a day) when a run starts. */
        const val LEDGER_FILE: String = "read-ledger.tsv"

        private const val WINDOW_MS = 15L * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SAFETY_MARGIN_MS = 2_000L
        private const val SECOND_MS = 1_000L
        private const val MAX_SINGLE_WAIT_MS = 60_000L

        @Volatile
        private var instance: ReadBudget? = null

        /** The budget of this process (shared by every reader of the suite). */
        fun shared(): ReadBudget =
            instance ?: synchronized(this) {
                instance ?: ReadBudget(
                    ledger = E2e.file(LEDGER_FILE),
                    per15Min = E2e.intArg(ARG_PER_15_MIN, DEFAULT_PER_15_MIN).coerceAtLeast(1),
                    total = E2e.intArg(ARG_TOTAL, DEFAULT_TOTAL).coerceAtLeast(1),
                ).also { instance = it }
            }

        /** Drops the ledger's entries older than a day (a new run keeps the recent windows of an earlier one). */
        fun pruneLedger(nowMs: Long = System.currentTimeMillis()) {
            val file = File(E2e.outDir, LEDGER_FILE)
            if (!file.isFile) return
            val kept = file.readLines().filter { line -> line.substringBefore('\t').toLongOrNull()?.let { it > nowMs - DAY_MS } == true }
            if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("\n", postfix = "\n"))
        }

        private class LedgerLine(
            val atMs: Long,
            val cost: Int,
            val runId: String,
        )

        private fun readLedger(file: File): List<LedgerLine> =
            if (!file.isFile) {
                emptyList()
            } else {
                file.readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    val at = parts.getOrNull(0)?.toLongOrNull()
                    val cost = parts.getOrNull(1)?.toIntOrNull()
                    if (at != null && cost != null) LedgerLine(at, cost, parts.getOrNull(2).orEmpty()) else null
                }
            }
    }
}
