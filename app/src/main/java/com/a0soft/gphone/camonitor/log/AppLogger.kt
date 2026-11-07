package com.a0soft.gphone.camonitor.log

import android.content.Context
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * File-backed logger.
 *
 * Design notes (each one is a fix for a field failure, not a preference):
 *
 *  * **One writer, batched flush.** The previous version opened, wrote and closed
 *    the file for *every* line — three syscalls per log entry, ~92 times a minute
 *    on a streaming camera, on top of everything else that device did. Now one
 *    [BufferedWriter] stays open and is flushed on a batch/idle boundary.
 *  * **Bounded queue.** The channel was `UNLIMITED`; a slow or stuck filesystem
 *    would have grown it until the process died. Overflow is now counted and
 *    reported instead of silently eating memory.
 *  * **Session boundaries.** A crashed or killed process used to look exactly
 *    like a stalled one — the log simply stopped. Every start now writes a header
 *    and every clean stop writes an end marker; a missing marker on the next boot
 *    is reported as "previous session ended abruptly".
 *  * **Crash capture.** There was no `UncaughtExceptionHandler`, so a real crash
 *    left no stack trace anywhere but logcat (which is gone by the time the user
 *    reports it). The handler writes synchronously — the process is about to die,
 *    a queued write would never reach disk.
 *  * **Capacity that survives a night.** 2 MB × 4 files held roughly 11 hours at
 *    the observed rate, so a morning report always lost the previous evening.
 *    Now 8 MB × 6.
 */
object AppLogger {
    private const val TAG = "AppLogger"

    /** Rotate at 8 MB; ~7 MB/day at INFO, so one file is about a day. */
    private const val MAX_FILE_SIZE_BYTES = 8 * 1024 * 1024L

    /** Rotated siblings kept on disk: 6 files ≈ 48 MB total. */
    private const val MAX_BACKUP_FILES = 5

    private const val MAX_MEMORY_LOGS = 500

    /**
     * Hard ceiling on queued-but-unwritten entries.
     *
     * 4096 is far above any realistic burst (a reconnect storm is a few hundred
     * lines) and far below anything that threatens the heap.
     */
    private const val LOG_QUEUE_CAPACITY = 4096

    /** Flush when this many lines are buffered, even if more keep arriving. */
    private const val FLUSH_BATCH = 32

    /** Flush when the writer has been idle this long, so a quiet device still persists. */
    private const val FLUSH_IDLE_MS = 1_000L

    /** How often the in-memory tail is republished to the log viewer. */
    private const val UI_PUBLISH_INTERVAL_MS = 300L

    private const val SESSION_END_MARKER = "=== SESSION END ==="

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<LogEntry>(LOG_QUEUE_CAPACITY)

    /** Guards every access to [writer], [uiBuffer] and the active file. */
    private val writerMutex = Mutex()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val uiBuffer = ArrayDeque<LogEntry>(MAX_MEMORY_LOGS + 1)

    private var logDir: File? = null
    private var activeLogFile: File? = null
    private var writer: BufferedWriter? = null
    private var pendingLines = 0
    private var lastFlushAt = 0L
    private var lastUiPublishAt = 0L

    private val droppedEntries = AtomicLong(0)
    private val processStartElapsed = SystemClock.elapsedRealtime()

