package uz.namdpi.childguard

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * ImageProxy kadrini to'g'ri (tik) holатga keltiradi.
 * ImageAnalysis bergan bitmap sensor yo'nalishiда bo'ladi (ko'pincha 90/270°
 * aylangan), shuning uchun ML Kit yuzni topa olmaydi. Bu yerда rotationDegrees
 * bo'yicha aylantiramiz.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val bmp = this.toBitmap()
    val deg = imageInfo.rotationDegrees
    if (deg == 0) return bmp
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
