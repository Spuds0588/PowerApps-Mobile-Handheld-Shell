package com.powerapps.handheld

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The bundled camera scanner, usable when config.json sets native.cameraScanner.
 *
 * It is an overlay inside MainActivity, never a second activity: a separate activity
 * pauses the webview, and Android may then kill it on a low-RAM handheld, which
 * reloads the Power App and loses whatever the operator had typed.
 *
 * A decoded value leaves through [BarcodeReceiver.dispatch], the same call the
 * DataWedge broadcast makes, so the page sees one scan path, not two.
 */
class ScanOverlay(private val activity: ComponentActivity) {
  private val preview = PreviewView(activity)
  private val root = FrameLayout(activity)
  private val hint = TextView(activity)
  private val torch = Button(activity)

  private val receiver = BarcodeReceiver()
  private var button: Button? = null
  private var camera: Camera? = null
  private var provider: ProcessCameraProvider? = null
  private var executor: ExecutorService? = null
  private var frames = 0
  private var lastValue: String? = null
  private var lastAt = 0L

  val isShowing: Boolean get() = root.parent != null

  // Built once: the overlay is opened and closed all shift, and re-adding a view that
  // still has its old parent throws.
  init {
    root.setBackgroundColor(Color.BLACK)
    root.addView(preview, matchParent())
    root.addView(
      statusBar(),
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM,
      ),
    )

