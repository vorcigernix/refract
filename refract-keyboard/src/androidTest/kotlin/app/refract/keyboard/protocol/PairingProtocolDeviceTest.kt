package app.refract.keyboard.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingProtocolDeviceTest {
    @Test
    fun x25519HandshakeWorksWithAndroidCryptoProvider() {
        PairingInitiatorSession.start("First device").use { initiator ->
            PairingResponderSession.respond(
                    encodedInvitation = initiator.invitation.encode(),
                    responderName = "Second device",
                )
                .use { responder ->
                    initiator.complete(responder.responseCode).use { completed ->
                        assertArrayEquals(
                            completed.copyConversationKey(),
                            responder.pairing.copyConversationKey(),
                        )
                        assertEquals(completed.safetyPhrase, responder.pairing.safetyPhrase)
                    }
                }
        }
    }
}
