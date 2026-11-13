package com.amandhakar.apkforge.core

import java.security.MessageDigest

object Hex {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}
