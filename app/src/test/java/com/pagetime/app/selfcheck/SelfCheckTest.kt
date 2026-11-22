package com.pagetime.app.selfcheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckTest {

    @Test
    fun `every rule scenario passes, so the button never reports a failure that is the test's own`() {
        val results = runBlocking { SelfCheck.runAll(RuleScenarios.all()) }
        val failed = results.filter { it.outcome.status != SelfCheck.Status.PASS }
        assertTrue(
            "Scenarios that did not pass: " + failed.joinToString { "${it.scenario.id} (${it.outcome.detail})" },
            failed.isEmpty(),
        )
        assertTrue(results.size >= 10)
    }

    @Test
    fun `scenario ids are unique`() {
        val ids = RuleScenarios.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an exception is a failure with its message, not a crash`() {
        val boom = SelfCheck.Scenario("x", "G", "Boom") { error("it broke") }
        val result = runBlocking { SelfCheck.runOne(boom) }
        assertEquals(SelfCheck.Status.FAIL, result.outcome.status)
        assertTrue(result.outcome.detail.contains("it broke"))
    }

    @Test
    fun `a failed check names what was expected`() {
        val out = SelfCheck.expect("a is a" to true, "b is b" to false, "c is c" to false)
        assertEquals(SelfCheck.Status.FAIL, out.status)
        assertEquals("Expected: b is b", out.detail)
    }

    @Test
    fun `the report groups, marks and ends with the crash log`() {
        val ok = SelfCheck.Scenario("1", "Rules", "A rule") { SelfCheck.pass("fine") }
        val bad = SelfCheck.Scenario("2", "Phone", "A thing") { SelfCheck.fail("not allowed") }
        val results = runBlocking { SelfCheck.runAll(listOf(ok, bad)) }
        val text = SelfCheck.report(results, listOf("Version 1"), "java.lang.Boom")
        assertTrue(text.contains("1 passed, 1 failed"))
        assertTrue(text.contains("== Rules"))
        assertTrue(text.contains("[PASS] A rule — fine"))
        assertTrue(text.contains("[FAIL] A thing — not allowed"))
        assertTrue(text.trim().endsWith("java.lang.Boom"))
    }
}
