package com.bydmate.app.local

/** Fail closed on unknown gear/speed, revoked observation permission or factory ownership. */
internal fun localCameraAllowed(gear: Int?, speed: Int?, usageAllowed: Boolean, factoryActive: Boolean): Boolean =
    gear == 1 && speed == 0 && usageAllowed && !factoryActive
