package com.bydmatecn.app.local

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun LocalToolsActivity.audioDirectoryControls() {
    label("音频仅从已选择的目录读取；支持多个目录，不复制文件、不扫描子目录、不删除原文件。")
    button("添加音频目录（浏览路径）") {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else showAudioDirectoryPicker(File("/storage/emulated/0"))
    }
    button("添加音频目录（输入绝对路径）") {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return@button
        }
        val input = LocalInput("/storage/emulated/0/Music 或 U盘目录", "/storage/emulated/0/Music")
        LocalDialogBuilder(this).setTitle("添加音频目录").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ -> addAudioDirectory(input.text.toString().trim()) }.show()
    }
    button("查看 / 移除音频目录") {
        val directories = audioLibrary.directories()
        if (directories.isEmpty()) { status.text = "尚未选择音频目录"; return@button }
        LocalDialogBuilder(this).setTitle("选择要移除的目录（不删除文件）")
            .setItems(directories.map { it.path }.toTypedArray()) { _, index ->
                stopAudio()
                stopService(android.content.Intent(this@audioDirectoryControls, LocalLockSoundService::class.java))
                audioLibrary.remove(directories[index])
                if (audioLibrary.resolve(selectedAudio?.path) == null) {
                    selectedAudio = null
                    getSharedPreferences("local-audio", android.content.Context.MODE_PRIVATE).edit().remove("selected").apply()
                }
                status.text = "已移除目录，原文件保留；关联音效在重新添加目录前不可用"
            }.show()
    }
    button("扫描目录并选择音频") { scanAudioDirectories() }
}

internal fun LocalToolsActivity.addAudioDirectory(path: String) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { audioLibrary.add(path) } }
        status.text = result.fold({ "已添加目录：$path" }, { "添加失败：${it.message}" })
    }
}

internal fun LocalToolsActivity.showAudioDirectoryPicker(directory: File) {
    lifecycleScope.launch {
        val children = withContext(Dispatchers.IO) {
            directory.listFiles().orEmpty().filter { it.isDirectory && it.canRead() &&
                !java.nio.file.Files.isSymbolicLink(it.toPath()) }.sortedBy { it.name }
        }
        val parent = directory.parentFile
        val names = listOf("选择此目录", "输入其他路径") +
            (if (parent != null) listOf("上一级") else emptyList()) + children.map { it.name + "/" }
        LocalDialogBuilder(this@showAudioDirectoryPicker).setTitle(directory.path)
            .setItems(names.toTypedArray()) { _, index ->
                when {
                    index == 0 -> addAudioDirectory(directory.path)
                    index == 1 -> promptAudioDirectory()
                    parent != null && index == 2 -> showAudioDirectoryPicker(parent)
                    else -> showAudioDirectoryPicker(children[index - if (parent != null) 3 else 2])
                }
            }.setNegativeButton("取消", null).show()
    }
}

private fun LocalToolsActivity.promptAudioDirectory() {
    val input = LocalInput("请输入绝对目录路径", "/storage/emulated/0")
    LocalDialogBuilder(this).setTitle("音频目录路径").setView(input).setNegativeButton("取消", null)
        .setPositiveButton("浏览") { _, _ ->
            val path = input.text.trim()
            val directory = File(path)
            if (!directory.isAbsolute || !directory.isDirectory || !directory.canRead()) status.text = "目录不存在或不可读取：$path"
            else showAudioDirectoryPicker(directory)
        }.show()
}

private fun LocalToolsActivity.scanAudioDirectories() {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { audioLibrary.files() } }
        result.onSuccess { files ->
            val unavailable = withContext(Dispatchers.IO) {
                audioLibrary.directories().count { !it.isDirectory || !it.canRead() || it.listFiles() == null }
            }
            status.text = "扫描完成：${files.size}个音频，${unavailable}个目录不可读；旧配置不自动替换"
            if (files.isNotEmpty()) LocalDialogBuilder(this@scanAudioDirectories).setTitle("选择音频（完整路径）")
                .setItems(files.map { it.path }.toTypedArray()) { _, index ->
                    stopAudio(); selectAudio(files[index]); status.text = "已选中：${files[index].path}"
                }.show()
        }.onFailure { status.text = "扫描失败：${it.message}" }
    }
}
