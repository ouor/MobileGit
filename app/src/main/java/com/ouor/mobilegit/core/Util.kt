package com.ouor.mobilegit.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val CoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Atomic JSON persistence: write tmp + fsync + rename, so a crash leaves either the old or the new file. */
object JsonFile {
    fun <T> read(file: File, serializer: KSerializer<T>): T? =
        if (!file.isFile) null else runCatching { CoreJson.decodeFromString(serializer, file.readText()) }.getOrNull()

    fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(CoreJson.encodeToString(serializer, value).toByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Encrypts secrets at rest. Android: Keystore AES-GCM ([com.ouor.mobilegit.app.KeystoreCipher]). */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(cipherText: String): String
}

fun interface Clock { fun now(): Long }
val SystemClock = Clock { System.currentTimeMillis() }

object Redact {
    private val urlCreds = Regex("""(https?://)[^/@\s]+@""")
    private val authHeader = Regex("""(?i)(authorization:\s*)(basic|bearer|token)\s+\S+""")

    /** Removes credentials embedded in URLs/headers plus any known secret values (F-07 AC5). */
    fun text(s: String?, secrets: Collection<String> = emptyList()): String? {
        if (s == null) return null
        var out = urlCreds.replace(s, "$1")
        out = authHeader.replace(out, "$1$2 ***")
        for (secret in secrets) if (secret.length >= 4) out = out.replace(secret, "***")
        return out
    }
}

fun File.dirSize(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
