package app.refract.keyboard.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline two-QR pairing built from X25519 and HKDF-SHA256.
 *
 * QR payloads contain public key material only. The conversation key is derived independently on
 * both devices and authenticated by comparing the safety words in person.
 */
object PairingProtocol {
    const val KEY_SIZE_BYTES = 32
    const val INVITATION_LIFETIME_SECONDS = 15 * 60L
}

class PairingInvite private constructor(
    val conversationId: String,
    val inviterSender: String,
    val inviteeSender: String,
    val inviterName: String,
    val expiresAtEpochSeconds: Long,
    private val inviterPublicKey: ByteArray,
    private val encodedBody: ByteArray,
) {
    fun encode(): String = INVITE_PREFIX + ENCODER.encodeToString(encodedBody)

    internal fun publicKeyCopy(): ByteArray = inviterPublicKey.copyOf()

    internal fun bodyCopy(): ByteArray = encodedBody.copyOf()

    companion object {
        internal fun create(
            inviterName: String,
            expiresAtEpochSeconds: Long,
            conversationId: ByteArray,
            inviterSender: ByteArray,
            inviteeSender: ByteArray,
            inviterPublicKey: ByteArray,
        ): PairingInvite {
            val cleanName = validateName(inviterName)
            require(conversationId.size == CONVERSATION_ID_SIZE)
            require(inviterSender.size == SENDER_ID_SIZE)
            require(inviteeSender.size == SENDER_ID_SIZE)
            require(inviterPublicKey.size == X25519_KEY_SIZE)
            val nameBytes = cleanName.toByteArray(Charsets.UTF_8)
            val body =
                ByteBuffer.allocate(
                        1 +
                            Long.SIZE_BYTES +
                            CONVERSATION_ID_SIZE +
                            SENDER_ID_SIZE * 2 +
                            X25519_KEY_SIZE +
                            1 +
                            nameBytes.size
                    )
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(FORMAT_VERSION)
                    .putLong(expiresAtEpochSeconds)
                    .put(conversationId)
                    .put(inviterSender)
                    .put(inviteeSender)
                    .put(inviterPublicKey)
                    .put(nameBytes.size.toByte())
                    .put(nameBytes)
                    .array()
            return PairingInvite(
                conversationId = encodeIdentifier(conversationId),
                inviterSender = encodeIdentifier(inviterSender),
                inviteeSender = encodeIdentifier(inviteeSender),
                inviterName = cleanName,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                inviterPublicKey = inviterPublicKey.copyOf(),
                encodedBody = body,
            )
        }

        fun decode(
            encoded: String,
            nowEpochSeconds: Long = Instant.now().epochSecond,
        ): PairingInvite {
            val body = decodePayload(encoded, INVITE_PREFIX, "invitation")
            require(body.size >= MINIMUM_INVITE_BODY_SIZE) {
                "The pairing invitation is truncated."
            }
            val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            require(buffer.get() == FORMAT_VERSION) {
                "This pairing invitation uses an unsupported version."
            }
            val expiresAt = buffer.long
            validateExpiry(expiresAt, nowEpochSeconds)
            val conversation = ByteArray(CONVERSATION_ID_SIZE).also(buffer::get)
            val inviter = ByteArray(SENDER_ID_SIZE).also(buffer::get)
            val invitee = ByteArray(SENDER_ID_SIZE).also(buffer::get)
            val publicKey = ByteArray(X25519_KEY_SIZE).also(buffer::get)
            val name = readName(buffer, trailingBytes = 0, label = "inviter")
            return PairingInvite(
                conversationId = encodeIdentifier(conversation),
                inviterSender = encodeIdentifier(inviter),
                inviteeSender = encodeIdentifier(invitee),
                inviterName = name,
                expiresAtEpochSeconds = expiresAt,
                inviterPublicKey = publicKey,
                encodedBody = body,
            )
        }
    }
}

