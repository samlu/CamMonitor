package com.a0soft.gphone.camonitor.update

import com.a0soft.gphone.camonitor.data.update.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun testParseStandardVersion() {
        val v = SemVer.parse("1.0.8")
        assertNotNull(v)
        assertEquals(1, v!!.major)
        assertEquals(0, v.minor)
        assertEquals(8, v.patch)
        assertNull(v.prerelease)
    }

    @Test
    fun testParseWithVPrefix() {
        val lower = SemVer.parse("v1.2.3")
        assertNotNull(lower)
        assertEquals(1, lower!!.major)
        assertEquals(2, lower.minor)
        assertEquals(3, lower.patch)

        val upper = SemVer.parse("V2.0.1")
        assertNotNull(upper)
        assertEquals(2, upper!!.major)
        assertEquals(0, upper.minor)
        assertEquals(1, upper.patch)
    }

    @Test
    fun testParseWithPrerelease() {
        val v = SemVer.parse("1.0.9-beta.1")
        assertNotNull(v)
        assertEquals(1, v!!.major)
        assertEquals(0, v.minor)
        assertEquals(9, v.patch)
        assertEquals("beta.1", v.prerelease)
    }

    @Test
    fun testParseInvalidReturnsNull() {
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("invalid"))
        assertNull(SemVer.parse("1.2"))
        assertNull(SemVer.parse("v1.2"))
        assertNull(SemVer.parse("1.2.x"))
    }

    @Test
    fun testComparisonPatchIncrement() {
        val oldV = SemVer.parse("1.0.8")!!
        val newV = SemVer.parse("1.0.9")!!
        assertTrue(newV > oldV)
        assertFalse(oldV > newV)
    }

    @Test
    fun testComparisonMinorIncrement() {
        val oldV = SemVer.parse("1.0.8")!!
        val newV = SemVer.parse("1.1.0")!!
        assertTrue(newV > oldV)
        assertFalse(oldV > newV)
    }

    @Test
    fun testComparisonMajorIncrement() {
        val oldV = SemVer.parse("1.9.9")!!
        val newV = SemVer.parse("2.0.0")!!
        assertTrue(newV > oldV)
        assertFalse(oldV > newV)
    }

    @Test
    fun testComparisonEquality() {
        val v1 = SemVer.parse("1.0.8")!!
        val v2 = SemVer.parse("v1.0.8")!!
        assertEquals(0, v1.compareTo(v2))
        assertEquals(v1, v2)
    }

    @Test
    fun testComparisonStableOverPrerelease() {
        val stable = SemVer.parse("1.0.8")!!
        val prerelease = SemVer.parse("1.0.8-alpha")!!
        assertTrue("Stable version must be considered newer than prerelease of same numbers", stable > prerelease)
    }

    @Test
    fun testComparisonPrereleaseOrdering() {
        val alpha = SemVer.parse("1.0.8-alpha")!!
        val beta = SemVer.parse("1.0.8-beta")!!
        assertTrue(beta > alpha)
    }
}
