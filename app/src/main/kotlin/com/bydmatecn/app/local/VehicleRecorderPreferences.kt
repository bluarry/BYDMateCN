package com.bydmatecn.app.local

import android.content.Context

/** Persistent options for the local dashcam and parking-sentry recorder. */
object VehicleRecorderPreferences {
    const val PREFS = "vehicle-recorder"
    const val KEY_DASHCAM = "dashcam_enabled"
    const val KEY_SENTRY = "parking_sentry_enabled"
    const val KEY_SEGMENT_MINUTES = "segment_minutes"
    const val KEY_QUOTA_MB = "quota_mb"
    const val KEY_SENTRY_HOURS = "sentry_hours"
    const val KEY_DOOR_EVENT = "door_event"
    const val KEY_IMPACT_EVENT = "impact_event"
    const val KEY_IMPACT_THRESHOLD = "impact_threshold_tenths"
    const val KEY_LAST_STATUS = "last_status"
    const val KEY_LAST_STATUS_AT = "last_status_at"
    const val KEY_CAPTURE_DIRECTION = "capture_direction"
    const val KEY_CAPTURE_SURFACE_INDEX = "capture_surface_index"
    const val KEY_CAPTURE_HORIZONTAL_FLIP = "capture_horizontal_flip"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).run {
        getBoolean(KEY_DASHCAM, false) || getBoolean(KEY_SENTRY, false)
    }
    fun segmentMinutes(context: Context): Int = prefs(context).getInt(KEY_SEGMENT_MINUTES, 3)
        .takeIf { it in setOf(1, 3, 5) } ?: 3
    fun quotaMb(context: Context): Int = prefs(context).getInt(KEY_QUOTA_MB, 2048)
        .coerceIn(512, 8192)
    fun sentryHours(context: Context): Int = prefs(context).getInt(KEY_SENTRY_HOURS, 3)
        .takeIf { it in setOf(1, 3, 6, 12) } ?: 3

    fun capturePlan(context: Context): RecorderCapturePlan = prefs(context).run {
        val direction = RecorderDirection.fromStored(getString(KEY_CAPTURE_DIRECTION, null))
        RecorderCapturePlan(
            direction = direction,
            // Existing installs retain the old global values until each direction is calibrated.
            surfaceIndex = getInt(
                captureSurfaceKey(direction),
                getInt(KEY_CAPTURE_SURFACE_INDEX, RecorderCapturePlan.DEFAULT_SURFACE_INDEX),
            ),
            horizontalFlip = getBoolean(
                captureFlipKey(direction),
                getBoolean(KEY_CAPTURE_HORIZONTAL_FLIP, false),
            ),
        ).sanitized()
    }

    fun setCaptureSurface(context: Context, direction: RecorderDirection, surfaceIndex: Int) {
        prefs(context).edit().putInt(captureSurfaceKey(direction), surfaceIndex.coerceIn(1, 4)).apply()
    }

    fun setCaptureFlip(context: Context, direction: RecorderDirection, enabled: Boolean) {
        prefs(context).edit().putBoolean(captureFlipKey(direction), enabled).apply()
    }

    internal fun captureSurfaceKey(direction: RecorderDirection) =
        "${KEY_CAPTURE_SURFACE_INDEX}_${direction.name.lowercase()}"

    internal fun captureFlipKey(direction: RecorderDirection) =
        "${KEY_CAPTURE_HORIZONTAL_FLIP}_${direction.name.lowercase()}"
}

/** Panorama strip order observed on the vehicle AVM source: rear, left, right, front. */
enum class RecorderDirection(val displayName: String, val stripIndex: Int) {
    FRONT("前方", 3), LEFT("左侧", 1), REAR("后方", 0), RIGHT("右侧", 2);

    companion object {
        fun fromStored(value: String?): RecorderDirection = entries.firstOrNull { it.name == value } ?: FRONT
    }
}

data class RecorderCapturePlan(
    val direction: RecorderDirection = RecorderDirection.FRONT,
    val surfaceIndex: Int = DEFAULT_SURFACE_INDEX,
    val horizontalFlip: Boolean = false,
) {
    fun sanitized(): RecorderCapturePlan = copy(surfaceIndex = surfaceIndex.takeIf { it in 1..4 } ?: DEFAULT_SURFACE_INDEX)

    companion object { const val DEFAULT_SURFACE_INDEX = 2 }
}

enum class VehicleRecorderMode { OFF, DASHCAM, PARKING_SENTRY }

data class VehicleRecorderDecision(
    val mode: VehicleRecorderMode,
    val reason: String,
)

/** Snapshot consumed by the recorder state machine. */
internal data class VehicleRecorderInput(
    val dashcamEnabled: Boolean,
    val sentryEnabled: Boolean,
    val powerState: Int?,
    val gear: Int?,
    val speedKph: Float?,
    val locked: Int?,
)

/** Fail closed: an unreadable gear/speed/power state never starts a camera in the background. */
internal fun decideVehicleRecorderMode(input: VehicleRecorderInput): VehicleRecorderDecision = when {
    !input.hasReadableVehicleState() -> VehicleRecorderDecision(VehicleRecorderMode.OFF, "车辆状态不可读")
    input.canRecordDrive() -> VehicleRecorderDecision(VehicleRecorderMode.DASHCAM, "车辆已上电并进入 D 挡")
    input.canRecordParking() -> VehicleRecorderDecision(VehicleRecorderMode.PARKING_SENTRY, "车辆熄火、驻车且已锁定")
    else -> VehicleRecorderDecision(VehicleRecorderMode.OFF, "未满足自动录像条件")
}

private fun VehicleRecorderInput.hasReadableVehicleState(): Boolean =
    powerState != null && gear != null && speedKph?.isFinite() == true

private fun VehicleRecorderInput.canRecordDrive(): Boolean =
    dashcamEnabled && powerState == 2 && gear == DRIVE_GEAR

private fun VehicleRecorderInput.canRecordParking(): Boolean =
    sentryEnabled && powerState != 2 && gear == VehicleSafetyProbe.PARK_GEAR &&
        speedKph?.let { kotlin.math.abs(it) <= STOPPED_SPEED_KPH } == true && locked == 2

private const val DRIVE_GEAR = 4
private const val STOPPED_SPEED_KPH = 0.5f
