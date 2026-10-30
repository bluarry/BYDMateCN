package com.bydmatecn.app.service

import org.json.JSONArray
import org.json.JSONObject

/** Automatic updates accept only stable three-part releases; four-part WIP builds are manual installs. */
internal object ForkReleasePolicy {
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?$")
    private val stableVersionPattern = Regex("^v?\\d+\\.\\d+\\.\\d+$")
    /** Three-part historical versions have revision zero; compare all four parts numerically. */
    fun version(tag: String): List<Int>? {
        val parts = versionPattern.matchEntire(tag)?.groupValues?.drop(1) ?: return null
        return parts.map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
    }
    fun isNewer(remote: String, local: String): Boolean {
        val a = version(remote) ?: return false
        val b = version(local) ?: return false
        for (index in a.indices) if (a[index] != b[index]) return a[index] > b[index]
        return false
    }
    fun select(releases: JSONArray, current: String): JSONObject? {
        require(version(current) != null) { "当前版本号格式无效：$current" }
        val candidates = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            .filter {
                val tag = it.optString("tag_name")
                !it.optBoolean("draft") && !it.optBoolean("prerelease") &&
                    stableVersionPattern.matches(tag) && version(tag) != null
            }
        if (candidates.isEmpty()) return null
        return candidates.filter { isNewer(it.optString("tag_name"), current) }
            .maxWithOrNull(compareBy<JSONObject> { version(it.optString("tag_name"))!![0] }
                .thenBy { version(it.optString("tag_name"))!![1] }.thenBy { version(it.optString("tag_name"))!![2] }
                .thenBy { version(it.optString("tag_name"))!![3] })
    }
    fun apkUrl(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: error("发布版本没有附件")
        val expectedName = downloadName(release.optString("tag_name"))
        val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name") == expectedName }
        require(matches.size == 1) { "发布版本必须包含唯一的 $expectedName" }
        return matches.single().optString("browser_download_url").also { requireDownloadUrl(it) }
    }
    fun requireDownloadUrl(url: String) {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.startsWith("/bluarry/BYDMateCN/releases/download/") &&
            Regex("/BYDMateCN-\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?\\.apk$").containsMatchIn(uri.path)) {
            "拒绝非 BYDMateCN 官方仓库的安装包地址"
        }
    }
    fun downloadName(version: String): String {
        require(version(version) != null) { "更新版本号无效" }
        return "BYDMateCN-${version.removePrefix("v")}.apk"
    }
}
