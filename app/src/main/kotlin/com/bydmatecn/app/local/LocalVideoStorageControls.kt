package com.bydmatecn.app.local

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun LocalCameraActivity.addVideoStorageControls() {
    ui.controls.add {
        VideoStorageSummary(
            description = LocalVideoStorage.description(this, currentRecorderMode()),
            onBrowse = { requestVideoDirectoryAccess { showVideoDirectoryPicker(initialVideoDirectory()) } },
            onInput = { requestVideoDirectoryAccess { promptVideoDirectory() } },
            onReset = { changeVideoDirectory(null) },
        )
    }
}

private fun LocalCameraActivity.currentRecorderMode(): VehicleRecorderMode =
    if (featureMode == LocalCameraActivity.MODE_SENTRY) VehicleRecorderMode.PARKING_SENTRY else VehicleRecorderMode.DASHCAM

private fun LocalCameraActivity.requestVideoDirectoryAccess(action: () -> Unit) {
    val missing = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) {
        videoStoragePermission.launch(missing.toTypedArray())
        status.text = "请授予存储读写权限后重新选择录像目录"
    } else action()
}

private fun LocalCameraActivity.initialVideoDirectory(): File =
    LocalVideoStorage.configuredPath(this, currentRecorderMode())?.let(::File)?.takeIf { it.isDirectory }
        ?: File("/storage/emulated/0")

internal fun LocalCameraActivity.showVideoDirectoryPicker(directory: File) {
    lifecycleScope.launch {
        val children = withContext(Dispatchers.IO) {
            directory.listFiles().orEmpty().filter {
                it.isDirectory && it.canRead() && !java.nio.file.Files.isSymbolicLink(it.toPath())
            }.sortedBy { it.name.lowercase() }
        }
        val parent = directory.parentFile
        val labels = buildList {
            add("选择此目录")
            add("输入其他路径")
            if (parent != null) add("上一级")
            addAll(children.map { "${it.name}/" })
        }
        LocalDialogBuilder(this@showVideoDirectoryPicker).setTitle(directory.path)
            .setItems(labels.toTypedArray()) { _, selected ->
                when {
                    selected == 0 -> changeVideoDirectory(directory.path)
                    selected == 1 -> promptVideoDirectory()
                    parent != null && selected == 2 -> showVideoDirectoryPicker(parent)
                    else -> showVideoDirectoryPicker(children[selected - if (parent != null) 3 else 2])
                }
            }.setNegativeButton("取消", null).show()
    }
}

private fun LocalCameraActivity.promptVideoDirectory() {
    val input = LocalInput("录像目录绝对路径", initialVideoDirectory().path)
    LocalDialogBuilder(this).setTitle("设置录像存储目录").setView(input)
        .setMessage("目录必须可读写。切换后，新录像保存到新目录；旧录像不会移动。")
        .setNegativeButton("取消", null)
        .setPositiveButton("使用此目录") { _, _ -> changeVideoDirectory(input.text.trim()) }
        .show()
}

private fun LocalCameraActivity.changeVideoDirectory(path: String?) {
    if (videoRecording || videoRecorder.active) {
        status.text = "请先停止当前录像，再切换存储目录"
        return
    }
    val result = runCatching {
        stopService(android.content.Intent(this, VehicleRecorderService::class.java))
        val directory = if (path == null) LocalVideoStorage.reset(this, currentRecorderMode()) else LocalVideoStorage.setDirectory(this, currentRecorderMode(), path)
        videos = LocalVideoStore(directory, VehicleRecorderPreferences.quotaMb(this, currentRecorderMode()).toLong() * 1024 * 1024)
        videoRecorder = LocalVideoRecorder(videos)
        if (VehicleRecorderPreferences.enabled(this)) VehicleRecorderService.start(this)
        directory
    }
    status.text = result.fold(
        { "${if (currentRecorderMode() == VehicleRecorderMode.PARKING_SENTRY) "哨兵" else "行车"}录像目录已切换：${it.path}；旧目录文件保持不变" },
        { "录像目录设置失败：${it.message}" },
    )
}
