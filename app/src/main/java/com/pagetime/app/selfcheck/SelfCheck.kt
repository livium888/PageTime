package com.pagetime.app.selfcheck

import kotlinx.coroutines.CancellationException

/**
 * Scenarios the reader can run with one button, on their own phone.
 *
 * They exist for what cannot be checked where the app is built: whether a rule
 * still holds on the device, whether the app can reach what it depends on, and
 * whether something we fixed has quietly broken again. They cannot say how
 * anything looks or feels, and a scenario only proves what it was written to
 * look for. Each release adds the scenarios for what it changed.
 *
 * Nothing here touches the reader's own library or settings: storage scenarios
 * run against a throwaway database and file, and network scenarios only read.
 */
object SelfCheck {

    enum class Status { PASS, FAIL, SKIP, INFO }

    data class Outcome(val status: Status, val detail: String)

    class Scenario(
        val id: String,
        val group: String,
        val title: String,
        val run: suspend () -> Outcome,
    )

    data class Result(val scenario: Scenario, val outcome: Outcome, val millis: Long)

    fun pass(detail: String = "") = Outcome(Status.PASS, detail)
    fun fail(detail: String) = Outcome(Status.FAIL, detail)
    fun skip(detail: String) = Outcome(Status.SKIP, detail)
    fun info(detail: String) = Outcome(Status.INFO, detail)

    /** Passes when every condition holds; otherwise fails naming the first that does not. */
    fun expect(vararg checks: Pair<String, Boolean>): Outcome {
        val broken = checks.firstOrNull { !it.second }
        return if (broken == null) pass() else fail("Expected: ${broken.first}")
    }

    /** Runs one scenario. An exception is a failure with its message, never a crash. */
    suspend fun runOne(scenario: Scenario, now: () -> Long = System::currentTimeMillis): Result {
        val start = now()
        val outcome = try {
            scenario.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
        return Result(scenario, outcome, now() - start)
    }

    suspend fun runAll(
        scenarios: List<Scenario>,
        onResult: (Result) -> Unit = {},
    ): List<Result> = scenarios.map { runOne(it).also(onResult) }

    fun summary(results: List<Result>): String {
        val failed = results.count { it.outcome.status == Status.FAIL }
        val passed = results.count { it.outcome.status == Status.PASS }
        val skipped = results.count { it.outcome.status == Status.SKIP }
        return buildString {
            append("$passed passed")
            append(", $failed failed")
            if (skipped > 0) append(", $skipped skipped")
        }
    }

    /** The text the reader copies and pastes to whoever is looking into a problem. */
    fun report(results: List<Result>, header: List<String>, crashTail: String?): String = buildString {
        appendLine("PageTime self-check")
        header.forEach { appendLine(it) }
        appendLine(summary(results))
        appendLine()
        var group = ""
        results.forEach { r ->
            if (r.scenario.group != group) {
                group = r.scenario.group
                appendLine("== $group")
            }
            val mark = when (r.outcome.status) {
                Status.PASS -> "PASS"
                Status.FAIL -> "FAIL"
                Status.SKIP -> "SKIP"
                Status.INFO -> "INFO"
            }
            append("[$mark] ${r.scenario.title}")
            if (r.outcome.detail.isNotBlank()) append(" — ${r.outcome.detail}")
            appendLine()
        }
        if (!crashTail.isNullOrBlank()) {
            appendLine()
            appendLine("== Latest crash log")
            appendLine(crashTail.trim())
        }
    }
}
