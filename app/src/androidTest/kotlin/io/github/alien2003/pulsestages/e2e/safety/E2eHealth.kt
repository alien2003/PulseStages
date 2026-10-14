package io.github.alien2003.pulsestages.e2e.safety

import io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository
import io.github.alien2003.pulsestages.data.hc.DelaySleeper
import io.github.alien2003.pulsestages.data.hc.HealthConnectRepository
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.ProcessImportanceForegroundState
import io.github.alien2003.pulsestages.data.hc.RetryPolicy
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.Dispatchers
import java.time.Clock

/**
 * The single place where the suite creates the real Health Connect repository of the app. The repository is built
 * exactly as the app wires it (HealthConnectRepository behind BackgroundRuleHealthRepository, the app's context and
 * package) but with the suite's own logger, and it is wrapped in [SafetyGuardRepository] before it leaves this
 * object: no test ever holds the unguarded instance.
 */
object E2eHealth {
    /** The app's package (the data origin of its own records). */
    val ownPackage: String get() = E2e.app.packageName

    /** The real repository behind the safety guard. */
    @Suppress("InjectDispatcher") // The suite wires the repository itself, like the app's DataHcModule.
    fun guardedRepository(logger: AppLogger): SafetyGuardRepository {
        val context = E2e.app.applicationContext
        val platform =
            HealthConnectRepository(
                context,
                context.packageName,
                Dispatchers.IO,
                DelaySleeper,
                RetryPolicy.DEFAULT,
                logger,
                Clock.systemUTC(),
            )
        return SafetyGuardRepository(BackgroundRuleHealthRepository(platform, context.packageName, ProcessImportanceForegroundState))
    }

    /** [guardedRepository] with every read charged to the run's [ReadBudget] ([ReadCharging.BUDGETED]). */
    fun budgetedRepository(
        logger: AppLogger,
        budget: ReadBudget = ReadBudget.shared(),
    ): HealthRepository = BudgetedReadRepository(guardedRepository(logger), budget, ReadCharging.BUDGETED)

    /** [guardedRepository] for reads earlier steps left room for: they wait for the window, never refused ([ReadCharging.RESERVED]). */
    fun reservedRepository(logger: AppLogger): HealthRepository =
        BudgetedReadRepository(guardedRepository(logger), ReadBudget.shared(), ReadCharging.RESERVED)

    /**
     * [guardedRepository] whose reads are only recorded, never held back or refused by the suite's budget
     * ([ReadCharging.RECORDED]): the cleanup's, and the real write's while its test record exists.
     */
    fun recordedRepository(logger: AppLogger): HealthRepository =
        BudgetedReadRepository(guardedRepository(logger), ReadBudget.shared(), ReadCharging.RECORDED)
}
