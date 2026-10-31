package com.bydmatecn.app.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForkReleasePolicyTest {
    @Test fun includesWipAndUsesVersionOrderingNotApiOrder() {
        val releases = JSONArray().put(release("v1.0.1")).put(release("v1.2.0")).put(release("v2.0.0", true))
        assertEquals("v1.2.0", ForkReleasePolicy.select(releases, "1.0.0")?.getString("tag_name"))
        assertNull(ForkReleasePolicy.select(releases, "1.2.0"))
        assertTrue(ForkReleasePolicy.isNewer("v1.10.0", "1.9.0"))
        assertFalse(ForkReleasePolicy.isNewer("1.bad.9", "1.0.0"))
    }
    @Test fun automaticUpdatesIgnoreFourPartWipReleases() {
        val releases = JSONArray()
            .put(release("v1.0.0.3"))
            .put(release("v1.0.1"))
            .put(release("v1.1.0.7"))
        assertEquals("v1.0.1", ForkReleasePolicy.select(releases, "1.0.0")?.getString("tag_name"))
        assertNull(ForkReleasePolicy.select(JSONArray().put(release("v1.0.0.3")), "1.0.0"))
        assertEquals("v1.0.1", ForkReleasePolicy.select(releases, "1.0.0.3")?.getString("tag_name"))
    }

    @Test fun fourPartVersionsUpgradeHistoricalVersionsAndCompareRevisionNumerically() {
        assertTrue(ForkReleasePolicy.isNewer("v1.0.0.1", "1.0.0"))
        assertFalse(ForkReleasePolicy.isNewer("v1.0.0", "1.0.0.1"))
        assertFalse(ForkReleasePolicy.isNewer("v1.0.0.0", "1.0.0"))
        assertTrue(ForkReleasePolicy.isNewer("v1.0.0.10", "1.0.0.9"))
        assertTrue(ForkReleasePolicy.isNewer("v1.0.1", "1.0.0.99"))
        val releases = JSONArray().put(release("v1.0.0.10")).put(release("v1.0.0.2"))
        assertNull(ForkReleasePolicy.select(releases, "1.0.0"))
        assertNull(ForkReleasePolicy.select(releases, "1.0.0.10"))
        assertEquals("BYDMateCN-1.0.0.1.apk", ForkReleasePolicy.downloadName("v1.0.0.1"))
        assertNull(ForkReleasePolicy.version("1.0.0.1.2"))
        assertNull(ForkReleasePolicy.version("1.0.0."))
        assertNull(ForkReleasePolicy.version("1.0.0.-1"))
        assertNull(ForkReleasePolicy.version("1.0.0.2147483648"))
    }
    @Test fun acceptsOnlyExactForkAssetAndSharesDownloadInstallName() {
        val release = release("v1.0.1").put("assets", JSONArray().put(JSONObject().put("name", "other.apk"))
            .put(JSONObject().put("name", "BYDMateCN-1.0.1.apk").put("browser_download_url", URL)))
        assertEquals(URL, ForkReleasePolicy.apkUrl(release))
        assertEquals("BYDMateCN-1.0.1.apk", ForkReleasePolicy.downloadName("v1.0.1"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDonorDownload() {
        ForkReleasePolicy.requireDownloadUrl("https://github.com/AndyShaman/BYDMate/releases/download/v1.0.1/BYDMateCN-1.0.1.apk")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsForeignHost() {
        ForkReleasePolicy.requireDownloadUrl("https://example.com/bluarry/BYDMateCN/releases/download/v1.0.1/BYDMateCN-1.0.1.apk")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidVersion() {
        ForkReleasePolicy.downloadName("../../malicious")
    }
    private fun release(tag: String, draft: Boolean = false): JSONObject = JSONObject()
        .put("tag_name", tag).put("draft", draft)
        .put("prerelease", tag.removePrefix("v").count { it == '.' } == 3)
    companion object { private const val URL = "https://github.com/bluarry/BYDMateCN/releases/download/v1.0.1/BYDMateCN-1.0.1.apk" }
}
