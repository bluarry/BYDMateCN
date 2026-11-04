package com.bydmatecn.app.local

import android.content.Context
import java.io.File
import java.nio.file.Files

/** Resolves independent writable roots for dashcam and parking-sentry recordings. */
@Suppress("TooManyFunctions") // Directory resolution and one-time migration share the same invariants.
object LocalVideoStorage {
    private const val PREFS = VehicleRecorderPreferences.PREFS
    private const val KEY_DASHCAM_DIRECTORY = "dashcam_video_storage_directory"
    private const val KEY_SENTRY_DIRECTORY = "sentry_video_storage_directory"
    private const val KEY_LEGACY_MIGRATED = "legacy_video_storage_migrated"

    fun configuredPath(context: Context, mode: VehicleRecorderMode): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(mode), null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun defaultDirectory(context: Context, mode: VehicleRecorderMode): File = File(
        context.filesDir,
        if (mode == VehicleRecorderMode.PARKING_SENTRY) "local-sentry-videos" else "local-dashcam-videos",
    )

    fun directory(context: Context, mode: VehicleRecorderMode): File {
        require(mode != VehicleRecorderMode.OFF) { "录像模式不能为空" }
        val configured = configuredPath(context, mode)?.let(::File)
        val active = if (configured != null && isWritableDirectory(configured)) {
            configured
        } else {
            defaultDirectory(context, mode).also {
                check(it.isDirectory || it.mkdirs()) { "无法创建应用录像目录" }
            }
        }
        if (mode == VehicleRecorderMode.DASHCAM) migrateLegacyDashcamVideos(context, active)
        return active
    }

    fun setDirectory(context: Context, mode: VehicleRecorderMode, path: String): File {
        require(mode != VehicleRecorderMode.OFF) { "录像模式不能为空" }
        val directory = File(path.trim())
        require(directory.isAbsolute) { "请输入绝对目录路径" }
        require(!Files.isSymbolicLink(directory.toPath())) { "不能使用符号链接目录" }
        check((directory.isDirectory || directory.mkdirs()) && directory.canRead() && directory.canWrite()) {
            "目录不存在或不可读写"
        }
        val otherMode = if (mode == VehicleRecorderMode.DASHCAM) VehicleRecorderMode.PARKING_SENTRY else VehicleRecorderMode.DASHCAM
        val other = runCatching { directory(context, otherMode).canonicalFile }.getOrNull()
        require(other == null || directory.canonicalFile != other) { "行车记录与驻车哨兵不能使用同一目录" }
        val probe = File.createTempFile(".bydmatecn-write-", ".tmp", directory)
        check(probe.delete()) { "目录写入测试文件无法清理" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(mode), directory.canonicalPath).apply()
        LocalFileLog.get(context).record("video/storage", "mode=$mode; directory=${directory.canonicalPath}")
        return directory.canonicalFile
    }

    fun reset(context: Context, mode: VehicleRecorderMode): File {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(mode)).apply()
        return directory(context, mode)
    }

    fun description(context: Context, mode: VehicleRecorderMode): String {
        val configured = configuredPath(context, mode)
        val active = directory(context, mode)
        return if (configured == null) "应用私有目录：${active.path}" else if (active.canonicalPath == File(configured).canonicalPath) {
            "自定义目录：${active.path}"
        } else {
            "自定义目录不可用，已回退：${active.path}"
        }
    }

    /**
     * Earlier builds mixed all recordings in local-videos. Their original mode cannot be proven, so
     * migrate them once into the dashcam root rather than exposing the same files to both features.
     */
    @Synchronized
    @Suppress("CyclomaticComplexMethod") // One-time migration keeps video/marker pairs atomic and diagnosable.
    private fun migrateLegacyDashcamVideos(context: Context, destination: File) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_LEGACY_MIGRATED, false)) return
        val legacy = File(context.filesDir, "local-videos")
        if (!legacy.isDirectory) {
            preferences.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
            return
        }
        val candidates = legacy.listFiles().orEmpty().filter { file ->
            file.isFile && (file.name.matches(Regex("""video-[A-Za-z0-9._-]+\.mp4""")) ||
                file.name.matches(Regex("""video-[A-Za-z0-9._-]+\.mp4\.event""")))
        }
        var migrated = 0
        candidates.filterNot { it.name.endsWith(".event") }.forEach { video ->
            val targetVideo = uniqueMigrationTarget(destination, video.name)
            val targetMarker = File(destination, targetVideo.name + ".event")
            val videoMoved = moveLegacyFile(video, targetVideo)
            if (videoMoved) migrated++
            val marker = File(legacy, video.name + ".event")
            if (videoMoved && marker in candidates && moveLegacyFile(marker, targetMarker)) migrated++
        }
        // Preserve an orphan marker for diagnostics instead of attaching it to an unrelated video.
        candidates.filter { it.name.endsWith(".event") && File(legacy, it.name.removeSuffix(".event")) !in candidates }
            .forEach { marker ->
                if (moveLegacyFile(marker, uniqueMigrationTarget(destination, marker.name))) migrated++
            }
        val remaining = legacy.listFiles().orEmpty().any { it in candidates }
        if (!remaining) preferences.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
        if (migrated > 0) {
            LocalFileLog.get(context).record(
                "video/storage",
                "旧版混合录像已迁移到行车记录目录：count=$migrated; directory=${destination.path}",
            )
        }
    }


    private fun moveLegacyFile(source: File, target: File): Boolean = runCatching {
        java.nio.file.Files.move(source.toPath(), target.toPath())
        true
    }.getOrElse {
        runCatching {
            source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
            check(source.delete()) { "旧文件复制后无法删除" }
            true
        }.getOrElse { false }
    }

    private fun uniqueMigrationTarget(directory: File, name: String): File {
        val direct = File(directory, name)
        if (!direct.exists()) return direct
        val event = name.endsWith(".event")
        val base = if (event) name.removeSuffix(".event") else name
        val stem = base.removeSuffix(".mp4")
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem-legacy-$index.mp4${if (event) ".event" else ""}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun key(mode: VehicleRecorderMode): String = when (mode) {
        VehicleRecorderMode.DASHCAM -> KEY_DASHCAM_DIRECTORY
        VehicleRecorderMode.PARKING_SENTRY -> KEY_SENTRY_DIRECTORY
        VehicleRecorderMode.OFF -> error("录像模式不能为空")
    }

    private fun isWritableDirectory(directory: File): Boolean = runCatching {
        directory.isAbsolute && directory.isDirectory && directory.canRead() && directory.canWrite() &&
            !Files.isSymbolicLink(directory.toPath())
    }.getOrDefault(false)
}

