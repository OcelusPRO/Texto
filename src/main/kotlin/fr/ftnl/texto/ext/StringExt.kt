package fr.ftnl.texto.ext

import java.security.MessageDigest

fun String.Companion.keyGen(length: Int): String {
    val keySpace = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val sb = StringBuilder()
    for (i in 0 until length) sb.append(keySpace.random())
    return sb.toString()
}

@OptIn(ExperimentalStdlibApi::class)
fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(this.toByteArray())
    return digest.toHexString()
}

fun String.checkHash(hash: String) = hash == this.md5()