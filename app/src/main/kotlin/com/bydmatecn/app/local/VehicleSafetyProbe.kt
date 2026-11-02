package com.bydmatecn.app.local

import com.bydmatecn.app.data.vehicle.BatchReadItem
import com.bydmatecn.app.data.vehicle.HelperClient
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

enum class VehicleMotionState { PARKED, MOVING, UNKNOWN }

data class VehicleSafetyState(
    val motion: VehicleMotionState,
    val gear: Int?,
    val speedKph: Float?,
    val powerState: Int?,
    val source: String,
)

/** Reads the small set of body-controller values used to guard exterior audio. */
class VehicleSafetyProbe(private val helper: HelperClient) {
    suspend fun read(timeoutMs: Long = 1_500L): VehicleSafetyState {
        val values = withTimeoutOrNull(timeoutMs) { helper.readBatch(ITEMS) }
        if (values == null || values.size != ITEMS.size) {
            return VehicleSafetyState(VehicleMotionState.UNKNOWN, null, null, null, "helper-unavailable")
        }
        fun raw(index: Int): Int? = values[index].let { (status, value) -> value.takeIf { status == 0 } }
        val gear = raw(0)
        val speed = raw(1)?.let(::decodeSpeed)
        val power = raw(2)
        val moving = (speed != null && abs(speed) > STOPPED_SPEED_KPH) ||
            (gear != null && gear != PARK_GEAR)
        val parked = gear == PARK_GEAR && speed != null && abs(speed) <= STOPPED_SPEED_KPH
        return VehicleSafetyState(
            motion = when { moving -> VehicleMotionState.MOVING; parked -> VehicleMotionState.PARKED; else -> VehicleMotionState.UNKNOWN },
            gear = gear,
            speedKph = speed,
            powerState = power,
            source = "helper-batch",
        )
    }

    companion object {
        const val PARK_GEAR = 1
        private const val STOPPED_SPEED_KPH = 0.5f
        val ITEMS = listOf(
            BatchReadItem(5, 1011, 555_745_336),
            BatchReadItem(7, 1013, -1_807_745_016),
            BatchReadItem(5, 1023, 315_621_408),
        )

        internal fun decodeSpeed(raw: Int): Float? = Float.fromBits(raw)
            .takeIf { it.isFinite() && abs(it) < 500f }
    }
}

