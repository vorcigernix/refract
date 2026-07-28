package app.refract.keyboard.protocol

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun `two QR handshake derives the same key and safety words`() {
        PairingInitiatorSession.start(
                inviterName = "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(1),
            )
            .use { alice ->
                PairingResponderSession.respond(
                        encodedInvitation = alice.invitation.encode(),
                        responderName = "Bob",
                        nowEpochSeconds = 1_001,
                        random = deterministicRandom(2),
                    )
                    .use { bob ->
                        alice.complete(bob.responseCode, nowEpochSeconds = 1_002).use { completed ->
                            assertEquals("Bob", completed.peerName)
                            assertEquals("Alice", bob.pairing.peerName)
                            assertEquals(completed.conversationId, bob.pairing.conversationId)
                            assertEquals(completed.localSender, bob.pairing.peerSender)
                            assertEquals(completed.peerSender, bob.pairing.localSender)
                            assertArrayEquals(
                                completed.copyConversationKey(),
                                bob.pairing.copyConversationKey(),
                            )
                            assertEquals(completed.safetyPhrase, bob.pairing.safetyPhrase)
                            assertEquals(5, completed.safetyPhrase.split(" ").size)
                        }
                    }
            }
    }

    @Test
    fun `neither QR contains the derived conversation key`() {
        PairingInitiatorSession.start(
                inviterName = "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(3),
            )
            .use { alice ->
                PairingResponderSession.respond(
                        encodedInvitation = alice.invitation.encode(),
                        responderName = "Bob",
                        nowEpochSeconds = 1_001,
                        random = deterministicRandom(4),
                    )
                    .use { bob ->
                        val key = bob.pairing.copyConversationKey()
                        try {
                            assertFalse(decodeQrBody(alice.invitation.encode()).containsBytes(key))
                            assertFalse(decodeQrBody(bob.responseCode).containsBytes(key))
                        } finally {
                            key.fill(0)
                        }
                    }
            }
    }

    @Test
    fun `tampered response fails key confirmation`() {
        PairingInitiatorSession.start(
                inviterName = "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(5),
            )
            .use { alice ->
                PairingResponderSession.respond(
                        encodedInvitation = alice.invitation.encode(),
                        responderName = "Bob",
                        nowEpochSeconds = 1_001,
                        random = deterministicRandom(6),
                    )
                    .use { bob ->
                        val body = decodeQrBody(bob.responseCode)
                        body[body.lastIndex] = (body.last().toInt() xor 1).toByte()
                        val tampered = "RK-R2:" + Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(body)

                        assertThrows(SecurityException::class.java) {
                            alice.complete(tampered, nowEpochSeconds = 1_002)
                        }
                    }
            }
    }

    @Test
    fun `response from another invitation is rejected`() {
        PairingInitiatorSession.start(
                "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(7),
            )
            .use { first ->
                PairingInitiatorSession.start(
                        "Alice",
                        nowEpochSeconds = 1_000,
                        random = deterministicRandom(8),
                    )
                    .use { second ->
                        PairingResponderSession.respond(
                                second.invitation.encode(),
                                "Bob",
                                nowEpochSeconds = 1_001,
                                random = deterministicRandom(9),
                            )
                            .use { responder ->
                                assertThrows(IllegalArgumentException::class.java) {
                                    first.complete(responder.responseCode, nowEpochSeconds = 1_002)
                                }
                            }
                    }
            }
    }

    @Test
    fun `expired invitation and response are rejected`() {
        PairingInitiatorSession.start(
                "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(10),
            )
            .use { alice ->
                assertThrows(IllegalArgumentException::class.java) {
                    PairingInvite.decode(
                        alice.invitation.encode(),
                        nowEpochSeconds =
                            1_000 + PairingProtocol.INVITATION_LIFETIME_SECONDS + 1,
                    )
                }
            }
    }

    @Test
    fun `independent handshakes produce independent roots`() {
        val first = completeHandshake(seed = 11)
        val second = completeHandshake(seed = 13)
        try {
            assertNotEquals(
                Base64.getEncoder().encodeToString(first),
                Base64.getEncoder().encodeToString(second),
            )
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    private fun completeHandshake(seed: Long): ByteArray =
        PairingInitiatorSession.start(
                "Alice",
                nowEpochSeconds = 1_000,
                random = deterministicRandom(seed),
            )
            .use { alice ->
                PairingResponderSession.respond(
                        alice.invitation.encode(),
                        "Bob",
                        nowEpochSeconds = 1_001,
                        random = deterministicRandom(seed + 1),
                    )
                    .use { bob ->
                        alice.complete(bob.responseCode, nowEpochSeconds = 1_002).use {
                            it.copyConversationKey()
                        }
                    }
            }

    private fun decodeQrBody(encoded: String): ByteArray =
        Base64.getUrlDecoder().decode(encoded.substringAfter(':'))

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private fun deterministicRandom(seed: Long): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed)
        }
}
