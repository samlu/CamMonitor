package com.a0soft.gphone.camonitor.log

import android.content.Context
import android.util.Log
import com.a0soft.gphone.camonitor.data.model.LogEntry
import com.a0soft.gphone.camonitor.data.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2MB
    private const val MAX_BACKUP_FILES = 3
    private const val MAX_MEMORY_LOGS = 500

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<LogEntry>(Channel.UNLIMITED)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var logDir: File? = null
    private var activeLogFile: File? = null

    fun init(context: Context) {
        try {
            context.getExternalFilesDir("logs")?.deleteRecursively()
        } catch (_: Exception) {}

        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDir = dir
        activeLogFile = File(dir, "cammonitor.log")

        // Start asynchronous disk writer
        logScope.launch {
            for (entry in logChannel) {
                writeEntryToDisk(entry)
            }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        log(LogLevel.ERROR, tag, msg)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        // Update in-memory flow for Compose UI atomically
        _logsFlow.update { current ->
            if (current.size >= MAX_MEMORY_LOGS) {
                current.drop(1) + entry
            } else {
                current + entry
            }
        }

        // Queue for disk persistence
        logChannel.trySend(entry)
    }

    private fun writeEntryToDisk(entry: LogEntry) {
        val file = activeLogFile ?: return
        try {
            if (file.exists() && file.length() >= MAX_FILE_SIZE_BYTES) {
                rotateLogFiles()
            }
            PrintWriter(FileWriter(file, true)).use { writer ->
                writer.println(entry.format())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing log to disk", e)
        }
    }

    private fun rotateLogFiles() {
        val dir = logDir ?: return
        try {
            // Delete oldest if it exists
            val oldest = File(dir, "cammonitor.$MAX_BACKUP_FILES.log")
            if (oldest.exists()) oldest.delete()

            // Shift backups
            for (i in (MAX_BACKUP_FILES - 1) downTo 1) {
                val current = File(dir, "cammonitor.$i.log")
                if (current.exists()) {
                    val next = File(dir, "cammonitor.${i + 1}.log")
                    current.renameTo(next)
                }
            }

            // Rename active to .1
            val active = activeLogFile
            if (active != null && active.exists()) {
                val backup1 = File(dir, "cammonitor.1.log")
                active.renameTo(backup1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log files", e)
        }
    }

    fun getLogFile(): File? = activeLogFile

    fun clearLogs() {
        logScope.launch {
            try {
                activeLogFile?.delete()
                val dir = logDir
                if (dir != null && dir.exists()) {
                    dir.listFiles()?.forEach { if (it.name.startsWith("cammonitor")) it.delete() }
                }
                _logsFlow.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }
}
