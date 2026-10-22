package com.aeriva.app

import android.app.Application
import com.aeriva.app.home.HomeUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-level owner of [AppContainer]. The container is created lazily on
 * first access (by [MainActivity.onCreate], on the main thread) so that
 * exactly one network monitor registration exists per process regardless of
 * how many times an Activity is created or recreated (rotation, process
 * restore, etc.).
 */
class AerivaApplication : Application() {

    /**
     * Test seam only: when set (by instrumentation tests, before launching
     * [MainActivity]) the home screen renders this state instead of the
     * real monitor-driven one, so UI behavior can be driven
     * deterministically. Always null in production; nothing in the app sets it.
     */
    @Volatile
    internal var uiStateOverride: StateFlow<HomeUiState>? = null

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }
}
