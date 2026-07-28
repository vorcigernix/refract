package app.refract.keyboard.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object SenderChain {
    const val HASH_SIZE_BYTES = 32

    fun initialHash(
        conversation: String,
        sender: String,
    ): ByteArray {
        require(conversation.isNotBlank()) { "Conversation identifier is required." }
        require(sender.isNotBlank()) { "Sender identifier is required." }
        return digest(
            domain = INITIAL_DOMAIN,
            conversation = conversation,
            sender = sender,
            sequence = 0,
            previousHash = null,
            carrier = null,
        )
    }

    fun advance(
        conversation: String,
        sender: String,
        sequence: Long,
        previousHash: ByteArray,
        carrier: String,
    ): ByteArray {
        require(sequence >= 0) { "Sequence must not be negative." }
        require(previousHash.size == HASH_SIZE_BYTES) {
            "Previous sender-chain hash must contain $HASH_SIZE_BYTES bytes."
        }
        require(carrier.isNotEmpty()) { "Carrier is required." }
        return digest(
            domain = COMMIT_DOMAIN,
            conversation = conversation,
            sender = sender,
            sequence = sequence,
            previousHash = previousHash,
            carrier = carrier,
        )
    }

    private fun digest(
        domain: ByteArray,
        conversation: String,
        sender: String,
        sequence: Long,
        previousHash: ByteArray?,
        carrier: String?,
    ): ByteArray =
        MessageDigest.getInstance(SHA_256).digest(
            ByteArrayOutputStream().use { output ->
                output.write(domain)
                output.write(conversation.toByteArray(Charsets.UTF_8))
                output.write(0)
                output.write(sender.toByteArray(Charsets.UTF_8))
                output.write(0)
                output.write(
                    ByteBuffer.allocate(Long.SIZE_BYTES)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putLong(sequence)
                        .array()
                )
                previousHash?.let(output::write)
                carrier?.let { output.write(it.toByteArray(Charsets.UTF_8)) }
                output.toByteArray()
            }
        )

    private val INITIAL_DOMAIN =
        "decalgo-sender-chain-seed-v1\u0000".toByteArray(Charsets.UTF_8)
    private val COMMIT_DOMAIN =
        "decalgo-sender-chain-commit-v1\u0000".toByteArray(Charsets.UTF_8)
    private const val SHA_256 = "SHA-256"
}
