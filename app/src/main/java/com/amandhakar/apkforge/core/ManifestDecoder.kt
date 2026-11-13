package com.amandhakar.apkforge.core

import java.io.File

sealed interface ManifestResult {
    data class Ok(val xml: String, val rootAttributes: Map<String, String> = emptyMap(), val applicationAttributes: Map<String, String> = emptyMap()) : ManifestResult
    data class Failed(val reason: String) : ManifestResult
}

/** Turns an APK's binary AndroidManifest.xml into readable XML. Reads only; never runs anything. */
interface ManifestDecoder {
    fun decode(apk: File): ManifestResult
}
