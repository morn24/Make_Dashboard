package com.morn24.dashboard.data

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Test
    fun encryptsAndDecryptsUtf8TextWithoutStoringPlaintext() {
        val plainText = "연결 문자열 예시 🔐"
        val cipher = TokenCipher(FixedKeyProvider(key))

        val encrypted = cipher.encrypt(plainText)
        val serialized = "${encrypted.ivBase64}:${encrypted.ciphertextBase64}"

        assertEquals(plainText, cipher.decrypt(encrypted))
        assertFalse(serialized.contains(plainText))
        assertNotEquals(encrypted.ciphertextBase64, Base64.getEncoder().encodeToString(plainText.toByteArray()))
        assertEquals(12, Base64.getDecoder().decode(encrypted.ivBase64).size)
    }

    @Test
    fun eachEncryptionUsesANewIv() {
        val cipher = TokenCipher(FixedKeyProvider(key))
        val first = cipher.encrypt("same input")
        val second = cipher.encrypt("same input")
        assertNotEquals(first.ivBase64, second.ivBase64)
        assertNotEquals(first.ciphertextBase64, second.ciphertextBase64)
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val cipher = TokenCipher(FixedKeyProvider(key))
        val encrypted = cipher.encrypt("private sample")
        val bytes = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val modified = encrypted.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(bytes))

        assertTrue(runCatching { cipher.decrypt(modified) }.isFailure)
    }

    private class FixedKeyProvider(private val key: SecretKey) : AesKeyProvider {
        override fun getOrCreate(): SecretKey = key
    }
}
