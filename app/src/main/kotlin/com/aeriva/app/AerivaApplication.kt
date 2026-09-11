package com.aeriva.app

import android.app.Application

/**
 * Intentionally minimal. No UI, no DI wiring, no feature modules
 * attached yet, this module exists right now only so the project has
 * a buildable :app target while Phase 2 (Network Detection) is in
 * progress in the network:monitor / core:model modules. Screens
 * (feature modules), the AERIVA brand system, and DI composition are
 * later work, not part of this change.
 */
class AerivaApplication : Application()