class PairingInitiatorSession private constructor(
    val invitation: PairingInvite,
    privateKey: PrivateKey,
) : AutoCloseable {
    private var privateKey: PrivateKey? = privateKey

    fun complete(
        encodedResponse: String,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): PairingEstablishedSession {
        val response = PairingResponse.decode(encodedResponse, nowEpochSeconds)
        require(response.expiresAtEpochSeconds == invitation.expiresAtEpochSeconds) {
            "The pairing response does not match this invitation."
        }
        require(response.conversationId == invitation.conversationId) {
            "The pairing response belongs to a different conversation."
        }
        val inviteBody = invitation.bodyCopy()
        val expectedInviteHash = sha256(INVITE_HASH_DOMAIN, inviteBody)
        try {
            require(MessageDigest.isEqual(expectedInviteHash, response.inviteHash)) {
                "The pairing response does not match the invitation shown on this phone."
            }
            val responseCore = response.coreBodyCopy()
            val sharedSecret =
                computeX25519SharedSecret(
                    checkNotNull(privateKey) { "This pairing session is closed." },
                    response.responderPublicKeyCopy(),
                )
            val derived =
                derivePairingMaterial(
                    sharedSecret = sharedSecret,
                    inviteBody = inviteBody,
                    responseCore = responseCore,
                )
            try {
                val expectedConfirmation =
                    hmacSha256(
                            key = derived.rootKey,
                            RESPONSE_CONFIRMATION_DOMAIN,
                            derived.transcriptHash,
                        )
                        .copyOf(CONFIRMATION_TAG_SIZE)
                try {
                    if (!MessageDigest.isEqual(expectedConfirmation, response.confirmationTag)) {
                        throw SecurityException(
                            "The pairing response could not prove possession of its private key."
                        )
                    }
                } finally {
                    expectedConfirmation.fill(0)
                }
                return PairingEstablishedSession(
                    conversationId = invitation.conversationId,
                    localSender = invitation.inviterSender,
                    peerSender = invitation.inviteeSender,
                    peerName = response.responderName,
                    rootKey = derived.rootKey,
                    transcriptHash = derived.transcriptHash,
                )
            } catch (error: Throwable) {
                derived.close()
                throw error
            }
        } finally {
            inviteBody.fill(0)
            expectedInviteHash.fill(0)
        }
    }

    override fun close() {
        privateKey = null
    }

    companion object {
        fun start(
            inviterName: String,
            nowEpochSeconds: Long = Instant.now().epochSecond,
            random: SecureRandom = SecureRandom(),
        ): PairingInitiatorSession {
            val keyPair = generateX25519KeyPair(random)
            return PairingInitiatorSession(
                invitation =
                    PairingInvite.create(
                        inviterName = inviterName,
                        expiresAtEpochSeconds =
                            nowEpochSeconds + PairingProtocol.INVITATION_LIFETIME_SECONDS,
                        conversationId = randomBytes(CONVERSATION_ID_SIZE, random),
                        inviterSender = randomBytes(SENDER_ID_SIZE, random),
                        inviteeSender = randomBytes(SENDER_ID_SIZE, random),
                        inviterPublicKey = keyPair.publicKey,
                    ),
                privateKey = keyPair.privateKey,
            )
        }
    }
}

