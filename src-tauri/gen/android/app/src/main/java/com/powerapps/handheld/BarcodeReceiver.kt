package com.powerapps.handheld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the scan engine's broadcast intent and hands the payload to Rust.
 *
 * The intent action is substituted into AndroidManifest.xml from config.json at
 * build time, so one receiver serves Zebra DataWedge, Honeywell Enterprise Scan
 * and Datalogic fleets without code changes.
 */
class BarcodeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
    if (intent == null) return

    // Configured key first, then the OEM defaults worth knowing about.
    val barcode = intent.getStringExtra(BuildConfig.SCANNER_INTENT_EXTRA_KEY)
      ?: intent.getStringExtra("com.symbol.datawedge.data_string")
      ?: intent.getByteArrayExtra("version_1_data")?.toString(Charsets.UTF_8)
      ?: intent.getStringExtra("data")

    if (barcode.isNullOrEmpty()) {
      Log.w(TAG, "[SCANNER][WARN] Scan intent '${intent.action}' carried no known string extra.")
      return
    }

    Log.d(TAG, "[SCANNER][RECEIVED] Hardware scan captured: $barcode")
    nativeOnScan(barcode)
  }

  /** Implemented in Rust as Java_com_powerapps_handheld_BarcodeReceiver_nativeOnScan. */
  private external fun nativeOnScan(barcode: String)

  companion object {
    private const val TAG = "POWERAPPS_SCANNER"

    init {
      System.loadLibrary("powerapps_mobile_handheld_shell")
    }
  }
}
