package com.aeriva.app.probe

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DEBUG BUILD ONLY. Answers the real device questions of
 * DATA_USAGE_PHASE_SPEC.md section 6.5 on the phone it runs on, so the Data
 * Usage screen can be designed from evidence instead of assumptions:
 *
 *  - T1 to T3: what the Usage access app-op reports before and after the grant.
 *  - T6: how many apps with traffic can be turned into a name under package
 *    visibility filtering (no QUERY_ALL_PACKAGES, no queries entry).
 *  - T11: observed bucket length.
 *  - T12: how long each query takes.
 *  - T14: how far back data exists (a probe by week, not an exact limit).
 *  - T15: the foreground split Android reports for the top apps.
 *
 * It makes no network request, writes nothing to disk and keeps no data. The
 * report is text on screen that the user can share by hand. It lists the names
 * of the top apps by mobile data, so the user decides whether to share it.
 */
@Suppress("DEPRECATION")
class DataUsageProbeActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var access: TextView
    private lateinit var output: TextView
    private var lastReport = ""
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()

        val intro = TextView(this).apply {
            text = "Grant Usage access to AERIVA, come back here, then run the probe. " +
                "Nothing leaves the phone unless you press Share."
            setTextColor(Color.WHITE)
        }
        access = TextView(this).apply {
            setTextColor(Color.parseColor("#8FD3FF"))
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val openSettings = Button(this).apply {
            text = "1. Open Usage access settings"
            setOnClickListener { openUsageAccess() }
        }
        val run = Button(this).apply {
            text = "2. Run probe"
            setOnClickListener { runProbe() }
        }
        val share = Button(this).apply {
            text = "3. Share report"
            setOnClickListener { shareReport() }
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(0, padding / 2, 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(intro)
            addView(access)
            addView(openSettings)
            addView(run)
            addView(share)
            addView(output)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.BLACK)
                fitsSystemWindows = true
                addView(content)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        access.text = accessSummary()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---- access state (T1 to T3) ----

    private fun usageAccessMode(): Int {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
    }

    private fun modeName(mode: Int): String {
        val name = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
            AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
            AppOpsManager.MODE_ERRORED -> "MODE_ERRORED"
            AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT"
            4 -> "MODE_FOREGROUND"
            else -> "unknown"
        }
        return "$name ($mode)"
    }

    private fun accessSummary(): String {
        val granted = checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED
        return "Usage access app-op: ${modeName(usageAccessMode())}\nPermission check granted: $granted"
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (missing: ActivityNotFoundException) {
            output.text = "The Usage access settings screen was not found on this device."
        }
    }

    // ---- probe ----

    private fun runProbe() {
        if (running) return
        running = true
        output.text = "Running..."
        worker.execute {
            val report = try {
                buildReport()
            } catch (failure: Exception) {
                "Probe failed: ${failure::class.java.name}: ${failure.message}"
            }
            runOnUiThread {
                lastReport = report
                output.text = report
                running = false
            }
        }
    }

    private fun buildReport(): String {
        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val day = DAY_MILLIS
        lines += "AERIVA data usage probe v1"
        lines += "Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}"
        lines += accessSummary().split("\n")
        lines += ""

        val manager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val types = listOf(ConnectivityManager.TYPE_MOBILE to "Mobile", ConnectivityManager.TYPE_WIFI to "Wi-Fi")

        lines += "DEVICE TOTALS"
        for ((type, label) in types) {
            for ((name, span) in listOf("last 24 hours" to day, "last 7 days" to 7 * day, "last 30 days" to 30 * day)) {
                lines += "$label $name: " + deviceTotal(manager, type, now - span, now)
            }
        }
        lines += ""

        lines += "RETENTION PROBE (week ending N days ago, bytes seen)"
        for ((type, label) in types) {
            for (age in listOf(7L, 30L, 60L, 90L, 180L, 365L)) {
                val end = now - (age - 7L) * day
                lines += "$label week ending $age days ago: " + deviceTotal(manager, type, end - 7L * day, end)
            }
        }
        lines += ""

        lines += "MOBILE DATA BY APP, LAST 30 DAYS"
        lines += perAppSection(manager, now - 30L * day, now)
        return lines.joinToString("\n")
    }

    private fun deviceTotal(manager: NetworkStatsManager, type: Int, start: Long, end: Long): String {
        val began = System.nanoTime()
        return try {
            val bucket = manager.querySummaryForDevice(type, null, start, end)
            val millis = (System.nanoTime() - began) / 1_000_000
            "${formatBytes(bucket.rxBytes + bucket.txBytes)} " +
                "(rx ${formatBytes(bucket.rxBytes)}, tx ${formatBytes(bucket.txBytes)}) in $millis ms"
        } catch (failure: Exception) {
            "error ${failure::class.java.simpleName}: ${failure.message}"
        }
    }

    private fun perAppSection(manager: NetworkStatsManager, start: Long, end: Long): List<String> {
        val out = mutableListOf<String>()
        val began = System.nanoTime()
        // Per uid: total, foreground, default (Android's own state labels).
        val perUid = HashMap<Int, LongArray>()
        var buckets = 0
        var shortest = Long.MAX_VALUE
        var longest = 0L
        try {
            val stats = manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    buckets++
                    val length = bucket.endTimeStamp - bucket.startTimeStamp
                    if (length < shortest) shortest = length
                    if (length > longest) longest = length
                    val sums = perUid.getOrPut(bucket.uid) { LongArray(3) }
                    val bytes = bucket.rxBytes + bucket.txBytes
                    sums[0] += bytes
                    when (bucket.state) {
                        NetworkStats.Bucket.STATE_FOREGROUND -> sums[1] += bytes
                        NetworkStats.Bucket.STATE_DEFAULT -> sums[2] += bytes
                        else -> Unit
                    }
                }
            } finally {
                stats.close()
            }
        } catch (failure: Exception) {
            out += "error ${failure::class.java.simpleName}: ${failure.message}"
            return out
        }
        val millis = (System.nanoTime() - began) / 1_000_000
        out += "Query time: $millis ms, buckets: $buckets, uids with traffic: ${perUid.size}"
        if (buckets > 0) {
            out += "Bucket length seen: shortest ${shortest / 60_000} min, longest ${longest / 60_000} min"
        }

        val withTraffic = perUid.filter { it.value[0] > 0L }
        val named = withTraffic.keys.count { isSpecialUid(it).not() && resolveName(it) != null }
        val special = withTraffic.keys.count { isSpecialUid(it) }
        val unresolved = withTraffic.size - named - special
        out += "Name resolution: resolved $named, unresolved $unresolved, special uids $special"

        out += "Top 10 by bytes (foreground / default as Android labels them):"
        withTraffic.entries.sortedByDescending { it.value[0] }.take(10).forEach { (uid, sums) ->
            val label = uidLabel(uid) ?: "uid $uid (not resolved)"
            out += "$label: ${formatBytes(sums[0])} " +
                "(foreground ${formatBytes(sums[1])}, default ${formatBytes(sums[2])})"
        }
        return out
    }

    private fun isSpecialUid(uid: Int): Boolean =
        uid == NetworkStats.Bucket.UID_REMOVED || uid == NetworkStats.Bucket.UID_TETHERING

    private fun uidLabel(uid: Int): String? = when (uid) {
        NetworkStats.Bucket.UID_REMOVED -> "(removed apps)"
        NetworkStats.Bucket.UID_TETHERING -> "(tethering)"
        else -> resolveName(uid)
    }

    /** Subject to package visibility filtering: an invisible app returns null. */
    private fun resolveName(uid: Int): String? {
        val packages = packageManager.getPackagesForUid(uid) ?: return null
        for (name in packages) {
            try {
                return packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString()
            } catch (invisible: PackageManager.NameNotFoundException) {
                // try the next package that shares this uid
            }
        }
        return null
    }

    private fun formatBytes(bytes: Long): String {
        val megabytes = bytes / 1_048_576.0
        return if (megabytes >= 1024.0) {
            String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", megabytes)
        }
    }

    private fun shareReport() {
        if (lastReport.isEmpty()) {
            output.text = "Run the probe first."
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }
        startActivity(Intent.createChooser(send, "Share probe report"))
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
