package fr.ftnl.texto.util

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Crypto {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun randomKey(length: Int): String = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun sha256(value: String): String = digest("SHA-256", value)

    // Argon2id, encodé au format PHC : $argon2id$v=19$m=...,t=...,p=...$sel$hash
    private const val MEMORY = 19_456
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private val b64e = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = argon2(password, salt, MEMORY, ITERATIONS, PARALLELISM, 32)
        return "\$argon2id\$v=19\$m=$MEMORY,t=$ITERATIONS,p=$PARALLELISM\$${b64e.encodeToString(salt)}\$${b64e.encodeToString(hash)}"
    }

    fun verifyPassword(password: String, encoded: String): Boolean = runCatching {
        val parts = encoded.split('$')
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = b64d.decode(parts[4])
        val expected = b64d.decode(parts[5])
        val actual = argon2(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        MessageDigest.isEqual(expected, actual)
    }.getOrDefault(false)

    private fun argon2(password: String, salt: ByteArray, memory: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memory).withIterations(iterations).withParallelism(parallelism)
                .withSalt(salt).build()
        )
        return ByteArray(length).also { gen.generateBytes(password.toByteArray(), it) }
    }

    private fun digest(algo: String, value: String): String =
        MessageDigest.getInstance(algo).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
