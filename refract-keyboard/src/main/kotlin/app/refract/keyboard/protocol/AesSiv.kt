package app.refract.keyboard.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-SIV compatible with the repository's Go implementation in siv.go.
 *
 * It derives independent AES-256 CMAC and CTR keys, authenticates associated data with S2V, and
 * adds a single 16-byte tag without carrying a random nonce.
 */
internal object AesSiv {
    const val TAG_SIZE = 16

    fun seal(
        key: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val macKey = deriveKey(key, MAC_KEY_LABEL)
        val encryptionKey = deriveKey(key, ENCRYPTION_KEY_LABEL)
        val tag = s2v(macKey, associatedData, plaintext)
        val iv = ctrIv(tag)
        val cipher = Cipher.getInstance(AES_CTR)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, AES),
            IvParameterSpec(iv),
        )
        return tag + cipher.doFinal(plaintext)
    }

    fun open(
        key: ByteArray,
        associatedData: ByteArray,
        sealed: ByteArray,
    ): ByteArray {
        require(sealed.size >= TAG_SIZE) { "Invalid SIV ciphertext." }
        val tag = sealed.copyOfRange(0, TAG_SIZE)
        val ciphertext = sealed.copyOfRange(TAG_SIZE, sealed.size)
        val encryptionKey = deriveKey(key, ENCRYPTION_KEY_LABEL)
        val cipher = Cipher.getInstance(AES_CTR)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(encryptionKey, AES),
            IvParameterSpec(ctrIv(tag)),
        )
        val plaintext = cipher.doFinal(ciphertext)
        val expectedTag = s2v(deriveKey(key, MAC_KEY_LABEL), associatedData, plaintext)
        if (!MessageDigest.isEqual(tag, expectedTag)) {
            plaintext.fill(0)
            throw SecurityException("SIV authentication failed.")
        }
        return plaintext
    }

    internal fun s2v(
        macKey: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        var d = cmac(macKey, ByteArray(TAG_SIZE))
        d = xorBlock(doubleBlock(d), cmac(macKey, associatedData))
        if (plaintext.size >= TAG_SIZE) {
            val input = plaintext.copyOf()
            val start = input.size - TAG_SIZE
            for (index in 0 until TAG_SIZE) {
                input[start + index] = (input[start + index].toInt() xor d[index].toInt()).toByte()
            }
            return cmac(macKey, input)
        }

        val padded = ByteArray(TAG_SIZE)
        plaintext.copyInto(padded)
        padded[plaintext.size] = 0x80.toByte()
        return cmac(macKey, xorBlock(doubleBlock(d), padded))
    }

    internal fun cmac(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_ECB)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES))
        val l = cipher.doFinal(ByteArray(TAG_SIZE))
        val k1 = doubleBlock(l)
        val k2 = doubleBlock(k1)
        val complete = message.isNotEmpty() && message.size % TAG_SIZE == 0
        val blocks = maxOf(1, (message.size + TAG_SIZE - 1) / TAG_SIZE)
        val last = ByteArray(TAG_SIZE)
        if (complete) {
            message.copyInto(last, startIndex = (blocks - 1) * TAG_SIZE)
            xorBlockInPlace(last, k1)
        } else {
            val remaining = message.size - (blocks - 1) * TAG_SIZE
            if (remaining > 0) {
                message.copyInto(
                    destination = last,
                    startIndex = (blocks - 1) * TAG_SIZE,
                )
            }
            last[remaining] = 0x80.toByte()
            xorBlockInPlace(last, k2)
        }

        var x = ByteArray(TAG_SIZE)
        repeat(blocks - 1) { blockIndex ->
            val block =
                message.copyOfRange(
                    blockIndex * TAG_SIZE,
                    (blockIndex + 1) * TAG_SIZE,
                )
            x = cipher.doFinal(xorBlock(x, block))
        }
        return cipher.doFinal(xorBlock(x, last))
    }

    private fun deriveKey(
        key: ByteArray,
        label: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    private fun ctrIv(tag: ByteArray): ByteArray =
        tag.copyOf().apply {
            this[8] = (this[8].toInt() and 0x7f).toByte()
            this[12] = (this[12].toInt() and 0x7f).toByte()
        }

    private fun doubleBlock(input: ByteArray): ByteArray {
        val output = ByteArray(TAG_SIZE)
        var carry = 0
        for (index in TAG_SIZE - 1 downTo 0) {
            val value = input[index].toInt() and 0xff
            val nextCarry = value ushr 7
            output[index] = ((value shl 1) or carry).toByte()
            carry = nextCarry
        }
        if (carry != 0) {
            output[TAG_SIZE - 1] =
                (output[TAG_SIZE - 1].toInt() xor 0x87).toByte()
        }
        return output
    }

    private fun xorBlock(
        first: ByteArray,
        second: ByteArray,
    ): ByteArray =
        ByteArray(TAG_SIZE) { index ->
            (first[index].toInt() xor second[index].toInt()).toByte()
        }

    private fun xorBlockInPlace(
        target: ByteArray,
        other: ByteArray,
    ) {
        for (index in target.indices) {
            target[index] = (target[index].toInt() xor other[index].toInt()).toByte()
        }
    }

    private const val AES = "AES"
    private const val AES_ECB = "AES/ECB/NoPadding"
    private const val AES_CTR = "AES/CTR/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val MAC_KEY_LABEL = "decalgo-aes-siv-mac-v1"
    private const val ENCRYPTION_KEY_LABEL = "decalgo-aes-siv-enc-v1"
}
