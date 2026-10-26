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
  private var scanner: ScanOverlay? = null

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

    if (BuildConfig.CAMERA_SCANNER) {
      scanner = ScanOverlay(this).also { it.attach() }
    }

    // Registered when either the kiosk shield or the scanner is on: Back closes the
    // scanner first, so an operator can always get out of the preview.
    if (BuildConfig.INTERCEPT_BACK_BUTTON || BuildConfig.CAMERA_SCANNER) {
      onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (scanner?.hide() == true) return
          if (BuildConfig.INTERCEPT_BACK_BUTTON) {
            Log.d(TAG, "[SECURITY][BLOCKED] Hardware Back suppressed.")
          } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
          }
        }
      })
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
    scanner?.onPermissionResult(requestCode, granted)
  }

  private companion object {
    const val TAG = "POWERAPPS_SHELL"
  }
}
