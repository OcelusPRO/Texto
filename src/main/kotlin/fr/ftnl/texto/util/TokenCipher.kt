package fr.ftnl.texto.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffre (AES-256-GCM) les jetons OAuth stockés en base. La clé est dérivée des secrets de session :
 * si ceux-ci changent, les jetons deviennent illisibles et sont simplement ignorés (l'utilisateur se reconnecte).
 */
class TokenCipher(secret: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("texto-oauth-tokens|$secret").toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Null si la valeur est illisible (clé changée, donnée corrompue). */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(cipher.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}
