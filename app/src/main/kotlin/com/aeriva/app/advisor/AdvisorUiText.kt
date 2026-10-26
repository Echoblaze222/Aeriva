package com.aeriva.app.advisor

import android.content.Context
import com.aeriva.app.R
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.AdvisorNote

/**
 * Copy and colors for [AdvisorUiState], using the strings in
 * values/strings_advisor.xml (ADVISOR_UI_SPEC.md section 5). Argument order is
 * fixed: %1$s is the suggested network, %2$s is the current one.
 */

fun AdvisorUiState.indicatorColorRes(): Int = when (this) {
    AdvisorUiState.Absent -> R.color.advisor_indicator_idle
    AdvisorUiState.Stale -> R.color.advisor_indicator_stale
    is AdvisorUiState.Recommend -> R.color.advisor_indicator_recommend
    is AdvisorUiState.Idle -> R.color.advisor_indicator_idle
    is AdvisorUiState.SingleNetwork -> R.color.advisor_indicator_single_network
    is AdvisorUiState.NoNetwork -> R.color.advisor_indicator_no_network
    is AdvisorUiState.Insufficient -> R.color.advisor_indicator_insufficient
}

fun AdvisorUiState.headline(context: Context): String = when (this) {
    AdvisorUiState.Absent -> ""
    AdvisorUiState.Stale -> context.getString(R.string.advisor_stale_headline)
    is AdvisorUiState.Recommend ->
        context.getString(R.string.advisor_recommend_headline, target.text(context))
    is AdvisorUiState.Idle -> context.getString(R.string.advisor_idle_headline)
    is AdvisorUiState.SingleNetwork -> context.getString(R.string.advisor_single_headline)
    is AdvisorUiState.NoNetwork -> context.getString(R.string.advisor_none_headline)
    is AdvisorUiState.Insufficient -> context.getString(R.string.advisor_insufficient_headline)
}

fun AdvisorUiState.message(context: Context): String = when (this) {
    AdvisorUiState.Absent -> ""
    AdvisorUiState.Stale -> context.getString(R.string.advisor_stale_message)
    is AdvisorUiState.Recommend ->
        context.getString(R.string.advisor_recommend_message, target.text(context), current.text(context))
    is AdvisorUiState.Idle -> context.getString(R.string.advisor_idle_message)
    is AdvisorUiState.SingleNetwork -> context.getString(R.string.advisor_single_message)
    is AdvisorUiState.NoNetwork -> context.getString(
        if (blockedByPolicy) R.string.advisor_none_blocked_message else R.string.advisor_none_message
    )
    is AdvisorUiState.Insufficient -> context.getString(
        when (reason) {
            InsufficientReason.MULTIPLE_VALIDATED -> R.string.advisor_insufficient_message
            InsufficientReason.CURRENT_UNKNOWN -> R.string.advisor_insufficient_unknown_current_message
            InsufficientReason.VPN_ON_CURRENT -> R.string.advisor_insufficient_vpn_message
        }
    )
}

/**
 * Notes appended under the message, one per line, never a ranking. The VPN note
 * is left out when the message itself already says a VPN is active.
 */
fun AdvisorUiState.noteText(context: Context): String {
    val notes = when (this) {
        is AdvisorUiState.Recommend -> notes
        is AdvisorUiState.Idle -> notes
        is AdvisorUiState.SingleNetwork -> notes
        is AdvisorUiState.Insufficient -> notes
        AdvisorUiState.Absent, AdvisorUiState.Stale, is AdvisorUiState.NoNetwork -> emptySet()
    }
    val vpnAlreadyInMessage = this is AdvisorUiState.Insufficient && reason == InsufficientReason.VPN_ON_CURRENT
    return buildList {
        if (AdvisorNote.VPN_MAY_AFFECT_COMPARISON in notes && !vpnAlreadyInMessage) {
            add(context.getString(R.string.advisor_note_vpn))
        }
        if (AdvisorNote.METERED_STATUS_DIFFERS in notes) {
            add(context.getString(R.string.advisor_note_metered))
        }
    }.joinToString(separator = "\n")
}

private fun NetworkLabel.text(context: Context): String {
    val name = context.getString(transport.labelRes())
    return if (number != null) {
        context.getString(R.string.advisor_network_label_numbered, name, number)
    } else {
        name
    }
}

private fun TransportType.labelRes(): Int = when (this) {
    TransportType.WIFI -> R.string.transport_wifi
    TransportType.CELLULAR -> R.string.transport_cellular
    TransportType.ETHERNET -> R.string.transport_ethernet
    TransportType.VPN -> R.string.transport_vpn
    TransportType.OTHER -> R.string.transport_other
    TransportType.NONE -> R.string.transport_not_reported
}
