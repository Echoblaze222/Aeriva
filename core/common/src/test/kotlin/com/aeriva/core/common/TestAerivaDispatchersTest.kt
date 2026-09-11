package com.aeriva.core.common

import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertSame
import org.junit.Test

class TestAerivaDispatchersTest {

    @Test
    fun allThreeProperties_pointToTheSameDispatcher() {
        val dispatcher = StandardTestDispatcher()
        val dispatchers = TestAerivaDispatchers(dispatcher)

        assertSame(dispatcher, dispatchers.main)
        assertSame(dispatcher, dispatchers.io)
        assertSame(dispatcher, dispatchers.default)
    }
}
