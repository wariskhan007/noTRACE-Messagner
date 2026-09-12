package com.notrace.messenger.data.media

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Attachments are encrypted independently of the Double Ratchet session,
 * the same way Signal's own attachment pipeline works: a fresh random key
 * per attachment (never reused, never derived from the identity or
 * session keys), AES-256-CBC for confidentiality + HMAC-SHA256 for
 * integrity (encrypt-then-MAC). Only the resulting `mediaKey` (64 bytes:
 * 32-byte AES key || 32-byte HMAC key) travels through the Double Ratchet
 * channel — inside the small "attachment control" text message
 * (see MessageRepository) — never the plaintext file itself.
 *
 * Wire format of the encrypted blob: `IV(16) || ciphertext || HMAC(32)`.
 */
object MediaCrypto {

    private const val AES_KEY_LEN = 32
    private const val HMAC_KEY_LEN = 32
    private const val IV_LEN = 16
    private const val MAC_LEN = 32

    data class EncryptResult(val mediaKey: ByteArray, val encryptedBlob: ByteArray)

    fun generateMediaKey(): ByteArray {
        val key = ByteArray(AES_KEY_LEN + HMAC_KEY_LEN)
        SecureRandom().nextBytes(key)
        return key
    }

    fun encrypt(plaintext: ByteArray, mediaKey: ByteArray = generateMediaKey()): EncryptResult {
        require(mediaKey.size == AES_KEY_LEN + HMAC_KEY_LEN) { "media key must be 64 bytes" }
        val aesKey = mediaKey.copyOfRange(0, AES_KEY_LEN)
        val hmacKey = mediaKey.copyOfRange(AES_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN)

        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        val tag = mac.doFinal()

        return EncryptResult(mediaKey, iv + ciphertext + tag)
    }

    /** Throws if the HMAC doesn't match — always verify before touching the ciphertext bytes. */
    fun decrypt(encryptedBlob: ByteArray, mediaKey: ByteArray): ByteArray {
        require(mediaKey.size == AES_KEY_LEN + HMAC_KEY_LEN) { "media key must be 64 bytes" }
        require(encryptedBlob.size > IV_LEN + MAC_LEN) { "blob too short to be valid" }

        val aesKey = mediaKey.copyOfRange(0, AES_KEY_LEN)
        val hmacKey = mediaKey.copyOfRange(AES_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN)

        val iv = encryptedBlob.copyOfRange(0, IV_LEN)
        val ciphertext = encryptedBlob.copyOfRange(IV_LEN, encryptedBlob.size - MAC_LEN)
        val tag = encryptedBlob.copyOfRange(encryptedBlob.size - MAC_LEN, encryptedBlob.size)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        val expectedTag = mac.doFinal()
        if (!java.security.MessageDigest.isEqual(expectedTag, tag)) {
            throw SecurityException("Attachment HMAC mismatch — corrupt or tampered blob")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }
}
