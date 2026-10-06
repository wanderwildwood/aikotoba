package com.wanderwildwood.aikotoba.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * The text of the QR codes in a picture: a photograph of a sign-in page, a screenshot, a
 * transfer code shown by another phone. Read with ZXing, as kAuth reads its camera shots.
 */
object Qr {

    /** Every QR code ZXing finds in the picture at [uri], or an empty list. */
    fun read(context: Context, uri: Uri): List<String> {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // A camera's full frame is far more than a QR code needs, and decoding it whole is
            // slow on this phone. 1600 pixels on the long side still reads a dense code.
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX) {
                val scale = MAX.toFloat() / long
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        return try {
            read(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun read(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        val image = BinaryBitmap(HybridBinarizer(source))
        val several = runCatching { QRCodeMultiReader().decodeMultiple(image, hints).map { it.text } }.getOrNull()
        if (!several.isNullOrEmpty()) return several.distinct()
        return runCatching { listOf(MultiFormatReader().decode(image, hints).text) }.getOrDefault(emptyList())
    }

    private const val MAX = 1600
}
