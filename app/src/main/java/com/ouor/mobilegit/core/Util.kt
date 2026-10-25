package com.ouor.mobilegit.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * ARC-02: unknown keys are ignored and an unknown enum value (or a null) in a field that has a default falls back to
 * that default, so adding/removing fields or enum values does not make a whole file unreadable.
 * Rule for model changes: never rename an enum constant or a field; if a rename is unavoidable, keep the old name with
 * `@SerialName` and bump [JsonFile.SCHEMA_VERSION] with a migration in [JsonFile.read].
 */
val CoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

/** ARC-01: a state file exists but could not be decoded. The original is left in place and copied to [preservedAs]. */
class CorruptFileException(val file: File, val preservedAs: File?, cause: Throwable) :
    IOException("${file.name}: ${cause.message ?: cause.javaClass.simpleName}", cause)

/** A state file that was unreadable at startup (shown by the UI as a warning; the copy is kept for recovery). */
data class StoreIssue(val file: String, val preservedAs: String?, val message: String) {
    companion object { fun of(e: CorruptFileException) = StoreIssue(e.file.absolutePath, e.preservedAs?.absolutePath, e.message ?: "") }
}

/**
 * Atomic JSON persistence: write a unique tmp + fsync + atomic rename + directory fsync, so a crash leaves either the
 * old or the new file. Every file carries `schemaVersion` (objects get the key, lists are wrapped as
 * `{"schemaVersion":1,"items":[...]}`; bare arrays written by older builds are still read).
 */
object JsonFile {
    const val SCHEMA_VERSION = 1
    private const val VERSION_KEY = "schemaVersion"
    private const val ITEMS_KEY = "items"

    /**
     * null = the file does not exist (first run). Throws [CorruptFileException] when it exists but cannot be decoded;
     * the bad file is copied to `<name>.corrupt-<epochMillis>` first and never deleted or rewritten here.
     */
    fun <T> read(file: File, serializer: KSerializer<T>): T? {
        val root = parse(file) ?: return null
        return try { CoreJson.decodeFromJsonElement(serializer, unwrap(root)) } catch (e: Exception) { throw corrupt(file, e) }
    }

    /** List files (history, logs, tokens): entries that cannot be decoded are skipped after preserving the original. */
    fun <T> readList(file: File, element: KSerializer<T>): List<T>? {
        val root = parse(file) ?: return null
        val arr = unwrap(root) as? JsonArray ?: throw corrupt(file, IOException("not a list"))
        val out = arr.mapNotNull { runCatching { CoreJson.decodeFromJsonElement(element, it) }.getOrNull() }
        if (out.size < arr.size) preserve(file, "partial")
        return out
    }

    fun <T> writeList(file: File, element: KSerializer<T>, value: List<T>) = write(file, ListSerializer(element), value)

    fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val body = when (val el = CoreJson.encodeToJsonElement(serializer, value)) {
            is JsonObject -> JsonObject(el + (VERSION_KEY to JsonPrimitive(SCHEMA_VERSION)))
            is JsonArray -> JsonObject(mapOf(VERSION_KEY to JsonPrimitive(SCHEMA_VERSION), ITEMS_KEY to el))
            else -> el
        }
        // ARC-15: unique tmp name, so two writers of one file never share a tmp file
        val tmp = File(dir, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(CoreJson.encodeToString(JsonElement.serializer(), body).toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { tmp.delete() }
        syncDir(dir)
    }

    /** Makes a rename durable. Some file systems refuse to open directories; then only the latest change can be lost. */
    fun syncDir(dir: File) { runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } } }

    /** Leftover tmp files of interrupted writes (ARC-04). */
    fun isTmp(f: File) = f.name.startsWith(".") && f.name.endsWith(".tmp") || f.name.endsWith(".json.tmp")

    private fun parse(file: File): JsonElement? {
        if (!file.isFile) return null
        val root = try { CoreJson.parseToJsonElement(file.readText()) } catch (e: Exception) { throw corrupt(file, e) }
        // ARC-02: written by a newer app version (downgrade). Keep a copy before this version rewrites it.
        val v = (root as? JsonObject)?.get(VERSION_KEY)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        if (v != null && v > SCHEMA_VERSION) preserve(file, "v$v")
        return root
    }

    private fun unwrap(root: JsonElement): JsonElement =
        if (root is JsonObject && VERSION_KEY in root && ITEMS_KEY in root) root.getValue(ITEMS_KEY) else root

    private fun corrupt(file: File, e: Throwable) = CorruptFileException(file, preserve(file, "corrupt"), e)

    /** Copies [file] to `<name>.<tag>-<millis>` unless an identical copy already exists. */
    private fun preserve(file: File, tag: String): File? = runCatching {
        val bytes = file.readBytes()
        val dir = file.absoluteFile.parentFile
        dir.listFiles { f -> f.name.startsWith("${file.name}.$tag-") }?.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
            ?.let { return@runCatching it }
        File(dir, "${file.name}.$tag-${System.currentTimeMillis()}").also { it.writeBytes(bytes) }
    }.getOrNull()
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
