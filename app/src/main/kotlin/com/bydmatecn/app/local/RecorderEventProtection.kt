package com.bydmatecn.app.local

/** Worker-confined state for preserving the segment after a sentry event. */
internal class RecorderEventProtection {
    private var postRollReason: String? = null

    fun armPostRoll(reason: String) {
        postRollReason = reason
    }

    fun consumePostRoll(): String? = postRollReason.also { postRollReason = null }

    fun hasPostRoll(): Boolean = postRollReason != null
}
