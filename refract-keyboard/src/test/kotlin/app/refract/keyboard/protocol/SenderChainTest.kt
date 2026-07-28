package app.refract.keyboard.protocol

import org.junit.Assert.assertFalse
import org.junit.Test

class SenderChainTest {
    @Test
    fun `chain commits bind sender sequence and carrier`() {
        val initial = SenderChain.initialHash("friends", "alice")
        val first = SenderChain.advance("friends", "alice", 0, initial, "cover one")

        assertFalse(
            first.contentEquals(
                SenderChain.advance("friends", "alice", 1, initial, "cover one")
            )
        )
        assertFalse(
            first.contentEquals(
                SenderChain.advance("friends", "bob", 0, initial, "cover one")
            )
        )
        assertFalse(
            first.contentEquals(
                SenderChain.advance("friends", "alice", 0, initial, "cover two")
            )
        )
    }
}
