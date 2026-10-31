package com.bydmatecn.app.local

/** View transforms only: never sends unverified commands to the vehicle camera. */
data class MirrorViewConfig(val horizontalFlip: Boolean = false, val zoomPercent: Int = 100, val rotation: Int = 0) {
    init {
        require(zoomPercent in 100..200)
        require(rotation in setOf(0, 90, 180, 270))
    }
    fun scaleX(): Float = zoomPercent / 100f * if (horizontalFlip) -1f else 1f
    fun scaleY(): Float = zoomPercent / 100f
}
