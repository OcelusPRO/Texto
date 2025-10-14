package fr.ftnl.texto.ext

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
data class HashSalt(val hash: String, val salt: String)

fun String.secureHash(saltString: String? = null): HashSalt {
    val salt = if (saltString == null) {
        val random = SecureRandom()
        val newSalt = ByteArray(16)
        random.nextBytes(newSalt)
        newSalt
    } else saltString.hexToByteArray()

    val spec: KeySpec = PBEKeySpec(this.toCharArray(), salt, 65536, 128)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    
    val hash = factory.generateSecret(spec).encoded.toHexString()
    return HashSalt(hash, salt.toHexString())
}

fun String.hexToByteArray(): ByteArray {
    val len = this.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((this[i].digitToInt(16) shl 4) + this[i + 1].digitToInt(16)).toByte()
        i += 2
    }
    return data
}
