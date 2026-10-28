package com.aeriva.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.aeriva.app.advisor.AdvisorStateHolder
import com.aeriva.app.history.NetworkHistoryTracker
import com.aeriva.app.home.HomeStateHolder
import com.aeriva.core.database.NetworkHistoryRepositoryFactory
import com.aeriva.core.logging.AndroidLogcatLogger
import com.aeriva.network.monitor.AndroidNetworkMonitor
import com.aeriva.network.monitor.ConnectionStateRepository
import com.aeriva.network.monitor.advisor.AndroidAvailableNetworksMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val homeState = HomeStateHolder(
        networkState = connectionState.state,
        seed = connectionState.state.value,
        scope = appScope
    )

    private val candidateMonitor = AndroidAvailableNetworksMonitor(context = context, logger = logger)

    val advisorState = AdvisorStateHolder(
        candidates = candidateMonitor.observe(),
        stale = candidateMonitor.stale,
        scope = appScope
    )

    val networkHistory = NetworkHistoryTracker(
        states = connectionState.state,
        repository = NetworkHistoryRepositoryFactory.create(context, logger),
        scope = appScope,
        seed = connectionState.state.value
    )
}
