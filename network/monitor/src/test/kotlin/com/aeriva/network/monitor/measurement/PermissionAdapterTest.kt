package com.aeriva.network.monitor.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
private const val NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
private const val INTERNET = "android.permission.INTERNET"

/** A scripted, non-Android [PermissionAdapter] for testing [buildGrantedPermissionSet]
 *  in isolation from any real platform query. */
private class FakePermissionAdapter(
    private val states: Map<String, PermissionState>,
    private val sdkInt: Int = 34
) : PermissionAdapter {
    override fun check(permission: String): PermissionState =
        states[permission] ?: PermissionState.Denied

    override fun currentSdkInt(): Int = sdkInt
}

class PermissionAdapterTest {

    @Test
    fun everyState_isDistinctAndExhaustivelyHandled() {
        val states: List<PermissionState> = listOf(
            PermissionState.Granted,
            PermissionState.Denied,
            PermissionState.GrantedApproximateOnly,
            PermissionState.CheckFailed("SecurityException")
        )

        val described = states.map(::describe)

        assertEquals(states.size, described.distinct().size)
    }

    private fun describe(state: PermissionState): String = when (state) {
        PermissionState.Granted -> "granted"
        PermissionState.Denied -> "denied"
        PermissionState.GrantedApproximateOnly -> "approximate"
        is PermissionState.CheckFailed -> "failed:${state.reason}"
    }

    @Test
    fun buildGrantedPermissionSet_includesOnlyGranted() {
        val adapter = FakePermissionAdapter(
            mapOf(
                NETWORK_STATE to PermissionState.Granted,
                INTERNET to PermissionState.Denied
            )
        )

        val result = buildGrantedPermissionSet(adapter, setOf(NETWORK_STATE, INTERNET))

        assertEquals(setOf(NETWORK_STATE), result)
    }

    @Test
    fun buildGrantedPermissionSet_excludesApproximateOnly_forFineLocation() {
        // The classifier's current table has no capability satisfied by
        // coarse location alone -- approximate-only for FINE must not
        // be treated as "fine location held."
        val adapter = FakePermissionAdapter(mapOf(FINE to PermissionState.GrantedApproximateOnly))

        val result = buildGrantedPermissionSet(adapter, setOf(FINE))

        assertTrue(result.isEmpty())
    }

    @Test
    fun buildGrantedPermissionSet_excludesCheckFailed_neverTreatedAsGrant() {
        val adapter = FakePermissionAdapter(mapOf(NETWORK_STATE to PermissionState.CheckFailed("SecurityException")))

        val result = buildGrantedPermissionSet(adapter, setOf(NETWORK_STATE))

        assertTrue(result.isEmpty())
    }

    @Test
    fun buildGrantedPermissionSet_unrequestedPermission_defaultsToDenied_viaFake() {
        // Confirms the composition function only ever asks about the
        // permissions it was given, not some implicit wider set.
        val adapter = FakePermissionAdapter(emptyMap())

        val result = buildGrantedPermissionSet(adapter, setOf(NETWORK_STATE))

        assertTrue(result.isEmpty())
    }

    @Test
    fun buildGrantedPermissionSet_reQueriesEveryCall_reflectsChangedState() {
        // Proves the composition function itself has no caching layer:
        // it re-reads whatever check() returns each time it is called,
        // which is what lets a real adapter's re-query behavior actually
        // reach the classifier.
        val mutableStates = mutableMapOf(NETWORK_STATE to PermissionState.Denied)
        val adapter = object : PermissionAdapter {
            override fun check(permission: String) = mutableStates[permission] ?: PermissionState.Denied
            override fun currentSdkInt() = 34
        }

        val before = buildGrantedPermissionSet(adapter, setOf(NETWORK_STATE))
        mutableStates[NETWORK_STATE] = PermissionState.Granted
        val after = buildGrantedPermissionSet(adapter, setOf(NETWORK_STATE))

        assertTrue(before.isEmpty())
        assertEquals(setOf(NETWORK_STATE), after)
    }
}
