package com.example.campusmarkettek.ui.verification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * ย่อรูปก่อนอัปโหลด: ด้านยาวไม่เกิน 1600 px, JPEG คุณภาพ 85 (ชัดพอให้ผู้ดูแลอ่านบัตร และเล็กกว่าเพดาน 6 MB ของ server)
 * หมุนรูปตาม EXIF ให้ตั้งตรง
 */
object ImageCompressor {
    private const val MAX_EDGE = 1600

    fun compress(source: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)

        // ลดขนาดตอนถอดรหัสก่อน (ประหยัดหน่วยความจำกับรูปจากกล้องที่ใหญ่มาก)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(
            source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalArgumentException("อ่านรูปไม่ได้")

        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longEdge
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }

        val rotation = when (
            ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        }

        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
