package com.bydmate.app.data.vehicle.reflection

/**
 * APK-derived read path: AutoTark tR.c / tR.b.
 * Class.forName -> static getInstance(Context) -> getAutoExternalRearMirrorState().
 * Accepts only the 1/2 codes accepted by AutoTark; their physical meaning is NOT guessed.
 * No BYD stubs, network, writes, or undocumented raw-FID fallback.
 * Runs under the caller UID: class presence does not imply permission to read.
 */
class RearMirrorStateReader(
    private val context: Any,
    private val contextType: Class<*>,
    private val reflection: BydReflection = BydReflection(),
    private val deviceClassName: String = DEVICE_CLASS,
) {
    fun read(): ReflectionRead<Int> = reflection.read lookup@{
        val owner = reflection.type(deviceClassName)
        val instance = reflection.invoke(owner, null, "getInstance", listOf(contextType), context)
            ?: return@lookup ReflectionRead.Unavailable(ReflectionFailure.INVALID_RESULT, "getInstance returned null")
        val raw = reflection.invoke(owner, instance, "getAutoExternalRearMirrorState", emptyList())
        val code = (raw as? Number)?.toInt()
        if (code == 1 || code == 2) {
            ReflectionRead.Value(code)
        } else {
            ReflectionRead.Unavailable(ReflectionFailure.INVALID_RESULT, "Unsupported rear mirror state: $raw")
        }
    }

    companion object {
        const val DEVICE_CLASS = "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice"
    }
}
