package com.aeriva.core.common

import kotlinx.coroutines.test.TestDispatcher

/**
 * All three [AerivaDispatchers] properties pointed at the same
 * [TestDispatcher]. Lives in the `testFixtures` source set, not `main`,
 * because it depends on kotlinx-coroutines-test -- a test-only artifact
 * that must not end up on the production classpath -- and not in `test`,
 * because Phase 3B needed it from another module's test source set
 * (`network:monitor`'s measurement tests). This is exactly the migration
 * this file's own earlier comment anticipated ("if other modules need it
 * too, that is a testFixtures-source-set change"), not a new pattern.
 * Consumed via `testImplementation(testFixtures(project(":core:common")))`.
 */
class TestAerivaDispatchers(dispatcher: TestDispatcher) : AerivaDispatchers {
    override val main = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}