    // Tap the preview to focus and meter, which is what an operator does when a code
    // is small or the light is bad.
    preview.setOnTouchListener { view, event ->
      if (event.action == MotionEvent.ACTION_UP) focus(event.x, event.y)
      view.performClick()
      true
    }
  }

  /** The floating 56dp trigger. A native view, so a Power App re-render cannot remove it. */
  fun attach() {
    val size = (56 * activity.resources.displayMetrics.density).toInt()
    val margin = (24 * activity.resources.displayMetrics.density).toInt()
    button = Button(activity).apply {
      text = "Scan"
      setOnClickListener { open() }
    }
    host().addView(
      button,
      FrameLayout.LayoutParams(size * 2, size, Gravity.BOTTOM or Gravity.END).apply {
        setMargins(0, 0, margin, margin)
      },
    )
  }

  /** Called from the floating button, which only exists when the flag is on. */
  fun open() {
    if (isShowing) return
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      show()
    } else {
      activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }
  }

  /** Returns false when no overlay was open, so Back can fall through to the kiosk shield. */
  fun hide(): Boolean {
    if (!isShowing) return false
    provider?.unbindAll()
    provider = null
    camera = null
    executor?.shutdown()
    executor = null
    (root.parent as? ViewGroup)?.removeView(root)
    button?.visibility = View.VISIBLE
    return true
  }

  fun onPermissionResult(requestCode: Int, granted: Boolean) {
    if (requestCode != REQUEST_CAMERA) return
    if (granted) {
      show()
    } else {
      Log.w(TAG, "[SCANNER][CAMERA] Camera permission denied by the operator.")
    }
  }

  private fun show() {
    if (isShowing) return
    host().addView(root, matchParent())
    root.bringToFront()
    button?.visibility = View.GONE
    start()
  }

  private fun focus(x: Float, y: Float) {
    val control = camera?.cameraControl ?: return
    val action = FocusMeteringAction.Builder(
      preview.meteringPointFactory.createPoint(x, y),
      FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
    ).build()
    Log.d(TAG, "[SCANNER][CAMERA] Focus requested ${x.toInt()},${y.toInt()}")
    val pending = control.startFocusAndMetering(action)
    pending.addListener({
      val ok = runCatching { pending.get().isFocusSuccessful }.getOrDefault(false)
      Log.d(TAG, "[SCANNER][CAMERA] Focus ${x.toInt()},${y.toInt()} success=$ok")
    }, ContextCompat.getMainExecutor(activity))
  }

  private fun statusBar() = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(padding(18), padding(14), padding(18), padding(26))
    setBackgroundColor(0xD9000000.toInt())

    hint.apply {
      text = "Point the camera at a barcode"
      setTextColor(Color.WHITE)
      textSize = 15f
    }
    addView(hint)

    addView(LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      // Enabled only when the camera reports a flash unit, so the control never
      // pretends to do something the hardware cannot.
      torch.apply {
        text = "Torch"
        isEnabled = false
        setOnClickListener { toggleTorch() }
      }
      addView(torch)
      addView(Button(activity).apply {
        text = "Close"
        setOnClickListener { hide() }
      })
    })
  }

  private fun toggleTorch() {
    val camera = camera ?: return
    val next = camera.cameraInfo.torchState.value != TorchState.ON
    camera.cameraControl.enableTorch(next)
    torch.text = if (next) "Torch off" else "Torch"
    Log.d(TAG, "[SCANNER][CAMERA] Torch ${if (next) "on" else "off"}")
  }

  private fun start() {
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener({
      try {
        provider = future.get().also { it.unbindAll() }
      } catch (err: Exception) {
        report("Camera unavailable: ${err.message}")
        return@addListener
      }

      val analysisExecutor = Executors.newSingleThreadExecutor().also { executor = it }
      val analysis = ImageAnalysis.Builder()
        // 1280x720 keeps a dense 1D code readable at arm's length; measure on hardware
        // before raising it, because decode time grows with the frame.
        .setResolutionSelector(
          ResolutionSelector.Builder()
            .setResolutionStrategy(
              ResolutionStrategy(
                Size(1280, 720),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
              ),
            )
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisExecutor, ::analyze) }

      val useCasePreview = Preview.Builder().build().also {
        it.setSurfaceProvider(preview.surfaceProvider)
      }

      try {
        camera = provider?.bindToLifecycle(activity, anyCamera(), useCasePreview, analysis)
      } catch (err: Exception) {
        report("Camera unavailable: ${err.message}")
        return@addListener
      }
      val flash = camera?.cameraInfo?.hasFlashUnit() == true
      torch.isEnabled = flash
      torch.text = if (flash) "Torch" else "No flash"
      if (flash) {
        Log.d(TAG, "[SCANNER][CAMERA] Preview and analysis bound")
      } else {
        Log.d(TAG, "[SCANNER][CAMERA] Preview and analysis bound; no flash unit, torch disabled")
      }
    }, ContextCompat.getMainExecutor(activity))
  }

  /** Rear lens when the device has one, otherwise whatever it does have. */
  private fun anyCamera() = CameraSelector.Builder()
    .addCameraFilter { infos ->
      listOfNotNull(
        infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: infos.firstOrNull(),
      )
    }
    .build()

  private fun analyze(image: ImageProxy) {
    try {
      frames += 1
      if (frames == 1) Log.d(TAG, "[SCANNER][CAMERA] Analysis ${image.width}x${image.height}")
      if (frames % 60 == 0) Log.d(TAG, "[SCANNER][CAMERA] Analysis alive: $frames frames")

      val plane = image.planes[0]
      val buffer = plane.buffer
      val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
      val value = BarcodeDecoder.decode(luma, image.width, image.height, plane.rowStride)
        ?: return

      // One code stays in frame for many frames; deliver it once per sighting.
      val now = System.currentTimeMillis()
      if (value == lastValue && now - lastAt < DUPLICATE_MS) return
      lastValue = value
      lastAt = now

      activity.runOnUiThread {
        Log.d(TAG, "[SCANNER][CAMERA] Decoded: $value")
        if (hide()) receiver.dispatch(value)
      }
    } finally {
      image.close()
    }
  }

  private fun report(message: String) {
    Log.w(TAG, "[SCANNER][CAMERA] $message")
    hint.text = message
  }

  // The webview is mounted into android.R.id.content after onCreate, so anything added
  // there ends up behind the page. The decor view is above it.
  private fun host() = activity.window.decorView as ViewGroup

  private fun matchParent() =
    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

  private fun padding(dp: Int) = (dp * activity.resources.displayMetrics.density).toInt()

  companion object {
    const val REQUEST_CAMERA = 4101
    private const val TAG = "POWERAPPS_SCANNER"
    private const val DUPLICATE_MS = 1500L
  }
}
