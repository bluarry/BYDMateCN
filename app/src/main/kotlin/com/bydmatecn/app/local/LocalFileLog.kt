package com.bydmatecn.app.local

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Single process-wide bounded writer; no disk IO on callers/UI; no network or public storage permission. */
class LocalFileLog private constructor(private val context: Context) {
    private val store = LocalLogFile(File(context.filesDir, "local-logs"))
    private val dropped = AtomicInteger()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "bydmatecn-local-log").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    init {
        worker.execute { runCatching { store.prune(retentionDays = LocalLogSettings.retentionDays(context)) } }
        if (LocalLogSettings.enabled(context)) {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            record("session", "start pid=${Process.myPid()} app=$version sdk=${android.os.Build.VERSION.SDK_INT} build=${android.os.Build.DISPLAY}")
        }
    }

    fun setEnabled(enabled: Boolean) {
        LocalLogSettings.setEnabled(context, enabled)
        if (enabled) record("journal", "日志记录已开启；保留${LocalLogSettings.retentionDays(context)}天")
    }

    fun setRetentionDays(days: Int) {
        LocalLogSettings.setRetentionDays(context, days)
        try { worker.execute { runCatching { store.prune(retentionDays = days) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
        record("journal", "日志保留时间已设置为${days}天")
    }

    fun record(module: String, message: String) {
        if (!LocalLogSettings.enabled(context)) return
        val at = System.currentTimeMillis()
        val safe = LocalLogFile.clean(message)
        try { worker.execute {
            try {
                val retentionDays = LocalLogSettings.retentionDays(context)
                val lost = dropped.getAndSet(0)
                if (lost > 0) store.append("journal", "dropped=$lost (queue full)", at, retentionDays)
                store.append(module, safe, at, retentionDays)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: java.io.IOException) { Log.w("BYDMateCNLog", "write failed: ${error.javaClass.simpleName}") }
            catch (error: IllegalStateException) { Log.w("BYDMateCNLog", "write failed: ${error.javaClass.simpleName}") }
            catch (error: IllegalArgumentException) { Log.w("BYDMateCNLog", "write failed: ${error.javaClass.simpleName}") }
            catch (error: SecurityException) { Log.w("BYDMateCNLog", "write failed: ${error.javaClass.simpleName}") }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    /** Call on IO dispatcher: FIFO barrier includes all earlier admitted events. Caller owns stream. */
    fun export(output: OutputStream) {
        val barrier = worker.submit(Callable {
            val days = LocalLogSettings.retentionDays(context)
            val lost = dropped.getAndSet(0)
            if (lost > 0 && LocalLogSettings.enabled(context)) store.append("journal", "dropped=$lost (queue full)", retentionDays = days)
            ByteArrayOutputStream().also { store.export(it, retentionDays = days) }.toByteArray()
        })
        try { output.write(barrier.get(30, TimeUnit.SECONDS)) }
        finally { barrier.cancel(false) }
    }

    companion object {
        @Volatile private var instance: LocalFileLog? = null
        fun get(context: Context): LocalFileLog = instance ?: synchronized(this) {
            instance ?: LocalFileLog(context.applicationContext).also { instance = it }
        }
    }
}
