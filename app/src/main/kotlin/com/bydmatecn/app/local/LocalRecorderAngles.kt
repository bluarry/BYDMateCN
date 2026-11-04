package com.bydmatecn.app.local

import android.view.View
import com.bydmatecn.app.camera.AvmFrameShare

internal fun LocalCameraActivity.selectRecorderDirection(direction: RecorderDirection) {
    val plan = VehicleRecorderPreferences.capturePlan(this, direction)
    val reopen = open
    AvmFrameShare.unpublish(texture)
    AvmFrameShare.unpublish(secondTexture)
    closePreview("切换${direction.displayName}视角")
    recorderDirection = direction
    index = plan.surfaceIndex
    dual = false
    secondTexture.visibility = View.GONE
    viewConfig = viewConfig.copy(horizontalFlip = plan.horizontalFlip)
    VehicleRecorderPreferences.prefs(this).edit()
        .putString(VehicleRecorderPreferences.KEY_CAPTURE_DIRECTION, direction.name)
        .apply()
    saveViewConfig()
    applyViewConfig()
    LocalFileLog.get(this).record("camera/angle", "direction=${direction.name}; channel=$index; flip=${plan.horizontalFlip}")
    if (reopen) requestPreviewRestart() else status.text = "已选择${direction.displayName}视角（AVM 通道 $index）"
}

internal fun LocalCameraActivity.openRecordedVideos() {
    val mode = if (featureMode == LocalCameraActivity.MODE_SENTRY) {
        LocalCameraActivity.MODE_SENTRY
    } else {
        LocalCameraActivity.MODE_DASHCAM
    }
    startActivity(android.content.Intent(this, LocalMediaActivity::class.java).putExtra(LocalMediaActivity.EXTRA_MODE, mode))
}
