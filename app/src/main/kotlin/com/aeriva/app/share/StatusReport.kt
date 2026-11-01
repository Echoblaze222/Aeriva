package com.aeriva.app.share

/**
 * Builds the plain text a user can send to someone else (a friend, an ISP
 * helpdesk, a WhatsApp chat). Pure: the caller passes strings already resolved
 * from resources, so the layout of the report is testable without Android.
 *
 * It contains only what AERIVA shows on screen, which is what Android reported.
 * Nothing is added, measured or inferred.
 */
fun buildStatusReport(
    title: String,
    headline: String,
    message: String,
    networksTitle: String,
    networkLines: List<String>,
    footer: String
): String = buildString {
    appendLine(title)
    appendLine()
    appendLine(headline)
    appendLine(message)
    if (networkLines.isNotEmpty()) {
        appendLine()
        appendLine(networksTitle)
        networkLines.forEach { appendLine(it) }
    }
    appendLine()
    append(footer)
}
