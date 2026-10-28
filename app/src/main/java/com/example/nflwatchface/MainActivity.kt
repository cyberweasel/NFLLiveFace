package com.example.nflwatchface

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Minimal launcher so Android Studio has something to run. The real work is in the services. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "NFL score source installed.\nPick it from the watch face editor."
            gravity = Gravity.CENTER
        })
    }
}
