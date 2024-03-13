package fr.ftnl.texto.ext

import java.io.File
import java.security.MessageDigest


@OptIn(ExperimentalStdlibApi::class)
fun File.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(this.readBytes())
    return digest.toHexString()
}

fun File.checkHash(hash: String) = hash == this.md5()