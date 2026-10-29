package com.aeriva.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.aeriva.app.advisor.AdvisorUiState
import com.aeriva.app.advisor.NetworkRow
import com.aeriva.app.advisor.detail
import com.aeriva.app.advisor.headline
import com.aeriva.app.advisor.indicatorColorRes
import com.aeriva.app.advisor.message
import com.aeriva.app.advisor.noteText
import com.aeriva.app.advisor.title
import com.aeriva.app.history.description
import com.aeriva.app.history.timeText
import com.aeriva.app.home.HomeUiState
import com.aeriva.app.home.detailRows
import com.aeriva.app.home.headlineRes
import com.aeriva.app.home.indicatorColorRes
import com.aeriva.app.home.messageRes
import com.aeriva.app.share.buildStatusReport
import com.aeriva.core.database.NetworkStateHistoryRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var advisorJob: Job? = null
    private var networksJob: Job? = null
    private var historyJob: Job? = null
    private var welcomeJob: Job? = null
    private var settingsLaunched = false

    // The latest of what the screen shows, so "Share status" reports exactly that.
    private var latestHome: HomeUiState? = null
    private var latestNetworks: List<NetworkRow> = emptyList()

    private lateinit var statusIndicator: View
    private lateinit var statusHeadline: TextView
    private lateinit var statusMessage: TextView
    private lateinit var detailsCard: View
    private lateinit var detailsContainer: LinearLayout

    private lateinit var advisorCard: View
    private lateinit var advisorIndicator: View
    private lateinit var advisorHeadline: TextView
    private lateinit var advisorMessage: TextView
    private lateinit var advisorBasis: TextView
    private lateinit var advisorNote: TextView
    private lateinit var advisorAction: TextView
    private lateinit var advisorActionError: TextView

    private lateinit var networksCard: View
    private lateinit var networksContainer: LinearLayout

    private lateinit var historyCard: View
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyClear: TextView

    private lateinit var welcomeCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.status_indicator)
        statusHeadline = findViewById(R.id.status_headline)
        statusMessage = findViewById(R.id.status_message)
        detailsCard = findViewById(R.id.details_card)
        detailsContainer = findViewById(R.id.details_container)

        advisorCard = findViewById(R.id.advisor_card)
        advisorIndicator = findViewById(R.id.advisor_indicator)
        advisorHeadline = findViewById(R.id.advisor_headline)
        advisorMessage = findViewById(R.id.advisor_message)
        advisorBasis = findViewById(R.id.advisor_basis)
        advisorNote = findViewById(R.id.advisor_note)
        advisorAction = findViewById(R.id.advisor_action)
        advisorActionError = findViewById(R.id.advisor_action_error)

        networksCard = findViewById(R.id.networks_card)
        networksContainer = findViewById(R.id.networks_container)

        historyCard = findViewById(R.id.history_card)
        historyContainer = findViewById(R.id.history_container)
        historyClear = findViewById(R.id.history_clear)

        welcomeCard = findViewById(R.id.welcome_card)

        val container = (application as AerivaApplication).container
        advisorAction.setOnClickListener { openNetworkSettings() }
        historyClear.setOnClickListener { container.networkHistory.clear() }
        findViewById<View>(R.id.welcome_dismiss).setOnClickListener { container.settings.dismissWelcome() }
        findViewById<View>(R.id.action_share).setOnClickListener { shareStatus() }
        findViewById<View>(R.id.action_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.action_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // The visible text is the label; the hint tells the user AERIVA does not
        // change the connection (ADVISOR_UI_SPEC.md section 6).
        advisorAction.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.hintText = getString(R.string.advisor_recommend_action_hint)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settingsLaunched = false
        val container = (application as AerivaApplication).container
        collectJob?.cancel()
        collectJob = uiScope.launch {
            container.homeState.uiState.collect { render(it) }
        }
        advisorJob?.cancel()
        advisorJob = uiScope.launch {
            container.advisorState.uiState.collect { renderAdvisor(it) }
        }
        networksJob?.cancel()
        networksJob = uiScope.launch {
            container.advisorState.networks.collect { renderNetworks(it) }
        }
        historyJob?.cancel()
        historyJob = uiScope.launch {
            container.networkHistory.recent.collect { renderHistory(it) }
        }
        welcomeJob?.cancel()
        welcomeJob = uiScope.launch {
            // Shown only once the stored value is known to be "not dismissed",
            // so a returning user never sees it flash.
            container.settings.welcomeDismissed.collect { dismissed ->
                welcomeCard.visibility = if (dismissed == false) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onStop() {
        collectJob?.cancel()
        collectJob = null
        advisorJob?.cancel()
        advisorJob = null
        networksJob?.cancel()
        networksJob = null
        historyJob?.cancel()
        historyJob = null
        welcomeJob?.cancel()
        welcomeJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun render(state: HomeUiState) {
        latestHome = state
        statusHeadline.setText(state.headlineRes())
        statusMessage.setText(state.messageRes())
        statusIndicator.backgroundTintList =
            ColorStateList.valueOf(getColor(state.indicatorColorRes()))

        val rows = state.detailRows()
        detailsContainer.removeAllViews()
        rows.forEach { row ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_detail_row, detailsContainer, false)
            view.findViewById<TextView>(R.id.detail_label).setText(row.label)
            view.findViewById<TextView>(R.id.detail_value).setText(row.value)
            detailsContainer.addView(view)
        }
        detailsCard.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * One row per network Android reports, in the order the holder gives
     * (default first). The card stays hidden when there is nothing to list.
     */
    private fun renderNetworks(rows: List<NetworkRow>) {
        latestNetworks = rows
        networksContainer.removeAllViews()
        rows.forEach { row ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_detail_row, networksContainer, false)
            view.findViewById<TextView>(R.id.detail_label).text = row.title(this)
            view.findViewById<TextView>(R.id.detail_value).text = row.detail(this)
            networksContainer.addView(view)
        }
        networksCard.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Newest first. The card stays hidden until something has been recorded. */
    private fun renderHistory(records: List<NetworkStateHistoryRecord>) {
        historyContainer.removeAllViews()
        records.forEach { record ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_detail_row, historyContainer, false)
            view.findViewById<TextView>(R.id.detail_label).text = record.timeText(this)
            view.findViewById<TextView>(R.id.detail_value).text = record.description(this)
            historyContainer.addView(view)
        }
        historyCard.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderAdvisor(state: AdvisorUiState) {
        if (state is AdvisorUiState.Absent) {
            advisorCard.visibility = View.GONE
            return
        }
        advisorCard.visibility = View.VISIBLE

        val recommending = state is AdvisorUiState.Recommend
        advisorCard.setBackgroundResource(
            if (recommending) R.drawable.card_background_recommend else R.drawable.card_background
        )
        advisorIndicator.backgroundTintList = ColorStateList.valueOf(getColor(state.indicatorColorRes()))
        advisorHeadline.text = state.headline(this)
        // Announce only a recommendation or a pause (decision D4). Every other
        // state updates silently because the status card already announces
        // connection changes.
        advisorHeadline.accessibilityLiveRegion =
            if (recommending || state is AdvisorUiState.Stale) {
                View.ACCESSIBILITY_LIVE_REGION_POLITE
            } else {
                View.ACCESSIBILITY_LIVE_REGION_NONE
            }
        advisorMessage.text = state.message(this)

        val notes = state.noteText(this)
        advisorNote.text = notes
        advisorNote.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        val actionVisibility = if (recommending) View.VISIBLE else View.GONE
        advisorBasis.visibility = actionVisibility
        advisorAction.visibility = actionVisibility
        // A failure line belongs to the card state it was shown for.
        advisorActionError.visibility = View.GONE
    }

    /**
     * Sends exactly what the screen shows as plain text, through the system
     * share sheet. Nothing is measured or added, and nothing is sent unless the
     * user picks a destination.
     */
    private fun shareStatus() {
        val state = latestHome ?: return
        val time = DateUtils.formatDateTime(
            this,
            System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
        )
        val report = buildStatusReport(
            title = getString(R.string.share_report_title),
            headline = getString(state.headlineRes()),
            message = getString(state.messageRes()),
            networksTitle = getString(R.string.networks_title),
            networkLines = latestNetworks.map { "${it.title(this)}: ${it.detail(this).replace("\n", ", ")}" },
            footer = getString(R.string.share_report_footer, time)
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
    }

    /**
     * Opens the system network settings (ADVISOR_UI_SPEC.md decision D5). The
     * launch returns immediately, so a second tap while it is in flight is
     * ignored. The platform warns a matching activity may not exist, so a
     * missing one shows the failure line instead of crashing.
     */
    private fun openNetworkSettings() {
        if (settingsLaunched) return
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIRELESS_SETTINGS
        }
        settingsLaunched = true
        try {
            startActivity(Intent(action))
            advisorActionError.visibility = View.GONE
        } catch (unavailable: ActivityNotFoundException) {
            settingsLaunched = false
            advisorActionError.visibility = View.VISIBLE
        }
    }
}
