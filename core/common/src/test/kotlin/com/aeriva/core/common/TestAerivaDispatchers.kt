package com.aeriva.core.common

import kotlinx.coroutines.test.TestDispatcher

/**
 * All three [AerivaDispatchers] properties pointed at the same
 * [TestDispatcher]. Lives in the test source set, not main, because it
 * depends on kotlinx-coroutines-test -- a test-only artifact that must
 * not end up on the production classpath. Currently only usable within
 * this module's own tests; if other modules need it too, that is a
 * testFixtures-source-set change, not a reason to move this into main.
 */
class TestAerivaDispatchers(dispatcher: TestDispatcher) : AerivaDispatchers {
    override val main = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}
