package com.transfer.flash.server

internal object ServerVersion {
    val value: String = ServerVersion::class.java.getResourceAsStream("/flash-server-version.txt")
        ?.bufferedReader()?.use { it.readText().trim() }
        ?: "unknown"
}
