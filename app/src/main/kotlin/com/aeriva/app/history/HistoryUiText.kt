package com.aeriva.app.history

import android.content.Context
import android.text.format.DateUtils
import com.aeriva.app.R
import com.aeriva.app.advisor.labelRes
import com.aeriva.core.database.NetworkStateHistoryRecord

/**
 * Copy for the "Recent changes" card. Only what Android reported at that time:
 * no duration, no quality, no judgement.
 */

/** Short local date and time, for example "Oct 27, 7:42 PM". */
fun NetworkStateHistoryRecord.timeText(context: Context): String =
    DateUtils.formatDateTime(
        context,
        recordedAt.toEpochMilli(),
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
    )

fun NetworkStateHistoryRecord.description(context: Context): String = when {
    !available -> context.getString(R.string.history_no_connection)
    validated -> context.getString(R.string.history_connected_passed, context.getString(transport.labelRes()))
    else -> context.getString(R.string.history_connected_not_passed, context.getString(transport.labelRes()))
}