class PairingResponderSession private constructor(
    val invitation: PairingInvite,
    val responseCode: String,
    val pairing: PairingEstablishedSession,
    privateKey: PrivateKey,
) : AutoCloseable {
    private var privateKey: PrivateKey? = privateKey

    override fun close() {
        privateKey = null
        pairing.close()
    }

    companion object {
        fun respond(
            encodedInvitation: String,
            responderName: String,
            nowEpochSeconds: Long = Instant.now().epochSecond,
            random: SecureRandom = SecureRandom(),
        ): PairingResponderSession {
            val invite = PairingInvite.decode(encodedInvitation, nowEpochSeconds)
            val cleanName = validateName(responderName)
            val keyPair = generateX25519KeyPair(random)
            val inviteBody = invite.bodyCopy()
            val inviteHash = sha256(INVITE_HASH_DOMAIN, inviteBody)
            val responseCore =
                encodeResponseCore(
                    expiresAtEpochSeconds = invite.expiresAtEpochSeconds,
                    conversationId = decodeIdentifier(invite.conversationId, CONVERSATION_ID_SIZE),
                    inviteHash = inviteHash,
                    responderPublicKey = keyPair.publicKey,
                    responderName = cleanName,
                )
            val sharedSecret =
                computeX25519SharedSecret(keyPair.privateKey, invite.publicKeyCopy())
            val derived =
                derivePairingMaterial(
                    sharedSecret = sharedSecret,
                    inviteBody = inviteBody,
                    responseCore = responseCore,
                )
            return try {
                val confirmation =
                    hmacSha256(
                            key = derived.rootKey,
                            RESPONSE_CONFIRMATION_DOMAIN,
                            derived.transcriptHash,
                        )
                        .copyOf(CONFIRMATION_TAG_SIZE)
                val response =
                    PairingResponse.create(
                        coreBody = responseCore,
                        confirmationTag = confirmation,
                    )
                confirmation.fill(0)
                val pairing =
                    PairingEstablishedSession(
                        conversationId = invite.conversationId,
                        localSender = invite.inviteeSender,
                        peerSender = invite.inviterSender,
                        peerName = invite.inviterName,
                        rootKey = derived.rootKey,
                        transcriptHash = derived.transcriptHash,
                    )
                PairingResponderSession(
                    invitation = invite,
                    responseCode = response.encode(),
                    pairing = pairing,
                    privateKey = keyPair.privateKey,
                )
            } catch (error: Throwable) {
                derived.close()
                throw error
            } finally {
                inviteBody.fill(0)
                inviteHash.fill(0)
                responseCore.fill(0)
            }
        }
    }
}

class PairingEstablishedSession internal constructor(
    val conversationId: String,
    val localSender: String,
    val peerSender: String,
    val peerName: String,
    rootKey: ByteArray,
    transcriptHash: ByteArray,
) : AutoCloseable {
    private val rootKey = rootKey
    private val transcriptHash = transcriptHash

    val safetyPhrase: String
        get() {
            val digest = hmacSha256(rootKey, SAFETY_DOMAIN, transcriptHash)
            return try {
                (0 until SAFETY_WORD_COUNT).joinToString(separator = " ") { index ->
                    SAFETY_WORDS[digest[index].toInt() and SAFETY_WORD_MASK]
                }
            } finally {
                digest.fill(0)
            }
        }

    fun copyConversationKey(): ByteArray = rootKey.copyOf()

    override fun close() {
        rootKey.fill(0)
        transcriptHash.fill(0)
    }
}

private class PairingResponse private constructor(
    val conversationId: String,
    val responderName: String,
    val expiresAtEpochSeconds: Long,
    val inviteHash: ByteArray,
    private val responderPublicKey: ByteArray,
    private val coreBody: ByteArray,
    val confirmationTag: ByteArray,
) {
    fun encode(): String =
        RESPONSE_PREFIX + ENCODER.encodeToString(coreBody + confirmationTag)

    fun responderPublicKeyCopy(): ByteArray = responderPublicKey.copyOf()

    fun coreBodyCopy(): ByteArray = coreBody.copyOf()

    companion object {
        fun create(
            coreBody: ByteArray,
            confirmationTag: ByteArray,
        ): PairingResponse {
            require(confirmationTag.size == CONFIRMATION_TAG_SIZE)
            return decodeBody(coreBody + confirmationTag, nowEpochSeconds = null)
        }

        fun decode(
            encoded: String,
            nowEpochSeconds: Long,
        ): PairingResponse =
            decodeBody(
                decodePayload(encoded, RESPONSE_PREFIX, "response"),
                nowEpochSeconds,
            )

        private fun decodeBody(
            body: ByteArray,
            nowEpochSeconds: Long?,
        ): PairingResponse {
            require(body.size >= MINIMUM_RESPONSE_BODY_SIZE) {
                "The pairing response is truncated."
            }
            val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            require(buffer.get() == FORMAT_VERSION) {
                "This pairing response uses an unsupported version."
            }
            val expiresAt = buffer.long
            if (nowEpochSeconds != null) {
                validateExpiry(expiresAt, nowEpochSeconds)
            }
            val conversation = ByteArray(CONVERSATION_ID_SIZE).also(buffer::get)
            val inviteHash = ByteArray(HASH_SIZE).also(buffer::get)
            val publicKey = ByteArray(X25519_KEY_SIZE).also(buffer::get)
            val name = readName(buffer, CONFIRMATION_TAG_SIZE, "responder")
            val coreSize = body.size - CONFIRMATION_TAG_SIZE
            return PairingResponse(
                conversationId = encodeIdentifier(conversation),
                responderName = name,
                expiresAtEpochSeconds = expiresAt,
                inviteHash = inviteHash,
                responderPublicKey = publicKey,
                coreBody = body.copyOfRange(0, coreSize),
                confirmationTag = body.copyOfRange(coreSize, body.size),
            )
        }
    }
}

