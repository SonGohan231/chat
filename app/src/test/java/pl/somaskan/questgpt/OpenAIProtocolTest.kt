package pl.somaskan.questgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIProtocolTest {
    @Test
    fun parsesAssistantTextAndFunctionCall() {
        val raw = """
            {
              "id":"resp_123",
              "output":[
                {"type":"message","content":[{"type":"output_text","text":"Widzę ekran Questa."}]},
                {"type":"function_call","call_id":"call_1","name":"press_key","arguments":"{\"key\":\"BACK\"}"}
              ]
            }
        """.trimIndent()

        val parsed = OpenAIProtocol.parseResponse(raw)
        assertEquals("resp_123", parsed.id)
        assertEquals("Widzę ekran Questa.", parsed.text)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("press_key", parsed.toolCalls.first().name)
        assertEquals("BACK", parsed.toolCalls.first().arguments.getString("key"))
    }

    @Test
    fun classifiesInvalidApiKey() {
        val message = OpenAIProtocol.errorMessage(
            401,
            """{"error":{"message":"Incorrect API key provided","code":"invalid_api_key"}}"""
        )
        assertTrue(message.contains("401"))
        assertTrue(message.contains("klucz", ignoreCase = true))
    }

    @Test
    fun distinguishesQuotaFromRateLimit() {
        val quota = OpenAIProtocol.errorMessage(
            429,
            """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}"""
        )
        val rate = OpenAIProtocol.errorMessage(
            429,
            """{"error":{"message":"Rate limit reached","code":"rate_limit_exceeded"}}"""
        )
        assertTrue(quota.contains("środk", ignoreCase = true) || quota.contains("limitu", ignoreCase = true))
        assertTrue(rate.contains("szybkości", ignoreCase = true))
    }
}
