package com.aeriva.app

import android.app.Activity
import android.os.Bundle

/** Plain language explanation of what AERIVA shows and what it does not do. Static text. */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
    }
}
