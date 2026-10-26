package com.powerapps.handheld

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shift-long kiosk ergonomics: no system bars, no accidental Back exit, no screen
 * sleep. Each behaviour follows config.json through BuildConfig, so the maker's
 * config is the only switch.
 */
class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    if (BuildConfig.KEEP_SCREEN_ON) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    if (BuildConfig.INTERCEPT_BACK_BUTTON) {
      onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          Log.d(TAG, "[SECURITY][BLOCKED] Hardware Back suppressed.")
        }
      })
    }
  }

  private companion object {
    const val TAG = "POWERAPPS_SHELL"
  }
}
