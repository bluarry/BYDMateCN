package com.bydmatecn.app.local

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Canonical directory identity shared by import, quota cleanup, gallery and backup. */
internal object LocalMediaLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forDirectory(root: File): Any = locks.computeIfAbsent(root.canonicalPath) { Any() }
}