private class DerivedPairingMaterial(
    val rootKey: ByteArray,
    val transcriptHash: ByteArray,
) : AutoCloseable {
    override fun close() {
        rootKey.fill(0)
        transcriptHash.fill(0)
    }
}

private fun derivePairingMaterial(
    sharedSecret: ByteArray,
    inviteBody: ByteArray,
    responseCore: ByteArray,
): DerivedPairingMaterial {
    require(sharedSecret.size == X25519_KEY_SIZE)
    require(sharedSecret.any { it.toInt() != 0 }) {
        "The peer supplied an invalid X25519 public key."
    }
    val transcriptHash = sha256(TRANSCRIPT_DOMAIN, inviteBody, responseCore)
    val salt = sha256(KDF_SALT_DOMAIN, transcriptHash)
    return try {
        DerivedPairingMaterial(
            rootKey =
                hkdfSha256(
                    inputKeyMaterial = sharedSecret,
                    salt = salt,
                    info = KDF_INFO_DOMAIN + transcriptHash,
                    outputSize = PairingProtocol.KEY_SIZE_BYTES,
                ),
            transcriptHash = transcriptHash,
        )
    } catch (error: Throwable) {
        transcriptHash.fill(0)
        throw error
    } finally {
        sharedSecret.fill(0)
        salt.fill(0)
    }
}

private fun encodeResponseCore(
    expiresAtEpochSeconds: Long,
    conversationId: ByteArray,
    inviteHash: ByteArray,
    responderPublicKey: ByteArray,
    responderName: String,
): ByteArray {
    val nameBytes = validateName(responderName).toByteArray(Charsets.UTF_8)
    require(conversationId.size == CONVERSATION_ID_SIZE)
    require(inviteHash.size == HASH_SIZE)
    require(responderPublicKey.size == X25519_KEY_SIZE)
    return ByteBuffer.allocate(
            1 +
                Long.SIZE_BYTES +
                CONVERSATION_ID_SIZE +
                HASH_SIZE +
                X25519_KEY_SIZE +
                1 +
                nameBytes.size
        )
        .order(ByteOrder.BIG_ENDIAN)
        .put(FORMAT_VERSION)
        .putLong(expiresAtEpochSeconds)
        .put(conversationId)
        .put(inviteHash)
        .put(responderPublicKey)
        .put(nameBytes.size.toByte())
        .put(nameBytes)
        .array()
}

private fun hkdfSha256(
    inputKeyMaterial: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    outputSize: Int,
): ByteArray {
    require(outputSize in 1..(255 * HASH_SIZE))
    val pseudorandomKey = hmacSha256(salt, inputKeyMaterial)
    return try {
        val output = ByteArrayOutputStream(outputSize)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < outputSize) {
            val next =
                hmacSha256(
                    pseudorandomKey,
                    previous,
                    info,
                    byteArrayOf(counter.toByte()),
                )
            previous.fill(0)
            previous = next
            output.write(next, 0, minOf(next.size, outputSize - output.size()))
            counter++
        }
        previous.fill(0)
        output.toByteArray()
    } finally {
        pseudorandomKey.fill(0)
    }
}

private fun hmacSha256(
    key: ByteArray,
    vararg inputs: ByteArray,
): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(key, HMAC_SHA256))
    inputs.forEach(mac::update)
    return mac.doFinal()
}

private fun sha256(vararg inputs: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance(SHA_256)
    inputs.forEach(digest::update)
    return digest.digest()
}

