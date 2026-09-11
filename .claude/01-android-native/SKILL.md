# AERIVA Android Native Skill
Build AERIVA as a production Android application using Kotlin and native Android capabilities.

## Preferred stack
Kotlin, Android SDK, AndroidX, Jetpack Compose where already used, Coroutines/Flow, Room, DataStore, WorkManager, ConnectivityManager, NetworkCapabilities, VpnService only when genuinely required, Android network statistics APIs where permitted, and Android Keystore for secrets.

Do not introduce frameworks merely because they are popular.

## Platform truth
Verify current official Android behavior for background execution, foreground services, permissions, VPN lifecycle, network callbacks, app standby, battery optimization, storage, and version-specific restrictions. If Android cannot reliably provide a requested behavior, expose the limitation instead of simulating it.

## Lifecycle
Handle process death, configuration changes, restart, network loss/recovery, reboot where applicable, permission revocation, service termination, and migrations.

## Performance
Avoid main-thread blocking, excessive polling, high-frequency sampling, unbounded collections, unnecessary recomposition, repeated database queries, and battery-draining loops.

Never claim real-device behavior from compilation alone.
