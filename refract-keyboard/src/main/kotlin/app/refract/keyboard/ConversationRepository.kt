package app.refract.keyboard

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import app.refract.keyboard.protocol.PairingProtocol
import app.refract.keyboard.protocol.SenderChain

class ConversationRepository(context: Context) {
    data class Summary(
        val profileId: String,
        val alias: String,
        val conversationId: String,
        val localSender: String,
        val peerSender: String,
        val sendSequence: Long,
        val receiveSequence: Long,
    )

    class Session internal constructor(
        val profileId: String,
        val alias: String,
        val conversationId: String,
        val localSender: String,
        val peerSender: String,
        val sendSequence: Long,
        val sendPreviousHash: ByteArray,
        val key: ByteArray,
    ) : AutoCloseable {
        override fun close() {
            key.fill(0)
            sendPreviousHash.fill(0)
        }
    }

    private data class StoredConversation(
        val profileId: String,
        val alias: String,
        val conversationId: String,
        val localSender: String,
        val peerSender: String,
        val encryptedKey: String,
        var sendSequence: Long,
        var sendPreviousHash: String,
        var receiveSequence: Long,
        var receivePreviousHash: String,
    )

    private data class CleanMetadata(
        val alias: String,
        val conversationId: String,
        val localSender: String,
        val peerSender: String,
    )

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val vault = AndroidKeyVault()
    private val gson = Gson()

    fun list(): List<Summary> =
        synchronized(LOCK) {
            readStored().map { it.toSummary() }
        }

    fun activeSummary(): Summary? =
        synchronized(LOCK) {
            val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
            readStored().firstOrNull { it.profileId == activeId }?.toSummary()
        }

    fun hasActiveConversation(): Boolean = activeSummary() != null

    fun createFromPairing(
        alias: String,
        conversationId: String,
        localSender: String,
        peerSender: String,
        conversationKey: ByteArray,
    ): Summary {
        require(conversationKey.size == PairingProtocol.KEY_SIZE_BYTES) {
            "Pairing key must contain ${PairingProtocol.KEY_SIZE_BYTES} bytes."
        }
        return createWithKey(
            cleanMetadata(alias, conversationId, localSender, peerSender),
            conversationKey,
        )
    }

    private fun cleanMetadata(
        alias: String,
        conversationId: String,
        localSender: String,
        peerSender: String,
    ): CleanMetadata {
        val cleanAlias = alias.trim()
        val cleanConversation = conversationId.trim()
        val cleanLocalSender = localSender.trim()
        val cleanPeerSender = peerSender.trim()
        require(cleanAlias.isNotEmpty()) { "Local conversation name is required." }
        require(cleanConversation.isNotEmpty()) { "Shared conversation ID is required." }
        require(cleanLocalSender.isNotEmpty()) { "Your sender name is required." }
        require(cleanPeerSender.isNotEmpty()) { "Peer sender name is required." }
        require(cleanLocalSender != cleanPeerSender) {
            "Your sender name and peer sender name must differ."
        }
        require(
            listOf(cleanConversation, cleanLocalSender, cleanPeerSender)
                .none { it.contains(FORBIDDEN_PROTOCOL_CHARACTERS) }
        ) {
            "Conversation and sender names cannot contain line breaks or null characters."
        }
        require(
            listOf(cleanAlias, cleanConversation, cleanLocalSender, cleanPeerSender)
                .all { it.length <= MAX_METADATA_LENGTH }
        ) {
            "Conversation names must not exceed $MAX_METADATA_LENGTH characters."
        }
        return CleanMetadata(
            alias = cleanAlias,
            conversationId = cleanConversation,
            localSender = cleanLocalSender,
            peerSender = cleanPeerSender,
        )
    }

    private fun createWithKey(
        metadata: CleanMetadata,
        conversationKey: ByteArray,
    ): Summary {
        val profileId = UUID.randomUUID().toString()
        val key = conversationKey.copyOf()
        val encryptedKey =
            try {
                vault.encrypt(
                    plaintext = key,
                    associatedData =
                        keyAssociatedData(profileId, metadata.conversationId),
                )
            } finally {
                key.fill(0)
            }
        val stored =
            StoredConversation(
                profileId = profileId,
                alias = metadata.alias,
                conversationId = metadata.conversationId,
                localSender = metadata.localSender,
                peerSender = metadata.peerSender,
                encryptedKey = encryptedKey,
                sendSequence = 0,
                sendPreviousHash =
                    encodeHash(
                        SenderChain.initialHash(
                            metadata.conversationId,
                            metadata.localSender,
                        )
                    ),
                receiveSequence = 0,
                receivePreviousHash =
                    encodeHash(
                        SenderChain.initialHash(
                            metadata.conversationId,
                            metadata.peerSender,
                        )
                    ),
            )

        synchronized(LOCK) {
            val conversations = readStored().toMutableList()
            require(
                conversations.none {
                    it.conversationId == metadata.conversationId &&
                        it.localSender == metadata.localSender
                }
            ) {
                "This conversation and local sender are already paired."
            }
            conversations += stored
            require(
                preferences
                    .edit()
                    .putString(KEY_CONVERSATIONS, gson.toJson(conversations))
                    .putString(KEY_ACTIVE_PROFILE, profileId)
                    .commit()
            ) {
                "Could not persist the paired conversation."
            }
        }
        return stored.toSummary()
    }

