package com.aeriva.core.logging

import android.util.Log

/**
 * Default [AerivaLogger] for real devices/builds. [debugBuild] controls
 * verbosity -- release builds suppress [i] entirely rather than shipping
 * routine connectivity chatter to logcat on end-user devices; [w] and [e]
 * always emit, since warnings/errors are exactly what field diagnostics
 * needs regardless of build type.
 *
 * [debugBuild] is passed in rather than read from BuildConfig here, so
 * this module stays free of a per-flavor BuildConfig dependency and each
 * app variant decides what "debug" means for itself.
 */
class AndroidLogcatLogger(private val debugBuild: Boolean) : AerivaLogger {

    override fun i(tag: String, message: String) {
        if (debugBuild) Log.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
