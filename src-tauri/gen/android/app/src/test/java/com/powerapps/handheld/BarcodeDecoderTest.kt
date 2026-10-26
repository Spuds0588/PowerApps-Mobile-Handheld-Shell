package com.powerapps.handheld

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real decode work over real frames: ZXing's writer produces the pixels, its reader
 * has to find them again through the same YUV adapter the camera hands us. No camera
 * and no emulator involved, so this runs in CI.
 */
class BarcodeDecoderTest {
  @Test
  fun decodesCode128() = assertDecodes(BarcodeFormat.CODE_128, "MAT-4417-B")

  @Test
  fun decodesQr() = assertDecodes(BarcodeFormat.QR_CODE, "asset-42")

  @Test
  fun decodesDataMatrix() = assertDecodes(BarcodeFormat.DATA_MATRIX, "DM-1180")

  /** Camera planes are padded to a stride. A decoder that assumes stride == width fails here. */
  @Test
  fun decodesWithRowPadding() {
    val frame = frame(BarcodeFormat.CODE_128, "STRIDE-9", stridePadding = 96)
    assertEquals(
      "STRIDE-9",
      BarcodeDecoder.decode(frame.luma, frame.width, frame.height, frame.stride),
    )
  }

  @Test
  fun blankFrameDecodesToNull() {
    val width = 640
    val height = 480
    assertNull(BarcodeDecoder.decode(ByteArray(width * height) { 0xFF.toByte() }, width, height, width))
  }

  private fun assertDecodes(format: BarcodeFormat, text: String) {
    val frame = frame(format, text)
    assertEquals(text, BarcodeDecoder.decode(frame.luma, frame.width, frame.height, frame.stride))
  }

  /** Renders [text] as white-on-black luma, four pixels per module, with a quiet zone. */
  private fun frame(format: BarcodeFormat, text: String, stridePadding: Int = 0): Frame {
    val matrix: BitMatrix = MultiFormatWriter().encode(text, format, 0, 0)
    val scale = 4
    val quiet = scale * 6
    val width = matrix.width * scale + quiet * 2
    val height = matrix.height * scale + quiet * 2
    val stride = width + stridePadding
    val luma = ByteArray(stride * height) { 0xFF.toByte() }

    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        if (!matrix.get(x, y)) continue
        for (dy in 0 until scale) {
          val row = (quiet + y * scale + dy) * stride + quiet
          for (dx in 0 until scale) luma[row + x * scale + dx] = 0
        }
      }
    }
    return Frame(luma, width, height, stride)
  }

  private class Frame(val luma: ByteArray, val width: Int, val height: Int, val stride: Int)
}
