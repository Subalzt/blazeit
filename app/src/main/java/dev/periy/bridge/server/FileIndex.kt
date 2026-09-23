package dev.periy.bridge.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The list of files the browser can see and download.
 *
 * Kept separately from the filesystem rather than listing the destination folder on
 * every request, for two reasons: the folder is the user's own and may contain thousands
 * of unrelated files, and a SAF directory listing of a large folder is slow enough to be
 * felt. This index only ever holds what actually crossed the bridge.
 */
class FileIndex(ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(ctx.applicationContext.filesDir, "index.json")
    private val lock = Any()

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<FileEntry>> = _flow

    val entries: List<FileEntry> get() = _flow.value

    fun get(id: String): FileEntry? = _flow.value.firstOrNull { it.id == id }

    fun add(entry: FileEntry) = synchronized(lock) {
        _flow.value = (listOf(entry) + _flow.value.filterNot { it.id == entry.id }).take(MAX)
        persist()
        EventBus.emit("files", json.encodeToString(_flow.value))
    }

    fun remove(id: String): FileEntry? = synchronized(lock) {
        val gone = _flow.value.firstOrNull { it.id == id }
        if (gone != null) {
            _flow.value = _flow.value.filterNot { it.id == id }
            persist()
            EventBus.emit("files", json.encodeToString(_flow.value))
        }
        gone
    }

    /** Drops entries whose backing document has vanished (user deleted it in Files). */
    fun prune(exists: (FileEntry) -> Boolean) = synchronized(lock) {
        val kept = _flow.value.filter(exists)
        if (kept.size != _flow.value.size) {
            _flow.value = kept
            persist()
            EventBus.emit("files", json.encodeToString(kept))
        }
    }

    private fun persist() {
        runCatching {
            // Write-then-rename: a half-written index.json after a kill would otherwise
            // lose the whole list, and this file is cheap to write atomically.
            val tmp = File(file.parentFile, "index.json.tmp")
            tmp.writeText(json.encodeToString(_flow.value))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    private fun load(): List<FileEntry> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<FileEntry>>(file.readText())
    }.getOrDefault(emptyList())

    private companion object { const val MAX = 500 }
}
