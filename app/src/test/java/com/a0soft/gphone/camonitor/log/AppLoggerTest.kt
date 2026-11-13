package com.a0soft.gphone.camonitor.log

import com.a0soft.gphone.camonitor.data.model.LogEntry
import com.a0soft.gphone.camonitor.data.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppLoggerTest {

    @Test
    fun testLogLineRegexWithFullEntry() {
        val entry = LogEntry(
            timestamp = 1700000000000L,
            level = LogLevel.INFO,
            tag = "TestTag",
            message = "Hello world log message",
            elapsedMs = 12345L,
            thread = "main",
        )
        val formatted = entry.format()
        val match = AppLogger.LOG_LINE_REGEX.matchEntire(formatted)
        assertNotNull("Should match formatted entry", match)

        match!!
        assertNotNull(match.groups[1]?.value) // timestamp
        assertEquals("12.3", match.groups[2]?.value) // relative elapsed seconds
        assertEquals("INFO", match.groups[3]?.value)
        assertEquals("TestTag", match.groups[4]?.value)
        assertEquals("main", match.groups[5]?.value)
        assertEquals("Hello world log message", match.groups[6]?.value)
    }

    @Test
    fun testLogLineRegexWithoutThreadAndElapsed() {
        val line = "[2026-11-13 08:00:00.000] [WARN] [Network] Connection timed out"
        val match = AppLogger.LOG_LINE_REGEX.matchEntire(line)
        assertNotNull("Should match line without thread and elapsed time", match)

        match!!
        assertEquals("2026-11-13 08:00:00.000", match.groups[1]?.value)
        assertNull(match.groups[2]?.value)
        assertEquals("WARN", match.groups[3]?.value)
        assertEquals("Network", match.groups[4]?.value)
        assertNull(match.groups[5]?.value)
        assertEquals("Connection timed out", match.groups[6]?.value)
    }

    @Test
    fun testLogLineRegexWithEmptyMessage() {
        val line = "[2026-11-13 08:00:00.000 +1.0s] [DEBUG] [Ping] {coroutine-1}"
        val match = AppLogger.LOG_LINE_REGEX.matchEntire(line)
        assertNotNull("Should match line without message body", match)

        match!!
        assertEquals("DEBUG", match.groups[3]?.value)
        assertEquals("Ping", match.groups[4]?.value)
        assertEquals("coroutine-1", match.groups[5]?.value)
        assertNull(match.groups[6]?.value)
    }

    @Test
    fun testLogLineRegexWithSpecialCharactersInTag() {
        val line = "[2026-11-13 08:00:00.000 +0.0s] [ERROR] [WebRTC/PeerConnection#1] {main} ICE failed"
        val match = AppLogger.LOG_LINE_REGEX.matchEntire(line)
        assertNotNull("Should match tag with special characters", match)

        match!!
        assertEquals("WebRTC/PeerConnection#1", match.groups[4]?.value)
        assertEquals("ICE failed", match.groups[6]?.value)
    }
}
