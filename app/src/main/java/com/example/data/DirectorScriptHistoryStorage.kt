package com.example.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single persisted entry of a successfully applied `.fcscript.json` script.
 */
data class DirectorScriptHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val appliedAtMs: Long,
    val sceneCount: Int,
    val totalDurationSec: Float = 0f,
    val clipCount: Int = 0,
    val cameraShotCount: Int = 0,
    val importMode: String = "REPLACE_TIMELINE",
    val scriptJson: String
)

data class DirectorScriptHistoryDocument(
    val schemaVersion: Int = 1,
    val entries: List<DirectorScriptHistoryEntry> = emptyList()
)

/**
 * 100% offline local JSON persistence for the last [MAX_HISTORY_ENTRIES] successfully applied
 * `.fcscript.json` director scripts (`filesDir/director_script_history.json`).
 *
 * Uses atomic temp-file replacement so history survives app restarts without corruption and
 * requires zero Room DB or cloud storage.
 */
class DirectorScriptHistoryStorage(
    private val storageFile: File
) {
    constructor(context: Context) : this(
        storageFile = File(context.filesDir, HISTORY_FILENAME)
    )

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val docAdapter = moshi.adapter(DirectorScriptHistoryDocument::class.java).indent("  ")

    suspend fun loadHistory(): List<DirectorScriptHistoryEntry> = withContext(Dispatchers.IO) {
        loadHistoryBlocking()
    }

    fun loadHistoryBlocking(): List<DirectorScriptHistoryEntry> {
        return runCatching {
            if (!storageFile.exists() || storageFile.length() == 0L) {
                emptyList()
            } else {
                val raw = storageFile.readText(Charsets.UTF_8)
                deserializeFromJson(raw)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Prepends a newly applied script entry, deduplicates identical script JSON payloads
     * (bumping the newest timestamp to the top), caps the list strictly to [MAX_HISTORY_ENTRIES],
     * and atomically persists `director_script_history.json`.
     */
    suspend fun recordAppliedScript(
        title: String,
        scriptJson: String,
        sceneCount: Int,
        totalDurationSec: Float = 0f,
        clipCount: Int = 0,
        cameraShotCount: Int = 0,
        importMode: String = "REPLACE_TIMELINE",
        appliedAtMs: Long = System.currentTimeMillis()
    ): List<DirectorScriptHistoryEntry> = withContext(Dispatchers.IO) {
        recordAppliedScriptBlocking(
            title = title,
            scriptJson = scriptJson,
            sceneCount = sceneCount,
            totalDurationSec = totalDurationSec,
            clipCount = clipCount,
            cameraShotCount = cameraShotCount,
            importMode = importMode,
            appliedAtMs = appliedAtMs
        )
    }

    fun recordAppliedScriptBlocking(
        title: String,
        scriptJson: String,
        sceneCount: Int,
        totalDurationSec: Float = 0f,
        clipCount: Int = 0,
        cameraShotCount: Int = 0,
        importMode: String = "REPLACE_TIMELINE",
        appliedAtMs: Long = System.currentTimeMillis()
    ): List<DirectorScriptHistoryEntry> {
        val cleanJson = scriptJson.trim()
        if (cleanJson.isEmpty()) return loadHistoryBlocking()
        val cleanTitle = title.trim().ifBlank { "Untitled Script" }.take(64)
        val entry = DirectorScriptHistoryEntry(
            id = "dsh_${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            appliedAtMs = appliedAtMs,
            sceneCount = sceneCount.coerceAtLeast(0),
            totalDurationSec = totalDurationSec.coerceAtLeast(0f),
            clipCount = clipCount.coerceAtLeast(0),
            cameraShotCount = cameraShotCount.coerceAtLeast(0),
            importMode = importMode,
            scriptJson = cleanJson
        )
        val existing = loadHistoryBlocking().filterNot { it.scriptJson.trim() == cleanJson }
        val updated = (listOf(entry) + existing).take(MAX_HISTORY_ENTRIES)
        writeEntriesBlocking(updated)
        return updated
    }

    suspend fun deleteEntry(entryId: String): List<DirectorScriptHistoryEntry> = withContext(Dispatchers.IO) {
        deleteEntryBlocking(entryId)
    }

    fun deleteEntryBlocking(entryId: String): List<DirectorScriptHistoryEntry> {
        val updated = loadHistoryBlocking().filterNot { it.id == entryId }
        writeEntriesBlocking(updated)
        return updated
    }

    suspend fun clearAll(): List<DirectorScriptHistoryEntry> = withContext(Dispatchers.IO) {
        clearAllBlocking()
    }

    fun clearAllBlocking(): List<DirectorScriptHistoryEntry> {
        writeEntriesBlocking(emptyList())
        return emptyList()
    }

    fun serializeToJson(entries: List<DirectorScriptHistoryEntry>): String =
        docAdapter.toJson(DirectorScriptHistoryDocument(entries = entries.take(MAX_HISTORY_ENTRIES)))

    fun deserializeFromJson(json: String): List<DirectorScriptHistoryEntry> {
        val parsed = runCatching { docAdapter.fromJson(json) }.getOrNull() ?: return emptyList()
        return parsed.entries
            .sortedByDescending { it.appliedAtMs }
            .take(MAX_HISTORY_ENTRIES)
    }

    private fun writeEntriesBlocking(entries: List<DirectorScriptHistoryEntry>) {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val tempFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tempFile.writeText(serializeToJson(entries), Charsets.UTF_8)
            if (storageFile.exists() && !tempFile.renameTo(storageFile)) {
                storageFile.writeText(tempFile.readText(Charsets.UTF_8), Charsets.UTF_8)
                tempFile.delete()
            } else if (!storageFile.exists()) {
                tempFile.renameTo(storageFile)
            }
        }
    }

    companion object {
        const val HISTORY_FILENAME = "director_script_history.json"
        const val MAX_HISTORY_ENTRIES = 10
    }
}
