package com.pagetime.app.data.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading what a request actually cost.
 *
 * The counters that appear vary by model and API version, so the parse has to
 * survive any of them being missing. The failure to avoid is recording zeroes
 * when the truth is "not reported" — that quietly turns "we do not know" into
 * "it was free".
 */
class GeminiUsageParserTest {

    private fun response(usage: String) = """{"candidates":[],$usage}"""

    @Test
    fun `a thinking model reports its reasoning separately`() {
        val json = response(
            """"usageMetadata":{
                "promptTokenCount":812,
                "candidatesTokenCount":204,
                "thoughtsTokenCount":1150,
                "totalTokenCount":2166
            }"""
        )
        val usage = GeminiUsageParser.parse(json, "gemini-2.5-flash")!!
        assertEquals(812, usage.promptTokens)
        assertEquals(204, usage.outputTokens)
        assertEquals(1150, usage.thinkingTokens)
        assertEquals(2166, usage.totalTokens)
        assertEquals("gemini-2.5-flash", usage.model)
    }

    /**
     * The total is whatever the API said, never a sum of the parts. Whether
     * thoughts are already inside candidates has changed between API versions,
     * and adding them up here would produce a confident number that drifts
     * from the invoice.
     */
    @Test
    fun `the reported total is taken as given even when it is not the sum`() {
        val json = response(
            """"usageMetadata":{
                "promptTokenCount":100,
                "candidatesTokenCount":50,
                "thoughtsTokenCount":40,
                "totalTokenCount":150
            }"""
        )
        val usage = GeminiUsageParser.parse(json, "m")!!
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun `a model that does not think reports no thoughts`() {
        val json = response(
            """"usageMetadata":{
                "promptTokenCount":300,
                "candidatesTokenCount":120,
                "totalTokenCount":420
            }"""
        )
        val usage = GeminiUsageParser.parse(json, "m")!!
        assertEquals(0, usage.thinkingTokens)
        assertEquals(0, usage.cachedTokens)
        assertEquals(420, usage.totalTokens)
    }

    @Test
    fun `cached tokens are recorded because they bill differently`() {
        val json = response(
            """"usageMetadata":{
                "promptTokenCount":9000,
                "cachedContentTokenCount":8000,
                "candidatesTokenCount":100,
                "totalTokenCount":9100
            }"""
        )
        assertEquals(8000, GeminiUsageParser.parse(json, "m")!!.cachedTokens)
    }

    @Test
    fun `a response with no usage block yields nothing rather than zeroes`() {
        assertNull(GeminiUsageParser.parse("""{"candidates":[]}""", "m"))
    }

    @Test
    fun `an empty usage block is not a free request`() {
        assertNull(GeminiUsageParser.parse(response(""""usageMetadata":{}"""), "m"))
    }

    @Test
    fun `a body that is not JSON at all does not throw`() {
        // A blocked or truncated response must cost the caller nothing beyond
        // the counts it could not read.
        assertNull(GeminiUsageParser.parse("<html>502 Bad Gateway</html>", "m"))
        assertNull(GeminiUsageParser.parse("", "m"))
    }

    @Test
    fun `a partial block keeps the counters that are present`() {
        val json = response(""""usageMetadata":{"promptTokenCount":42}""")
        val usage = GeminiUsageParser.parse(json, "m")!!
        assertEquals(42, usage.promptTokens)
        assertEquals(0, usage.totalTokens)
    }
}
