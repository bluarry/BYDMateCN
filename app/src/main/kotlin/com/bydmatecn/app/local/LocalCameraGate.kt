package com.bydmatecn.app.local

/** Manual preview fails closed on unknown/negative speed and unsupported gears. */
internal fun localRecordingAllowed(gear: Int?, speed: Int?, driving: Boolean): Boolean =
    speed != null && speed >= 0 && ((gear == 1 && speed == 0) || (driving && gear == 4))