    /**
     * Lines below this level are neither logged to logcat nor persisted.
     *
     * Defaults to DEBUG so nothing that used to be captured gets lost; the log
     * viewer exposes a switch for users who want a quieter file on a hot device.
     */
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG
        set(value) {
            field = value
            logEntry(LogLevel.INFO, TAG, "Log level threshold set to ${value.name}", mandatory = true)
        }

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) {
            Log.w(TAG, "init() called twice — ignoring the second call")
            return
        }
        initialized = true
        try {
            // Leftover from an older build that wrote to the external dir.
            context.getExternalFilesDir("logs")?.deleteRecursively()
        } catch (_: Exception) {}

        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        activeLogFile = File(dir, "cammonitor.log")

        installCrashHandler()

        logScope.launch { writerLoop() }

        // Read the previous run's tail BEFORE appending the new header, so the
        // verdict lands right after the session banner instead of being buried.
        val previousEndedCleanly = previousSessionEndedCleanly()

        logEntry(
            LogLevel.INFO,
            TAG,
            "===== SESSION START pid=${android.os.Process.myPid()} " +
                "app=${versionName(context)} api=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} =====",
            mandatory = true
        )
        if (!previousEndedCleanly) {
            logEntry(
                LogLevel.WARN, TAG,
                "Previous session did not end normally (no shutdown marker): killed by the " +
                    "system, force-stopped, or crashed. Its last lines are immediately above.",
                mandatory = true
            )
        }
    }

    // ---------------------------------------------------------------- public API

    fun d(tag: String, message: String) = logEntry(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = logEntry(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = logEntry(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        logEntry(LogLevel.ERROR, tag, msg)
    }

    /** Files on disk, newest first (active file, then rotations). */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val active = activeLogFile
        val rotations = (1..MAX_BACKUP_FILES)
            .map { File(dir, "cammonitor.$it.log") }
            .filter { it.exists() }
        return (listOfNotNull(active?.takeIf { it.exists() }) + rotations)
    }

    /**
     * Appends a marker so the next boot can tell "stopped cleanly" from
     * "disappeared". Called from service/Activity teardown paths.
     */
    fun markCleanShutdown(reason: String) {
        logEntry(LogLevel.INFO, TAG, "$SESSION_END_MARKER ($reason)", mandatory = true)
    }

    fun clearLogs() {
        logScope.launch {
            writerMutex.withLock {
                try {
                    closeWriterLocked()
                    val dir = logDir
                    if (dir != null && dir.exists()) {
                        dir.listFiles()?.forEach { if (it.name.startsWith("cammonitor")) it.delete() }
                    }
                    uiBuffer.clear()
                    _logsFlow.value = emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear logs", e)
                }
            }
        }
    }

    fun droppedCount(): Long = droppedEntries.get()

    /**
     * Bundles the active file *and* every rotated sibling into one zip.
     *
     * The old share action only ever sent `cammonitor.log`, so a report about an
     * overnight failure arrived missing exactly the hours it was about — the ones
     * already rotated into `cammonitor.1..5.log`.
     *
     * The buffer is flushed first: the writer keeps lines in memory between
     * batches, and exporting without flushing silently omits the newest ones.
     */
    suspend fun exportBundle(): File? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val dir = logDir ?: return@withContext null
        writerMutex.withLock { flushLocked(rotateIfNeeded = false) }

        val files = getLogFiles()
        if (files.isEmpty()) return@withContext null

        val exportDir = File(dir, "export")
        if (!exportDir.exists()) exportDir.mkdirs()
        val out = File(exportDir, "cammonitor-logs.zip")
        if (out.exists()) out.delete()

        try {
            java.util.zip.ZipOutputStream(
                java.io.BufferedOutputStream(FileOutputStream(out))
            ).use { zip ->
                files.forEach { f ->
                    zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build log bundle", e)
            null
        }
    }

    // ------------------------------------------------------------- writer core

    /**
     * @param mandatory session-bracketing lines (start banner, shutdown marker,
     * crash dump) ignore [minLevel]. Losing the shutdown marker to a threshold
     * would make every following boot claim the previous run crashed.
     */
    private fun logEntry(level: LogLevel, tag: String, message: String, mandatory: Boolean = false) {
        if (!mandatory && level.ordinal < minLevel.ordinal) return

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
            message = message,
            elapsedMs = SystemClock.elapsedRealtime() - processStartElapsed,
            thread = Thread.currentThread().name
        )

        if (logChannel.trySend(entry).isFailure) {
            // The writer is behind (stuck filesystem, huge burst). Counting the
            // loss is strictly better than growing an unbounded queue until OOM.
            val dropped = droppedEntries.incrementAndGet()
            if (dropped == 1L || dropped % 1000L == 0L) {
                Log.w(TAG, "Log queue full, dropped $dropped entries so far")
            }
        }
    }

    private suspend fun writerLoop() {
        while (logScope.isActive) {
            // Wait for at least one line, but never longer than the idle flush
            // window so a quiet process still gets its tail persisted.
            val first = withTimeoutOrNull(FLUSH_IDLE_MS) { logChannel.receive() }

            writerMutex.withLock {
                try {
                    if (first != null) {
                        appendLocked(first)
                        // Drain whatever else is already queued; this is what
                        // turns N open/write/close into one write per batch.
                        while (true) {
                            val more = logChannel.tryReceive().getOrNull() ?: break
                            appendLocked(more)
                        }
                    }
                    val idle = first == null
                    val now = SystemClock.elapsedRealtime()
                    if (idle || pendingLines >= FLUSH_BATCH ||
                        now - lastFlushAt >= FLUSH_IDLE_MS
                    ) {
                        flushLocked(rotateIfNeeded = true)
                    }
                    // Only republish when something actually arrived. The idle
                    // timeout fires every second, and republishing a 500-entry
                    // list then would allocate continuously for a UI that is
                    // almost never on screen.
                    if (first != null && now - lastUiPublishAt >= UI_PUBLISH_INTERVAL_MS) {
                        publishUiLocked()
                        lastUiPublishAt = now
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Log writer failed", e)
                    closeWriterLocked()
                }
            }
        }
    }

    private fun appendLocked(entry: LogEntry) {
        val w = ensureWriterLocked() ?: return
        w.write(entry.format())
        w.write("\n")
        pendingLines++

        uiBuffer.addLast(entry)
        while (uiBuffer.size > MAX_MEMORY_LOGS) uiBuffer.removeFirst()
    }

    private fun ensureWriterLocked(): BufferedWriter? {
        writer?.let { return it }
        val file = activeLogFile ?: return null
        return try {
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
                16 * 1024
            ).also { writer = it }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open log file", e)
            null
        }
    }

    private fun flushLocked(rotateIfNeeded: Boolean) {
        val w = writer
        if (w == null) {
            pendingLines = 0
            return
        }
        try {
            w.flush()
            pendingLines = 0
            lastFlushAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.e(TAG, "Flush failed", e)
            closeWriterLocked()
            return
        }
        if (rotateIfNeeded) {
            val file = activeLogFile
            if (file != null && file.length() >= MAX_FILE_SIZE_BYTES) {
                closeWriterLocked()
                rotateLogFiles()
            }
        }
    }

    private fun closeWriterLocked() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        } finally {
            writer = null
            pendingLines = 0
        }
    }

    private fun publishUiLocked() {
        _logsFlow.value = uiBuffer.toList()
    }

    private fun rotateLogFiles() {
        val dir = logDir ?: return
        try {
            val oldest = File(dir, "cammonitor.$MAX_BACKUP_FILES.log")
            if (oldest.exists()) oldest.delete()

            for (i in (MAX_BACKUP_FILES - 1) downTo 1) {
                val current = File(dir, "cammonitor.$i.log")
                if (current.exists()) {
                    current.renameTo(File(dir, "cammonitor.${i + 1}.log"))
                }
            }

            val active = activeLogFile
            if (active != null && active.exists()) {
                active.renameTo(File(dir, "cammonitor.1.log"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating log files", e)
        }
    }

    // --------------------------------------------------------- crash & session

    /**
     * Reads the tail of the previous file looking for the shutdown marker.
     *
     * This is the cheapest way to answer "did it crash, or did it just stop
     * logging?" — a question the old logs could not answer at all.
     */
    private fun previousSessionEndedCleanly(): Boolean {
        val file = activeLogFile ?: return true
        if (!file.exists() || file.length() == 0L) return true
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val window = minOf(length, 4096L).toInt()
                raf.seek(length - window)
                val bytes = ByteArray(window)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).contains(SESSION_END_MARKER)
            }
        } catch (_: Exception) {
            true // cannot tell — stay quiet rather than cry wolf
        }
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // The process is about to die: this must not go through the
                // queue, it would never be flushed.
                appendSync(
                    LogLevel.ERROR,
                    TAG,
                    "FATAL uncaught exception on thread \"${thread.name}\"\n" +
                        Log.getStackTraceString(throwable)
                )
                appendSync(LogLevel.INFO, TAG, "$SESSION_END_MARKER (crash)")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Best-effort synchronous append used only on the crash path.
     *
     * Deliberately bypasses the coroutine queue and the mutex: whatever holds
     * them may itself be what died.
     */
    private fun appendSync(level: LogLevel, tag: String, message: String) {
        // Best effort: flush the buffered remainder first so the crash record
        // lands after the lines that led up to it instead of jumping ahead of
        // them. Never block — if the writer holds the lock, it is not ours.
        if (writerMutex.tryLock()) {
            try {
                writer?.flush()
            } catch (_: Throwable) {
            } finally {
                writerMutex.unlock()
            }
        }
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            elapsedMs = SystemClock.elapsedRealtime() - processStartElapsed,
            thread = Thread.currentThread().name
        )
        val file = activeLogFile ?: return
        try {
            FileOutputStream(file, true).use { fos ->
                fos.write((entry.format() + "\n").toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (_: Throwable) {
            // Nothing left to do; logcat already has it via the synchronous path.
        }
    }
}
