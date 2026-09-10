package com.zerotimes.picocart.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgyerAppUpdateTest {
    @Test
    fun parseCheckReadsNewVersion() {
        val raw = """
            {
              "buildHaveNewVersion": true,
              "needForceUpdate": false,
              "buildVersion": "0.1.4",
              "buildVersionNo": "5",
              "buildBuildVersion": "10",
              "downloadURL": "https://www.pgyer.com/app/installUpdate/abc",
              "buildShortcutUrl": "picocartdebug",
              "buildUpdateDescription": "修复更新检测",
              "buildFileSize": "101813334"
            }
        """.trimIndent()
        val release = PgyerAppUpdateClient.parseCheck(raw)
        assertTrue(release.hasNewVersion)
        assertFalse(release.forceUpdate)
        assertEquals("0.1.4", release.versionName)
        assertEquals(5, release.versionCode)
        assertEquals(10, release.buildNumber)
        assertEquals("https://www.pgyer.com/app/installUpdate/abc", release.downloadUrl)
        assertEquals("https://www.pgyer.com/picocartdebug", release.installPageUrl)
        assertEquals("修复更新检测", release.updateDescription)
        assertEquals(101813334L, release.fileSizeBytes)
        assertEquals("0.1.4 (5) · #10", release.displayVersion)
    }

    @Test
    fun parseCheckKeepsFullShortcutUrl() {
        val release = PgyerAppUpdateClient.parseCheck(
            """{"buildHaveNewVersion":false,"buildShortcutUrl":"https://www.pgyer.com/picocartdebug"}""",
        )
        assertFalse(release.hasNewVersion)
        assertEquals("https://www.pgyer.com/picocartdebug", release.installPageUrl)
    }

    @Test
    fun formatFileSizeUsesMegabytes() {
        assertEquals("", PgyerAppUpdateClient.formatFileSize(null))
        assertEquals("97.1 MB", PgyerAppUpdateClient.formatFileSize(101813334L))
    }
}
