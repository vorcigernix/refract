package app.refract.keyboard.protocol

/**
 * Assigns each `(token position, token ID)` pair to a keyed pseudorandom bucket.
 *
 * During generation, a logits processor will retain only safe tokens in the required bucket. The
 * receiver tokenizes the final carrier and performs this same lookup; it never runs the language
 * model.
 */
class KeyedTokenBuckets(key: ByteArray, val bitsPerToken: Int) {
    private val bucketKey: ByteArray
    val bucketCount: Int

    init {
        require(key.size >= BUCKET_KEY_SIZE) { "Bucket key must be at least 16 bytes." }
        require(bitsPerToken in 1..2) { "This prototype supports one or two bits per token." }
        bucketKey = key.copyOfRange(0, BUCKET_KEY_SIZE)
        bucketCount = 1 shl bitsPerToken
    }

    fun bucket(position: Long, tokenId: Int): Int {
        require(position >= 0) { "Token position must not be negative." }
        require(tokenId >= 0) { "Token ID must not be negative." }

        val tokenAndDomain =
            (tokenId.toLong() and 0xffff_ffffL) or (BUCKET_DOMAIN.toLong() shl Int.SIZE_BITS)
        return SipHash24
            .hashTwoWords(
                key = bucketKey,
                firstWord = position,
                secondWord = tokenAndDomain,
            )
            .toInt() and (bucketCount - 1)
    }

    fun bytesToBuckets(bytes: ByteArray): IntArray {
        val bucketSize = bitsPerToken
        val bucketTotal = (bytes.size * Byte.SIZE_BITS + bucketSize - 1) / bucketSize
        val result = IntArray(bucketTotal)
        var bitOffset = 0

        for (bucketIndex in result.indices) {
            var value = 0
            repeat(bucketSize) {
                value = value shl 1
                if (bitOffset < bytes.size * Byte.SIZE_BITS) {
                    val sourceByte = bytes[bitOffset / Byte.SIZE_BITS].toInt() and 0xff
                    val sourceBit = 7 - (bitOffset % Byte.SIZE_BITS)
                    value = value or ((sourceByte ushr sourceBit) and 1)
                }
                bitOffset++
            }
            result[bucketIndex] = value
        }
        return result
    }

    fun bucketsToBytes(buckets: IntArray, byteCount: Int): ByteArray {
        require(byteCount >= 0) { "Byte count must not be negative." }
        require(buckets.size * bitsPerToken >= byteCount * Byte.SIZE_BITS) {
            "Not enough buckets for $byteCount bytes."
        }
        val result = ByteArray(byteCount)
        var bitOffset = 0

        buckets.forEach { bucket ->
            require(bucket in 0 until bucketCount) { "Bucket $bucket is out of range." }
            for (sourceBit in bitsPerToken - 1 downTo 0) {
                if (bitOffset >= byteCount * Byte.SIZE_BITS) return result
                val bit = (bucket ushr sourceBit) and 1
                val destinationByte = bitOffset / Byte.SIZE_BITS
                val destinationBit = 7 - (bitOffset % Byte.SIZE_BITS)
                result[destinationByte] =
                    (result[destinationByte].toInt() or (bit shl destinationBit)).toByte()
                bitOffset++
            }
        }
        return result
    }

    fun firstCandidateInBucket(
        position: Long,
        desiredBucket: Int,
        candidatesInPreferenceOrder: Iterable<Int>,
    ): Int? {
        require(desiredBucket in 0 until bucketCount) {
            "Bucket $desiredBucket is out of range."
        }
        return candidatesInPreferenceOrder.firstOrNull {
            bucket(position = position, tokenId = it) == desiredBucket
        }
    }

    internal fun clear() {
        bucketKey.fill(0)
    }

    companion object {
        private const val BUCKET_KEY_SIZE = 16
        private const val BUCKET_DOMAIN = 0x31534243 // "CSB1" in little-endian bytes.
    }
}