private fun decodePayload(
    encoded: String,
    prefix: String,
    label: String,
): ByteArray {
    require(encoded.startsWith(prefix)) {
        "This is not a Refract Keyboard pairing $label."
    }
    require(encoded.length <= MAX_ENCODED_PAYLOAD_LENGTH) {
        "The pairing $label is too large."
    }
    return runCatching {
            DECODER.decode(encoded.removePrefix(prefix))
        }
        .getOrElse {
            throw IllegalArgumentException("The pairing $label is damaged.", it)
        }
}

private fun validateExpiry(
    expiresAtEpochSeconds: Long,
    nowEpochSeconds: Long,
) {
    require(expiresAtEpochSeconds >= nowEpochSeconds) {
        "This pairing code has expired. Start again."
    }
    require(expiresAtEpochSeconds <= nowEpochSeconds + MAX_ACCEPTED_FUTURE_SECONDS) {
        "This pairing code has an invalid expiry time."
    }
}

private fun validateName(value: String): String {
    val clean = value.trim()
    val bytes = clean.toByteArray(Charsets.UTF_8)
    require(bytes.isNotEmpty()) { "Your display name is required." }
    require(bytes.size <= MAX_NAME_BYTES) {
        "Display name must fit within $MAX_NAME_BYTES UTF-8 bytes."
    }
    require(clean.none { it == '\u0000' || it == '\r' || it == '\n' }) {
        "Display name cannot contain line breaks."
    }
    return clean
}

private fun readName(
    buffer: ByteBuffer,
    trailingBytes: Int,
    label: String,
): String {
    require(buffer.remaining() >= 1 + trailingBytes) {
        "The pairing payload is missing the $label name."
    }
    val size = buffer.get().toInt() and 0xff
    require(size in 1..MAX_NAME_BYTES && buffer.remaining() == size + trailingBytes) {
        "The pairing payload contains an invalid $label name."
    }
    val bytes = ByteArray(size).also(buffer::get)
    val decoded = bytes.toString(Charsets.UTF_8)
    require(decoded.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
        "The pairing payload contains invalid text."
    }
    return validateName(decoded)
}

private fun randomBytes(
    size: Int,
    random: SecureRandom,
): ByteArray = ByteArray(size).also(random::nextBytes)

private data class EphemeralX25519KeyPair(
    val privateKey: PrivateKey,
    val publicKey: ByteArray,
)

private fun generateX25519KeyPair(random: SecureRandom): EphemeralX25519KeyPair {
    val generator = KeyPairGenerator.getInstance(XDH)
    generator.initialize(NamedParameterSpec.X25519, random)
    val keyPair = generator.generateKeyPair()
    val publicKey =
        keyPair.public as? XECPublicKey
            ?: error("The Android XDH provider did not return an X25519 public key.")
    return EphemeralX25519KeyPair(
        privateKey = keyPair.private,
        publicKey = encodeX25519PublicKey(publicKey),
    )
}

private fun computeX25519SharedSecret(
    privateKey: PrivateKey,
    peerPublicKey: ByteArray,
): ByteArray {
    require(peerPublicKey.size == X25519_KEY_SIZE) {
        "The peer supplied an invalid X25519 public key."
    }
    val publicKey =
        KeyFactory.getInstance(XDH)
            .generatePublic(
                XECPublicKeySpec(
                    NamedParameterSpec.X25519,
                    BigInteger(1, peerPublicKey.reversedArray()),
                )
            )
    val agreement = KeyAgreement.getInstance(XDH)
    agreement.init(privateKey)
    agreement.doPhase(publicKey, true)
    return agreement.generateSecret().also { sharedSecret ->
        require(sharedSecret.size == X25519_KEY_SIZE && sharedSecret.any { it.toInt() != 0 }) {
            "The peer supplied an invalid X25519 public key."
        }
    }
}

