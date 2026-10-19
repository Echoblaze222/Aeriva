package com.aeriva.app

import android.app.Application

/**
 * Process-level owner of [AppContainer]. The container is created lazily on
 * first access (by [MainActivity.onCreate], on the main thread) so that
 * exactly one network monitor registration exists per process regardless of
 * how many times an Activity is created or recreated (rotation, process
 * restore, etc.).
 */
class AerivaApplication : Application() {

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }
}
