package app.refract.keyboard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AesSivTest {
    @Test
    fun `cmac matches the standard empty-message vector`() {
        assertEquals(
            "bb1d6929e95937287fa37d129b756746",
            AesSiv.cmac(
                hex("2b7e151628aed2a6abf7158809cf4f3c"),
                byteArrayOf(),
            ).hex(),
        )
    }

    @Test
    fun `s2v matches the RFC 5297 vector used by the Go core`() {
        val result =
            AesSiv.s2v(
                macKey = hex("fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0"),
                associatedData =
                    hex("101112131415161718191a1b1c1d1e1f2021222324252627"),
                plaintext = hex("112233445566778899aabbccddee"),
            )

        assertEquals("85632d07c6e8f37f950acd320a2ecc93", result.hex())
    }

    @Test
    fun `round trip is deterministic and authenticated`() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        val aad = "ordered conversation state".toByteArray()
        val plaintext = "exact private message".toByteArray()
        val sealed = AesSiv.seal(key, aad, plaintext)

        assertArrayEquals(sealed, AesSiv.seal(key, aad, plaintext))
        assertArrayEquals(plaintext, AesSiv.open(key, aad, sealed))

        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) {
            AesSiv.open(key, aad, sealed)
        }
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
