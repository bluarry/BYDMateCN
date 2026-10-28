package com.bydmatecn.app.local

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalBackupActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var busy = false
    private val backup by lazy { LocalMediaBackup(filesDir, cacheDir) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runOperation { contentResolver.openOutputStream(uri)?.use { backup.export(it) } ?: error("无法写入") }
    }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) android.app.AlertDialog.Builder(this).setMessage("仅恢复媒体。拒绝同名覆盖；不恢复账号/数据库/权限。建议先备份并停止录音录像。")
            .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                runOperation { contentResolver.openInputStream(uri)?.use { backup.restore(it) } ?: error("无法读取") }
            }.show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(0xFF101A2D.toInt()) }
        status = TextView(this).apply { text = "本地媒体备份 / 恢复 · 不含账号及敏感数据库"; setTextColor(-1); textSize=20f }
        LocalFileLog.get(this).watch(status, "media-backup")
        layout.addView(status)
        fun button(label: String, action: () -> Unit) { layout.addView(Button(this).apply { text=label; setOnClickListener { if (!busy) { LocalFileLog.get(this@LocalBackupActivity).record("media-backup/action", label); action() } } }) }
        button("导出音频、截图和MP4备份") { export.launch("BYDMate-local-media.zip") }
        button("从本地ZIP恢复") { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
        button("返回") { finish() }
        setContentView(layout)
    }
    private fun runOperation(action: () -> Int) {
        if (busy) return
        lifecycleScope.launch {
            busy=true; status.text="正在处理，请勿录制、导入或删除媒体"
            try {
                val result = withContext(Dispatchers.IO) { runCatching(action) }
                status.text=result.fold({ "处理成功：$it 个媒体文件" }, { "处理失败：${it.message}" })
            } finally { busy=false }
        }
    }
}
