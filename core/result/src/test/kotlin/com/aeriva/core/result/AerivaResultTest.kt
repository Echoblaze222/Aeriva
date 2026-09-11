package com.aeriva.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AerivaResultTest {

    @Test
    fun map_transformsSuccessValue() {
        val result: AerivaResult<Int> = AerivaResult.Success(2)

        val mapped = result.map { it * 10 }

        assertEquals(AerivaResult.Success(20), mapped)
    }

    @Test
    fun map_passesFailureThrough() {
        val error = AerivaError.Unsupported("no reason")
        val result: AerivaResult<Int> = AerivaResult.Failure(error)

        val mapped = result.map { it * 10 }

        assertEquals(AerivaResult.Failure(error), mapped)
    }

    @Test
    fun onSuccess_runsOnlyForSuccess() {
        var ran = false
        val result: AerivaResult<Int> = AerivaResult.Success(1)

        result.onSuccess { ran = true }

        assertTrue(ran)
    }

    @Test
    fun onSuccess_doesNotRunForFailure() {
        var ran = false
        val result: AerivaResult<Int> = AerivaResult.Failure(AerivaError.Unsupported("no reason"))

        result.onSuccess { ran = true }

        assertEquals(false, ran)
    }

    @Test
    fun onFailure_runsOnlyForFailure() {
        var captured: AerivaError? = null
        val error = AerivaError.PermissionRequired("android.permission.PACKAGE_USAGE_STATS")
        val result: AerivaResult<Int> = AerivaResult.Failure(error)

        result.onFailure { captured = it }

        assertEquals(error, captured)
    }
}
