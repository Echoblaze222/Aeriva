package com.aeriva.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 4 engine/foundation reconciliation, Decision DD-5
 * (PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md Section 5.C): [NetworkState]'s
 * three observation fields (`captivePortalReported`, `vpnPresent`,
 * `blockedByDevicePolicy`) must never have a default value, so that a
 * call site which forgets to compute a real observation fails to
 * compile instead of silently reporting "false" for something never
 * actually observed. This test pins that property so a future edit
 * cannot reintroduce a default without a test failing here.
 *
 * Deliberately avoids adding a `kotlin-reflect` dependency to this
 * dependency-free module (see this module's own build.gradle.kts
 * comment, "Pure Kotlin domain models, zero dependencies") -- plain
 * `java.lang.reflect` is enough: when a Kotlin class has any
 * default-valued constructor parameter, the compiler emits an
 * additional synthetic constructor (extra bitmask + marker parameters)
 * alongside the real one, purely for Java interop. No defaults means no
 * synthetic constructor, so exactly one declared constructor is the
 * observable signal this test checks.
 */
class NetworkStateTest {

    @Test
    fun hasExactlyOneConstructor_meaningNoDefaultValuedParameters() {
        val constructors = NetworkState::class.java.declaredConstructors

        assertEquals(
            "NetworkState has ${constructors.size} declared constructors, expected " +
                "exactly 1. More than one usually means the Kotlin compiler emitted a " +
                "synthetic all-defaults-aware constructor, which means a default value " +
                "crept back onto a primary-constructor parameter (see this class's own " +
                "KDoc and Decision DD-5: a default makes \"not observed\" " +
                "indistinguishable from \"observed false\").",
            1,
            constructors.size
        )
    }
}
