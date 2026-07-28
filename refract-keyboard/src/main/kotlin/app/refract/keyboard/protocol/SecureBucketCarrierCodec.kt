package app.refract.keyboard.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Compact encrypted frame for a token-bucket carrier.
 *
 * AES-SIV already supplies a 128-bit authentication tag, so this format does not add the
 * experimental frame's second HMAC. The one-byte header declares version and sealed length and is
 * itself authenticated as associated data.
 */
class SecureBucketCarrierCodec(
    key: ByteArray,
    bitsPerToken: Int,
    private val conversation: String,
    private val direction: String,
    private val sequence: Long,
    previousHash: ByteArray,
) : AutoCloseable {
    private val rootKey =
        key.copyOf().also {
            require(it.size >= 16) { "Carrier key must contain at least 16 bytes." }
        }
    private val previousHash =
        previousHash.copyOf().also {
            require(it.size == SenderChain.HASH_SIZE_BYTES) {
                "Previous sender-chain hash must contain ${SenderChain.HASH_SIZE_BYTES} bytes."
            }
        }
    val bucketKey: ByteArray = deriveBucketKey(rootKey)
    val buckets = KeyedTokenBuckets(bucketKey, bitsPerToken)

    init {
        require(conversation.isNotBlank()) { "Conversation identifier is required." }
        require(direction.isNotBlank()) { "Carrier direction is required." }
        require(sequence >= 0) { "Sequence must not be negative." }
    }

    fun requiredBuckets(plaintext: ByteArray): IntArray =
        buckets.bytesToBuckets(encodeFrame(plaintext))

    fun decodeTokenIds(tokenIds: IntArray): ByteArray {
        val observedBuckets =
            IntArray(tokenIds.size) { position ->
                buckets.bucket(position.toLong(), tokenIds[position])
            }
        val headerBucketCount = bucketsForBytes(HEADER_SIZE)
        require(observedBuckets.size >= headerBucketCount) {
            "Carrier is too short to contain a secure frame header."
        }
        val header =
            buckets.bucketsToBytes(
                observedBuckets.copyOfRange(0, headerBucketCount),
                HEADER_SIZE,
            )
        val sealedLength = sealedLength(header[0])
        val frameLength = HEADER_SIZE + sealedLength
        val frameBucketCount = bucketsForBytes(frameLength)
        require(observedBuckets.size >= frameBucketCount) {
            "Carrier ended before its declared secure frame was complete."
        }
        val frame =
            buckets.bucketsToBytes(
                observedBuckets.copyOfRange(0, frameBucketCount),
                frameLength,
            )
        return AesSiv.open(
            key = rootKey,
            associatedData = associatedData(frame[0]),
            sealed = frame.copyOfRange(HEADER_SIZE, frame.size),
        )
    }

    fun chooseToken(
        position: Int,
        desiredBucket: Int,
        candidatesInPreferenceOrder: Iterable<Int>,
    ): Int? =
        buckets.firstCandidateInBucket(
            position = position.toLong(),
            desiredBucket = desiredBucket,
            candidatesInPreferenceOrder = candidatesInPreferenceOrder,
        )

    private fun encodeFrame(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Private message exceeds the $MAX_PLAINTEXT_BYTES-byte secure-frame limit."
        }
        val sealedLength = AesSiv.TAG_SIZE + plaintext.size
        val header = ((VERSION shl LENGTH_BITS) or sealedLength).toByte()
        return byteArrayOf(header) +
            AesSiv.seal(
                key = rootKey,
                associatedData = associatedData(header),
                plaintext = plaintext,
            )
    }

    private fun sealedLength(header: Byte): Int {
        val value = header.toInt() and 0xff
        val version = value ushr LENGTH_BITS
        require(version == VERSION) { "Unsupported secure frame version $version." }
        val length = value and LENGTH_MASK
        require(length >= AesSiv.TAG_SIZE) { "Secure frame is shorter than its SIV tag." }
        return length
    }

    private fun associatedData(header: Byte): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(AAD_DOMAIN)
            output.write(header.toInt())
            output.write(conversation.toByteArray(Charsets.UTF_8))
            output.write(0)
            output.write(direction.toByteArray(Charsets.UTF_8))
            output.write(0)
            output.write(
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(sequence)
                    .array()
            )
            output.write(previousHash)
            output.toByteArray()
        }

    private fun deriveBucketKey(key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(BUCKET_KEY_LABEL).copyOfRange(0, BUCKET_KEY_SIZE)
    }

    private fun bucketsForBytes(byteCount: Int): Int =
        (byteCount * Byte.SIZE_BITS + buckets.bitsPerToken - 1) / buckets.bitsPerToken

    override fun close() {
        rootKey.fill(0)
        previousHash.fill(0)
        bucketKey.fill(0)
        buckets.clear()
    }

    companion object {
        private const val VERSION = 3
        private const val LENGTH_BITS = 6
        private const val LENGTH_MASK = (1 shl LENGTH_BITS) - 1
        private const val HEADER_SIZE = 1
        // Stable wire-protocol domain. This is intentionally independent of product branding.
        private val AAD_DOMAIN =
            "conversation-stego/secure-frame/v1\u0000".toByteArray(Charsets.UTF_8)
        private val BUCKET_KEY_LABEL =
            "decalgo-token-bucket-key-v1".toByteArray(Charsets.UTF_8)
        private const val BUCKET_KEY_SIZE = 16
        private const val HMAC_SHA256 = "HmacSHA256"

        const val MAX_PLAINTEXT_BYTES = LENGTH_MASK - AesSiv.TAG_SIZE
    }
}
