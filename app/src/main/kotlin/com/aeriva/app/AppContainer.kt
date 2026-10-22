package com.aeriva.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.aeriva.app.home.HomeStateHolder
import com.aeriva.core.logging.AndroidLogcatLogger
import com.aeriva.network.monitor.AndroidNetworkMonitor
import com.aeriva.network.monitor.ConnectionStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual composition root for :app (no DI framework exists in this
 * project, and none is admitted by this milestone).
 *
 * Ownership model:
 * - [appScope] lives for the whole process. [ConnectionStateRepository]
 *   deliberately keeps ONE shared platform registration alive for the
 *   process lifetime (`SharingStarted.Eagerly`, see its KDoc), so its scope
 *   must outlive any Activity.
 * - Activities never create monitors or repositories. They only collect
 *   [homeState], which is a read-only view over the single repository.
 *   Collection is scoped to the Activity's started window (see
 *   MainActivity), so a configuration change re-subscribes to this already
 *   running StateFlow instead of registering a second platform callback.
 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val debuggable =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val logger = AndroidLogcatLogger(debugBuild = debuggable)

    private val monitor = AndroidNetworkMonitor(context = context, logger = logger)

    val connectionState = ConnectionStateRepository(
        monitor = monitor,
        scope = appScope,
        logger = logger
    )

    /**
     * [ConnectionStateRepository.state] starts as `NetworkState.unknown(...)`
     * and the monitor emits nothing until Android reports a real event, so
     * the pre-emission seed is captured here, immediately after
     * construction, and passed to [HomeStateHolder] to tell "no report yet"
     * apart from a genuine offline report (which is structurally identical
     * to the seed apart from its timestamp). See [HomeStateHolder].
     */
    val homeState = HomeStateHolder(
        networkState = connectionState.state,
        seed = connectionState.state.value,
        scope = appScope
    )
}
