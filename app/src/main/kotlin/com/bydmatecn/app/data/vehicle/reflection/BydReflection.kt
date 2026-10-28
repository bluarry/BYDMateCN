package com.bydmatecn.app.data.vehicle.reflection

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Failures remain observable: absent SDK, permission denial and device failure are not success. */
enum class ReflectionFailure { CLASS_MISSING, METHOD_MISSING, ACCESS_DENIED, CALL_FAILED, INVALID_RESULT, LINKAGE_FAILED }

sealed class ReflectionRead<out T> {
    data class Value<T>(val value: T) : ReflectionRead<T>()
    data class Unavailable(val reason: ReflectionFailure, val detail: String) : ReflectionRead<Nothing>()
}

/** Only public, exact-signature lookup, matching AutoTark. No overload guessing or permission changes. */
class BydReflection(private val loadClass: (String) -> Class<*> = { Class.forName(it) }) {
    private data class MethodKey(val owner: Class<*>, val name: String, val parameters: List<Class<*>>)
    private val classes = ConcurrentHashMap<String, Class<*>>()
    private val methods = ConcurrentHashMap<MethodKey, Method>()

    fun type(name: String): Class<*> = classes.computeIfAbsent(name) { loadClass(it) }

    fun invoke(owner: Class<*>, receiver: Any?, name: String, parameters: List<Class<*>>, vararg args: Any?): Any? {
        val key = MethodKey(owner, name, parameters.toList())
        val method = methods.computeIfAbsent(key) { owner.getMethod(name, *parameters.toTypedArray()) }
        return method.invoke(receiver, *args)
    }

    fun <T> read(block: () -> ReflectionRead<T>): ReflectionRead<T> = try {
        block()
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        // Never turn VM failure or coroutine cancellation from a target into a normal read failure.
        if (cause is java.util.concurrent.CancellationException) throw cause
        if (cause is Error && cause !is LinkageError) throw cause
        ReflectionRead.Unavailable(reason(cause), cause.javaClass.name + ": " + (cause.message ?: ""))
    } catch (error: LinkageError) {
        ReflectionRead.Unavailable(ReflectionFailure.LINKAGE_FAILED, error.javaClass.name + ": " + (error.message ?: ""))
    }

    private fun reason(error: Throwable): ReflectionFailure = when (error) {
        is ClassNotFoundException -> ReflectionFailure.CLASS_MISSING
        is NoSuchMethodException -> ReflectionFailure.METHOD_MISSING
        is SecurityException, is IllegalAccessException -> ReflectionFailure.ACCESS_DENIED
        is LinkageError -> ReflectionFailure.LINKAGE_FAILED
        else -> ReflectionFailure.CALL_FAILED
    }
}