    fun select(profileId: String): Boolean =
        synchronized(LOCK) {
            if (readStored().none { it.profileId == profileId }) return@synchronized false
            preferences.edit().putString(KEY_ACTIVE_PROFILE, profileId).commit()
        }

    fun remove(profileId: String): Boolean =
        synchronized(LOCK) {
            val conversations = readStored().toMutableList()
            val removed = conversations.removeAll { it.profileId == profileId }
            if (!removed) return@synchronized false
            val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
            val nextActive =
                if (activeId == profileId) conversations.firstOrNull()?.profileId else activeId
            preferences
                .edit()
                .putString(KEY_CONVERSATIONS, gson.toJson(conversations))
                .apply {
                    if (nextActive == null) {
                        remove(KEY_ACTIVE_PROFILE)
                    } else {
                        putString(KEY_ACTIVE_PROFILE, nextActive)
                    }
                }
                .commit()
        }

    fun openActiveSession(): Session? =
        synchronized(LOCK) {
            val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
            val stored = readStored().firstOrNull { it.profileId == activeId }
                ?: return@synchronized null
            val key =
                vault.decrypt(
                    encoded = stored.encryptedKey,
                    associatedData =
                        keyAssociatedData(stored.profileId, stored.conversationId),
                )
            Session(
                profileId = stored.profileId,
                alias = stored.alias,
                conversationId = stored.conversationId,
                localSender = stored.localSender,
                peerSender = stored.peerSender,
                sendSequence = stored.sendSequence,
                sendPreviousHash = decodeHash(stored.sendPreviousHash),
                key = key,
            )
        }

    fun commitSent(
        session: Session,
        carrier: String,
    ): Boolean =
        synchronized(LOCK) {
            val conversations = readStored().toMutableList()
            val index = conversations.indexOfFirst { it.profileId == session.profileId }
            if (index < 0) return@synchronized false
            val stored = conversations[index]
            val storedHash = decodeHash(stored.sendPreviousHash)
            if (
                stored.sendSequence != session.sendSequence ||
                    !storedHash.contentEquals(session.sendPreviousHash)
            ) {
                storedHash.fill(0)
                return@synchronized false
            }
            storedHash.fill(0)
            val nextHash =
                SenderChain.advance(
                    conversation = session.conversationId,
                    sender = session.localSender,
                    sequence = session.sendSequence,
                    previousHash = session.sendPreviousHash,
                    carrier = carrier,
                )
            stored.sendSequence++
            stored.sendPreviousHash = encodeHash(nextHash)
            nextHash.fill(0)
            preferences
                .edit()
                .putString(KEY_CONVERSATIONS, gson.toJson(conversations))
                .commit()
        }

    private fun readStored(): List<StoredConversation> {
        val json = preferences.getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return runCatching {
                gson.fromJson<List<StoredConversation>>(
                    json,
                    object : TypeToken<List<StoredConversation>>() {}.type,
                )
            }
            .getOrDefault(emptyList())
    }

    private fun StoredConversation.toSummary() =
        Summary(
            profileId = profileId,
            alias = alias,
            conversationId = conversationId,
            localSender = localSender,
            peerSender = peerSender,
            sendSequence = sendSequence,
            receiveSequence = receiveSequence,
        )

    private fun keyAssociatedData(
        profileId: String,
        conversationId: String,
    ): ByteArray =
        ("decalgo-android-key-wrap-v1\u0000$profileId\u0000$conversationId")
            .toByteArray(Charsets.UTF_8)

    private fun encodeHash(hash: ByteArray): String =
        Base64.encodeToString(hash, Base64.NO_WRAP)

    private fun decodeHash(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.NO_WRAP).also {
            require(it.size == SenderChain.HASH_SIZE_BYTES) {
                "Stored sender-chain hash is invalid."
            }
        }

    companion object {
        private val LOCK = Any()
        private const val FILE_NAME = "paired_conversations"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val MAX_METADATA_LENGTH = 80
        private val FORBIDDEN_PROTOCOL_CHARACTERS = Regex("""[\u0000\r\n]""")
    }
}
