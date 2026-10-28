package com.bydmatecn.app.local

/** Fail closed on unknown gear/speed, revoked observation permission or factory ownership. */
internal fun localCameraAllowed(gear: Int?, speed: Int?, usageAllowed: Boolean, factoryActive: Boolean): Boolean =
    gear == 1 && speed == 0 && usageAllowed && !factoryActive


/** Driving capture is an explicit opt-in; reverse/unknown/negative speed always releases AVM. */
internal fun localRecordingAllowed(gear: Int?, speed: Int?, usage: Boolean, factory: Boolean, driving: Boolean): Boolean =
    usage && !factory && speed != null && speed >= 0 &&
        ((gear == 1 && speed == 0) || (driving && gear == 4))
