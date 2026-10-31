package com.bydmatecn.app.local

import android.content.Context
import java.io.File

/** Persisted, user-selected direct filesystem directories on the API 29 car head unit. */
class LocalAudioLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("local-audio-directories", Context.MODE_PRIVATE)
    fun directories(): List<File> = prefs.getStringSet("paths", emptySet()).orEmpty().sorted().map(::File)
    fun add(path: String) {
        require(File(path).isAbsolute) { "请输入绝对目录路径" }
        val directory = File(path).canonicalFile
        require(directory.isDirectory && directory.canRead() && directory.listFiles() != null) { "目录不存在或没有读取权限" }
        val paths = directories().map { it.path }.toMutableSet()
        require(paths.size < 32 || directory.path in paths) { "最多支持32个音频目录" }
        paths += directory.path
        prefs.edit().putStringSet("paths", paths).apply()
    }
    fun remove(directory: File) {
        prefs.edit().putStringSet("paths", directories().map { it.path }.toSet() - directory.path).apply()
    }
    fun files(): List<File> = LocalAudioDirectoryIndex(directories()).files()
    fun resolve(path: String?): File? = LocalAudioDirectoryIndex(directories()).resolve(path)
}
