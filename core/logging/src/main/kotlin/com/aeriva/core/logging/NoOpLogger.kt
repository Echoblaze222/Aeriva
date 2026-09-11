package com.aeriva.core.logging

/**
 * Discards everything. For unit tests of classes that take [AerivaLogger]
 * as a constructor dependency (e.g.
 * [com.aeriva.network.monitor.ConnectionStateRepository]) and have
 * nothing to assert about logging -- avoids every such test hand-rolling
 * its own throwaway fake.
 */
object NoOpLogger : AerivaLogger {
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
