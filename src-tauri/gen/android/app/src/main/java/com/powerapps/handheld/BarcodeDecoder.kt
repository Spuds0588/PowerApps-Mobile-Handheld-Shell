package com.powerapps.handheld

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * One camera frame in, one barcode out. ZXing core, no Play Services, no model
 * download, so it works on an offline handheld.
 *
 * The formats are the ones Zebra and Honeywell engines read by default. A reader
 * per call keeps this object stateless: the analysis executor is single threaded
 * today, but nothing here assumes that.
 */
object BarcodeDecoder {
  private val FORMATS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(
      BarcodeFormat.CODE_128,
      BarcodeFormat.CODE_39,
      BarcodeFormat.EAN_13,
      BarcodeFormat.UPC_A,
      BarcodeFormat.ITF,
      BarcodeFormat.QR_CODE,
      BarcodeFormat.DATA_MATRIX,
      BarcodeFormat.PDF_417,
      BarcodeFormat.AZTEC,
    ),
    DecodeHintType.TRY_HARDER to true,
  )

  /**
   * @param luma the first plane of a YUV_420_888 frame, one byte per pixel
   * @param rowStride the plane's stride, which is usually wider than [width]
   */
  fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
    val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
    val reader = MultiFormatReader().apply { setHints(FORMATS) }
    return try {
      reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
      null
    } finally {
      reader.reset()
    }
  }
}
