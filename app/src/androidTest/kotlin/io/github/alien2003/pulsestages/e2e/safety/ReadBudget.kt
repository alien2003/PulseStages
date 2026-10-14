package io.github.alien2003.pulsestages.e2e.safety

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the suite's Health Connect reads within a share of the quota of the app (the instrumentation runs as the app,
 * so its reads count against the app's quota: AOSP defaults 2000 reads per 15 min and 16000 per day in the foreground,
 * half of that in the background, docs/health-connect.md §7.2). Every real read call of the suite is charged here:
 * - at most [per15Min] calls in any 15-minute window: a call that would exceed it waits (the wait is streamed to the
 *   runner as progress);
 * - at most [total] calls in the whole run: a call beyond it is refused with [HealthDataException.QuotaExceeded] (the
 *   pipeline under test treats that like a real rate limit and stops the run, which the shadow test then reports).
 *
 * The charges are appended to `e2e/read-ledger.tsv`, so the windows span the separate instrumentation processes of one
 * run. Reads made by the app's own screens during the UI tour are not seen here; the UI tour waits for headroom first.
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

    /** Real rate-limit errors Health Connect returned to the suite. */
    val platformQuotaErrors: Int get() = platformErrors.get()

    /** Calls refused because [total] was reached. */
    val refusedCalls: Int get() = refusals.get()

    init {
        if (ledger.isFile) {
            ledger.readLines().forEach { line ->
                val parts = line.split('\t')
                val at = parts.getOrNull(0)?.toLongOrNull()
                val cost = parts.getOrNull(1)?.toIntOrNull()
                if (at != null && cost != null) entries += Entry(at, cost, parts.getOrNull(2).orEmpty())
            }
        }
    }

    /** Calls charged in this run so far. */
    fun spent(): Int = synchronized(entries) { entries.filter { it.runId == E2e.runId }.sumOf { it.cost } }

    /** Calls charged (by any run) in the 15 minutes before [nowMs]. */
    fun spentInWindow(nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(entries) { entries.filter { it.atMs > nowMs - WINDOW_MS }.sumOf { it.cost } }

    /** Calls still allowed in this run. */
    fun remaining(): Int = (total - spent()).coerceAtLeast(0)

    /**
     * Waits until [cost] more calls fit into the 15-minute window, then charges them for [who]. Throws
     * [HealthDataException.QuotaExceeded] when the run's [total] is used up.
     */
    suspend fun acquire(
        cost: Int,
        who: String,
    ) {
        if (spent() + cost > total) {
            refusals.incrementAndGet()
            throw HealthDataException.QuotaExceeded("e2e read budget of $total calls for this run is used up ($who)")
        }
        awaitWindow(cost, who)
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
            ledger.parentFile?.mkdirs()
            ledger.appendText("$now\t$cost\t${E2e.runId}\t$who\n")
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

    /** Defaults and the process-wide instance. */
    companion object {
        /** Instrumentation argument overriding [per15Min]. */
        const val ARG_PER_15_MIN: String = "e2eReadsPer15Min"

        /** Instrumentation argument overriding [total]. */
        const val ARG_TOTAL: String = "e2eReadBudget"

        /** 75 % of the foreground 15-minute read quota (2000). */
        const val DEFAULT_PER_15_MIN: Int = 1500

        /** 37.5 % of the foreground daily read quota (16000), 75 % of the background one (8000). */
        const val DEFAULT_TOTAL: Int = 6000

        private const val WINDOW_MS = 15L * 60 * 1000
        private const val SAFETY_MARGIN_MS = 2_000L
        private const val SECOND_MS = 1_000L
        private const val MAX_SINGLE_WAIT_MS = 60_000L

        @Volatile
        private var instance: ReadBudget? = null

        /** The budget of this process (shared by every reader of the suite). */
        fun shared(): ReadBudget =
            instance ?: synchronized(this) {
                instance ?: ReadBudget(
                    ledger = E2e.file("read-ledger.tsv"),
                    per15Min = E2e.intArg(ARG_PER_15_MIN, DEFAULT_PER_15_MIN).coerceAtLeast(1),
                    total = E2e.intArg(ARG_TOTAL, DEFAULT_TOTAL).coerceAtLeast(1),
                ).also { instance = it }
            }
    }
}
