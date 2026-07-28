package app.refract.keyboard.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaTransportSafeTokensTest {
    @Test
    fun `builder withholds base and canonical control tokens until frame completion`() {
        val canonicalTokens =
            mapOf(
                "<turn|>" to 20,
                "<|think|>" to 21,
                "(" to 22,
            )
        val masks =
            GemmaTransportSafeTokens.build(
                tokenize = { text ->
                    canonicalTokens[text]?.let { intArrayOf(it) }
                        ?: text.removePrefix(" token").toIntOrNull()?.let { intArrayOf(it) }
                        ?: intArrayOf(30, 31)
                },
                detokenize = { tokenIds ->
                    canonicalTokens.entries.singleOrNull { it.value == tokenIds.singleOrNull() }?.key
                        ?: " token${tokenIds.single()}"
                },
            )

        (0..3).forEach { assertFalse(masks.safeTokens[it].toInt() != 0) }
        canonicalTokens.values.forEach { assertFalse(masks.safeTokens[it].toInt() != 0) }
        assertTrue(masks.safeTokens[100].toInt() != 0)
        assertTrue(masks.safeTokens.last().toInt() != 0)
        assertTrue(masks.postFrameTokens.all { it.toInt() != 0 })
    }
}
