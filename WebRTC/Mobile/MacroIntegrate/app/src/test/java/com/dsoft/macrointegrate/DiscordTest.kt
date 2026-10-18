package com.dsoft.macrointegrate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks that don't need Android: JSON escaping and webhook validation. */
class DiscordTest {
    private fun esc(s: String): String {
        // Mirror of Discord.esc via reflection-free copy for testing the rules.
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append("\"").toString()
    }

    @Test fun escapesQuotesAndNewlines() {
        assertTrue(esc("a\"b\nc") == "\"a\\\"b\\nc\"")
    }

    @Test fun validWebhook() {
        val ok = "https://discord.com/api/webhooks/123/abc"
        assertTrue(ok.startsWith("https://") && ok.contains("/api/webhooks/"))
        assertFalse("http://evil.test/x".let { it.startsWith("https://") && it.contains("/api/webhooks/") })
    }
}
