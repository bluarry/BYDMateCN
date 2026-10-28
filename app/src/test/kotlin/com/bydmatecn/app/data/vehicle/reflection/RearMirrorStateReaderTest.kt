package com.bydmatecn.app.data.vehicle.reflection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RearMirrorStateReaderTest {
    class FakeContext
    class FakeDevice {
        fun getAutoExternalRearMirrorState(): Any? {
            failure?.let { throw it }
            return state
        }
        companion object {
            var state: Any? = 1
            var failure: RuntimeException? = null
            var returnNull = false
            @JvmStatic fun getInstance(context: FakeContext): FakeDevice? = if (returnNull) null else context.let { FakeDevice() }
        }
    }
    object WrongSignature {
        @JvmStatic fun getInstance(): WrongSignature = this
    }

    private fun reader(reflection: BydReflection = BydReflection { FakeDevice::class.java }): RearMirrorStateReader {
        FakeDevice.state = 1
        FakeDevice.failure = null
        FakeDevice.returnNull = false
        return RearMirrorStateReader(FakeContext(), FakeContext::class.java, reflection)
    }

    @Test fun readsBothAcceptedCodesWithoutGuessingTheirMeaning() {
        val reader = reader()
        assertEquals(ReflectionRead.Value(1), reader.read())
        FakeDevice.state = 2L // Reflective Number, not a guessed primitive return descriptor.
        assertEquals(ReflectionRead.Value(2), reader.read())
    }

    @Test fun cachesClassButReadsLiveStateEachTime() {
        var loads = 0
        val reader = reader(BydReflection { loads++; FakeDevice::class.java })
        reader.read()
        FakeDevice.state = 2
        assertEquals(ReflectionRead.Value(2), reader.read())
        assertEquals(1, loads)
    }

    @Test fun rejectsNullInstanceAndInvalidResults() {
        val reader = reader()
        for (state in listOf(null, "1", 0, 3, -1)) {
            FakeDevice.state = state
            assertReason(ReflectionFailure.INVALID_RESULT, reader.read())
        }
        FakeDevice.returnNull = true
        assertReason(ReflectionFailure.INVALID_RESULT, reader.read())
    }

    @Test fun missingClassDoesNotEscapeAndCanBeRetried() {
        var available = false
        val reader = reader(BydReflection {
            if (!available) throw ClassNotFoundException("not installed")
            FakeDevice::class.java
        })
        assertReason(ReflectionFailure.CLASS_MISSING, reader.read())
        available = true
        assertEquals(ReflectionRead.Value(1), reader.read())
    }

    @Test fun exactContextSignatureIsRequired() {
        assertReason(ReflectionFailure.METHOD_MISSING, reader(BydReflection { WrongSignature::class.java }).read())
    }

    @Test fun unwrapsPermissionDenialAndDeviceException() {
        val reader = reader()
        FakeDevice.failure = SecurityException("denied")
        assertReason(ReflectionFailure.ACCESS_DENIED, reader.read())
        FakeDevice.failure = IllegalStateException("service down")
        assertReason(ReflectionFailure.CALL_FAILED, reader.read())
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationIsNotConvertedToUnavailable() {
        val reader = reader()
        FakeDevice.failure = java.util.concurrent.CancellationException()
        reader.read()
    }

    @Test fun linkageFailureIsReported() {
        assertReason(ReflectionFailure.LINKAGE_FAILED, reader(BydReflection { throw NoClassDefFoundError("missing") }).read())
    }

    private fun assertReason(reason: ReflectionFailure, result: ReflectionRead<Int>) {
        assertTrue(result is ReflectionRead.Unavailable)
        assertEquals(reason, (result as ReflectionRead.Unavailable).reason)
    }
}
