package com.heinshare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较与 tag 规范化的测试。
 */
class UpdateCheckerTest {

    @Test
    fun normalizeStripsVPrefix() {
        assertEquals("1.0.0", UpdateChecker.normalizeVersion("v1.0.0"))
        assertEquals("1.0.0", UpdateChecker.normalizeVersion("V1.0.0"))
        assertEquals("1.0.0", UpdateChecker.normalizeVersion(" 1.0.0 "))
    }

    @Test
    fun compareOrdersVersions() {
        assertTrue(UpdateChecker.compareVersion("1.0.1", "1.0.0") > 0)
        assertTrue(UpdateChecker.compareVersion("1.0.0", "1.0.1") < 0)
        assertEquals(0, UpdateChecker.compareVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun comparePadsMissingSegments() {
        assertEquals(0, UpdateChecker.compareVersion("1.0", "1.0.0"))
        assertTrue(UpdateChecker.compareVersion("1.0.1", "1.0") > 0)
        assertTrue(UpdateChecker.compareVersion("1.1", "1.0.9") > 0)
    }

    @Test
    fun compareHandlesNonNumericSegments() {
        assertTrue(UpdateChecker.compareVersion("1.0.1-beta", "1.0.0") > 0)
        assertEquals(0, UpdateChecker.compareVersion("1.0.x", "1.0.0"))
    }
}
