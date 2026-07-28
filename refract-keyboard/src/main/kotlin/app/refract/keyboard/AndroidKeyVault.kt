package app.refract.keyboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeyVault {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val blob =
            byteArrayOf(FORMAT_VERSION, cipher.iv.size.toByte()) +
                cipher.iv +
                ciphertext
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(
        encoded: String,
        associatedData: ByteArray,
    ): ByteArray {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size >= HEADER_SIZE + MINIMUM_IV_SIZE + GCM_TAG_SIZE) {
            "Encrypted conversation key is truncated."
        }
        require(blob[0] == FORMAT_VERSION) {
            "Unsupported encrypted conversation key format."
        }
        val ivSize = blob[1].toInt() and 0xff
        require(ivSize >= MINIMUM_IV_SIZE && blob.size > HEADER_SIZE + ivSize) {
            "Encrypted conversation key has an invalid IV."
        }
        val iv = blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivSize)
        val ciphertext = blob.copyOfRange(HEADER_SIZE + ivSize, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
            }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "refract_keyboard_pairing_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HEADER_SIZE = 2
        private const val MINIMUM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 16
        private const val GCM_TAG_BITS = GCM_TAG_SIZE * 8
        private const val FORMAT_VERSION: Byte = 1
    }
}
