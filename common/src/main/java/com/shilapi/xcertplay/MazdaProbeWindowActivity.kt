package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * EZ-60 probe: opens in its own task (one instance, the flags DiPlay opens CarPlay with) without needing
 * CarPlay, so the recorder shows which half of the cockpit the car puts a new task on. Tap to close.
 */
class MazdaProbeWindowActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MzdCarAppLayout.applyWindowBackground(this)
        val message = TextView(this).apply {
            text = getString(R.string.settings_probe_window_test_body)
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(12, 17, 27))
            setOnClickListener { finish() }
        }
        setContentView(MzdCarAppLayout(this).apply { setPanelContent(message) })
        MazdaCockpitProbe.attach(this, TAG, savedInstanceState != null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MazdaCockpitProbe.configuration(this, TAG, newConfig)
    }

    override fun onDestroy() {
        MazdaCockpitProbe.detach(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "carplay-test"
    }
}
