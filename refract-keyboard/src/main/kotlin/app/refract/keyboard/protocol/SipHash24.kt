package app.refract.keyboard.protocol

/**
 * SipHash-2-4 for the fixed two-word token-bucket input.
 *
 * SipHash is used here as a small, fast keyed PRF that has matching portable C++ code in the
 * LiteRT-LM extension. The caller must pass a separately derived 16-byte bucket key.
 */
internal object SipHash24 {
    fun hashTwoWords(key: ByteArray, firstWord: Long, secondWord: Long): Long {
        require(key.size == 16) { "SipHash-2-4 requires a 16-byte key." }
        val k0 = littleEndianLong(key, 0)
        val k1 = littleEndianLong(key, 8)
        var v0 = 0x736f6d6570736575L xor k0
        var v1 = 0x646f72616e646f6dL xor k1
        var v2 = 0x6c7967656e657261L xor k0
        var v3 = 0x7465646279746573L xor k1

        fun round() {
            v0 += v1
            v1 = java.lang.Long.rotateLeft(v1, 13)
            v1 = v1 xor v0
            v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v3
            v3 = java.lang.Long.rotateLeft(v3, 16)
            v3 = v3 xor v2
            v0 += v3
            v3 = java.lang.Long.rotateLeft(v3, 21)
            v3 = v3 xor v0
            v2 += v1
            v1 = java.lang.Long.rotateLeft(v1, 17)
            v1 = v1 xor v2
            v2 = java.lang.Long.rotateLeft(v2, 32)
        }

        fun absorb(word: Long) {
            v3 = v3 xor word
            repeat(2) { round() }
            v0 = v0 xor word
        }

        absorb(firstWord)
        absorb(secondWord)
        absorb(16L shl 56)
        v2 = v2 xor 0xffL
        repeat(4) { round() }
        return v0 xor v1 xor v2 xor v3
    }

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }
}
