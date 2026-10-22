package com.aeriva.app

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aeriva.app.home.HomeUiState
import com.aeriva.app.home.detailRows
import com.aeriva.app.home.headlineRes
import com.aeriva.app.home.indicatorColorRes
import com.aeriva.app.home.messageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AERIVA launcher Activity and home screen.
 *
 * Lifecycle model: state is collected only between onStart and onStop. The
 * collection Job is cancelled in onStop, so no collector outlives the
 * visible Activity, and a configuration change (stop -> destroy -> create ->
 * start) simply attaches a new collector to the already-running, process
 * scoped StateFlow -- never a second platform network callback. The Activity
 * never touches ConnectivityManager or the monitor directly.
 *
 * Plain `android.app.Activity` is used on purpose: AppCompat and
 * lifecycle-runtime are not in the project's pinned dependency catalog, and
 * this screen needs neither.
 */
class MainActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private lateinit var statusIndicator: View
    private lateinit var statusHeadline: TextView
    private lateinit var statusMessage: TextView
    private lateinit var detailsCard: View
    private lateinit var detailsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.status_indicator)
        statusHeadline = findViewById(R.id.status_headline)
        statusMessage = findViewById(R.id.status_message)
        detailsCard = findViewById(R.id.details_card)
        detailsContainer = findViewById(R.id.details_container)
    }

    override fun onStart() {
        super.onStart()
        val homeState = (application as AerivaApplication).container.homeState
        collectJob?.cancel()
        collectJob = uiScope.launch {
            homeState.uiState.collect { render(it) }
        }
    }

    override fun onStop() {
        collectJob?.cancel()
        collectJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun render(state: HomeUiState) {
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
}
