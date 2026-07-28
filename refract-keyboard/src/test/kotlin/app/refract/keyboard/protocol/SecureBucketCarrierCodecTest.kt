package app.refract.keyboard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureBucketCarrierCodecTest {
    private val key = ByteArray(32) { index -> (index * 17 + 11).toByte() }

    @Test
    fun `secure carrier round trips through synthetic token IDs`() {
        val codec = codec()
        val plaintext = "hello".toByteArray()
        val buckets = codec.requiredBuckets(plaintext)
        val tokens = synthesize(codec, buckets)

        assertArrayEquals(plaintext, codec.decodeTokenIds(tokens))
    }

    @Test
    fun `secure frame adds only header and SIV tag`() {
        val codec = codec()

        assertEquals(68, codec.requiredBuckets(byteArrayOf()).size)
        assertEquals(76, codec.requiredBuckets("OK".toByteArray()).size)
    }

    @Test
    fun `secure frame enforces its forty-seven-byte limit`() {
        codec().requiredBuckets(ByteArray(47))

        assertThrows(IllegalArgumentException::class.java) {
            codec().requiredBuckets(ByteArray(48))
        }
    }

    @Test
    fun `wrong sequence fails authentication`() {
        val sender = codec(sequence = 0)
        val receiver = codec(sequence = 1)
        val tokens = synthesize(sender, sender.requiredBuckets("secret".toByteArray()))

        assertThrows(SecurityException::class.java) {
            receiver.decodeTokenIds(tokens)
        }
    }

    @Test
    fun `wrong previous sender-chain hash fails authentication`() {
        val sender = codec(previousHash = ByteArray(32) { 1 })
        val receiver = codec(previousHash = ByteArray(32) { 2 })
        val tokens = synthesize(sender, sender.requiredBuckets("secret".toByteArray()))

        assertThrows(SecurityException::class.java) {
            receiver.decodeTokenIds(tokens)
        }
    }

    @Test
    fun `tampered bucket fails authentication`() {
        val codec = codec()
        val desired = codec.requiredBuckets("secret".toByteArray())
        val tokens = synthesize(codec, desired)
        val position = tokens.size / 2
        val current = codec.buckets.bucket(position.toLong(), tokens[position])
        tokens[position] =
            requireNotNull(
                codec.chooseToken(
                    position,
                    current xor 1,
                    0 until 100_000,
                )
            )

        assertThrows(SecurityException::class.java) {
            codec.decodeTokenIds(tokens)
        }
    }

    private fun codec(
        sequence: Long = 0,
        previousHash: ByteArray = SenderChain.initialHash("device-demo", "local-demo"),
    ) =
        SecureBucketCarrierCodec(
            key = key,
            bitsPerToken = 2,
            conversation = "device-demo",
            direction = "local-demo",
            sequence = sequence,
            previousHash = previousHash,
        )

    private fun synthesize(
        codec: SecureBucketCarrierCodec,
        desired: IntArray,
    ): IntArray =
        IntArray(desired.size) { position ->
            requireNotNull(
                codec.chooseToken(
                    position,
                    desired[position],
                    0 until 100_000,
                )
            )
        }
}
