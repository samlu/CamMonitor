package com.a0soft.gphone.camonitor.update

import com.a0soft.gphone.camonitor.data.update.VersionCodeHelper
import org.junit.Assert.*
import org.junit.Test

class VersionCodeHelperTest {

    @Test
    fun testFinalReleaseBasic() {
        assertEquals(100_000_899, VersionCodeHelper.compute("1.0.8"))
        assertEquals(100_000_899, VersionCodeHelper.compute("v1.0.8"))
        assertEquals(100_000_999, VersionCodeHelper.compute("1.0.9"))
        assertEquals(100_100_099, VersionCodeHelper.compute("1.1.0"))
        assertEquals(100_100_199, VersionCodeHelper.compute("1.1.1"))
        assertEquals(200_000_099, VersionCodeHelper.compute("2.0.0"))
    }

    @Test
    fun testPreReleaseBetaAndRc() {
        assertEquals(100_000_801, VersionCodeHelper.compute("1.0.8-beta01"))
        assertEquals(100_000_803, VersionCodeHelper.compute("1.0.8-beta03"))
        assertEquals(100_000_803, VersionCodeHelper.compute("1.0.8-beta3"))
        assertEquals(100_000_802, VersionCodeHelper.compute("1.0.8-rc02"))
        assertEquals(100_000_805, VersionCodeHelper.compute("1.0.8-alpha.05"))
        assertEquals(100_000_807, VersionCodeHelper.compute("1.0.8-7"))
    }

    @Test
    fun testStrictMonotonicOrdering() {
        val beta1 = VersionCodeHelper.compute("1.0.8-beta01")
        val beta3 = VersionCodeHelper.compute("1.0.8-beta03")
        val finalRel = VersionCodeHelper.compute("1.0.8")
        val nextBeta = VersionCodeHelper.compute("1.0.9-beta01")

        assertTrue("Beta 1 must be strictly less than Beta 3", beta1 < beta3)
        assertTrue("Beta 3 must be strictly less than Final release", beta3 < finalRel)
        assertTrue("Final release 1.0.8 must be strictly less than 1.0.9 beta", finalRel < nextBeta)
    }

    @Test
    fun testIllegalMinorExceedsThreeDigits() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            VersionCodeHelper.compute("1.1234.4567")
        }
        assertTrue(ex.message!!.contains("Minor version (1234)"))
    }

    @Test
    fun testIllegalPatchExceedsThreeDigits() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            VersionCodeHelper.compute("1.2.3456")
        }
        assertTrue(ex.message!!.contains("Patch version (3456)"))
    }

    @Test
    fun testIllegalMajorExceedsLimit() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            VersionCodeHelper.compute("22.0.0")
        }
        assertTrue(ex.message!!.contains("Major version (22)"))
    }

    @Test
    fun testIllegalPreReleaseNum99() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            VersionCodeHelper.compute("1.0.8-beta99")
        }
        assertTrue(ex.message!!.contains("must be between 1 and 98"))
    }

    @Test
    fun testMalformedStrings() {
        assertThrows(IllegalArgumentException::class.java) { VersionCodeHelper.compute("1.0") }
        assertThrows(IllegalArgumentException::class.java) { VersionCodeHelper.compute("1.0.8.4") }
        assertThrows(IllegalArgumentException::class.java) { VersionCodeHelper.compute("abc") }
        assertThrows(IllegalArgumentException::class.java) { VersionCodeHelper.compute("") }
    }
}
