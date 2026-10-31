package com.bydmatecn.app.data.vehicle

import com.bydmatecn.app.data.vehicle.reflection.RearMirrorStateReader
import com.bydmatecn.app.data.vehicle.reflection.ReflectionRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adds firmware reflection reads without coupling the vehicle write channels to that SDK. */
class RearMirrorVehicleApi(private val delegate: VehicleApi, private val reader: RearMirrorStateReader) : VehicleApi by delegate {
    override suspend fun readRearMirrorState(): ReflectionRead<Int> = withContext(Dispatchers.IO) { reader.read() }
}
