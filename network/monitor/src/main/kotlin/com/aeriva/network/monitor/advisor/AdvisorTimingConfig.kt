package com.aeriva.network.monitor.advisor

import java.time.Duration

/**
 * Anti flapping timing, injectable per leader decision L5. The defaults are
 * the spec section 5.4 initial proposals (10 seconds, 5 minutes). They are
 * PLACEHOLDERS, not validated numbers, and are to be tuned on real devices.
 *
 * [dwell]: how long a recommendation condition must hold continuously
 * before it is shown. [cooldown]: how long after a recommendation was last
 * visible the same recommendation is not shown again. Zero is allowed and
 * disables the respective protection.
 */
data class AdvisorTimingConfig(
    val dwell: Duration = DEFAULT_DWELL,
    val cooldown: Duration = DEFAULT_COOLDOWN
) {
    init {
        require(!dwell.isNegative) { "dwell must not be negative" }
        require(!cooldown.isNegative) { "cooldown must not be negative" }
    }

    companion object {
        val DEFAULT_DWELL: Duration = Duration.ofSeconds(10)
        val DEFAULT_COOLDOWN: Duration = Duration.ofMinutes(5)
    }
}
