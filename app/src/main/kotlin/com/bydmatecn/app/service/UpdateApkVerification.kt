package com.bydmatecn.app.service

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** Never hand an unrelated APK or an incompatible signing key to the installer. */
internal fun verifyUpdateApk(context: Context, file: File, expectedVersion: String) {
    val pm = context.packageManager
    val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: error("无法解析下载的 APK")
    val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    require(archive.packageName == context.packageName) { "安装包应用 ID 不一致，拒绝安装" }
    require(archive.versionName == expectedVersion && archive.longVersionCode > installed.longVersionCode) {
        "安装包版本与发布信息不符，或 versionCode 未递增，请发布者修正"
    }
    val currentSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
    val nextSigners = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
    require(currentSigners.isNotEmpty() && currentSigners == nextSigners) {
        "安装包签名与已安装应用不一致，无法覆盖更新；请使用同一签名发布"
    }
}