private fun encodeX25519PublicKey(publicKey: XECPublicKey): ByteArray {
    val unsignedBigEndian =
        publicKey.u.toByteArray().let { encoded ->
            if (encoded.size > 1 && encoded[0].toInt() == 0) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
        }
    require(unsignedBigEndian.size <= X25519_KEY_SIZE) {
        "The Android XDH provider returned an invalid X25519 public key."
    }
    return ByteArray(X25519_KEY_SIZE).also { littleEndian ->
        unsignedBigEndian.forEachIndexed { index, byte ->
            littleEndian[unsignedBigEndian.lastIndex - index] = byte
        }
    }
}

private fun encodeIdentifier(value: ByteArray): String =
    ENCODER.encodeToString(value)

private fun decodeIdentifier(
    value: String,
    expectedSize: Int,
): ByteArray =
    DECODER.decode(value).also {
        require(it.size == expectedSize) { "Pairing identifier is invalid." }
    }

private const val INVITE_PREFIX = "RK-I2:"
private const val RESPONSE_PREFIX = "RK-R2:"
private const val FORMAT_VERSION: Byte = 2
private const val CONVERSATION_ID_SIZE = 16
private const val SENDER_ID_SIZE = 12
private const val X25519_KEY_SIZE = 32
private const val HASH_SIZE = 32
private const val CONFIRMATION_TAG_SIZE = 16
private const val MAX_NAME_BYTES = 48
private const val MAX_ENCODED_PAYLOAD_LENGTH = 512
private const val MAX_ACCEPTED_FUTURE_SECONDS = 30 * 60L
private const val MINIMUM_INVITE_BODY_SIZE =
    1 + Long.SIZE_BYTES + CONVERSATION_ID_SIZE + SENDER_ID_SIZE * 2 + X25519_KEY_SIZE + 1 + 1
private const val MINIMUM_RESPONSE_BODY_SIZE =
    1 +
        Long.SIZE_BYTES +
        CONVERSATION_ID_SIZE +
        HASH_SIZE +
        X25519_KEY_SIZE +
        1 +
        1 +
        CONFIRMATION_TAG_SIZE
private const val SHA_256 = "SHA-256"
private const val HMAC_SHA256 = "HmacSHA256"
private const val XDH = "XDH"
private const val SAFETY_WORD_COUNT = 5
private const val SAFETY_WORD_MASK = 0x3f
private val ENCODER = Base64.getUrlEncoder().withoutPadding()
private val DECODER = Base64.getUrlDecoder()
private val INVITE_HASH_DOMAIN =
    "refract-pairing-invite-hash-v2\u0000".toByteArray(Charsets.UTF_8)
private val TRANSCRIPT_DOMAIN =
    "refract-pairing-transcript-v2\u0000".toByteArray(Charsets.UTF_8)
private val KDF_SALT_DOMAIN =
    "refract-pairing-hkdf-salt-v2\u0000".toByteArray(Charsets.UTF_8)
private val KDF_INFO_DOMAIN =
    "refract-conversation-root-v2\u0000".toByteArray(Charsets.UTF_8)
private val RESPONSE_CONFIRMATION_DOMAIN =
    "refract-pairing-response-confirm-v2\u0000".toByteArray(Charsets.UTF_8)
private val SAFETY_DOMAIN =
    "refract-pairing-safety-v2\u0000".toByteArray(Charsets.UTF_8)
private val SAFETY_WORDS =
    arrayOf(
        "amber",
        "apple",
        "arch",
        "beach",
        "birch",
        "blue",
        "breeze",
        "brook",
        "cedar",
        "cherry",
        "cloud",
        "coral",
        "dawn",
        "dove",
        "dune",
        "elm",
        "fern",
        "field",
        "finch",
        "flame",
        "forest",
        "frost",
        "gold",
        "green",
        "harbor",
        "hazel",
        "hill",
        "iris",
        "jade",
        "lake",
        "leaf",
        "lemon",
        "lilac",
        "maple",
        "meadow",
        "mint",
        "moon",
        "moss",
        "night",
        "ocean",
        "olive",
        "orchid",
        "peach",
        "pearl",
        "pine",
        "plum",
        "pond",
        "rain",
        "reed",
        "river",
        "rose",
        "sage",
        "sand",
        "sky",
        "snow",
        "solar",
        "spring",
        "stone",
        "sun",
        "teal",
        "vale",
        "violet",
        "willow",
        "wind",
    )
