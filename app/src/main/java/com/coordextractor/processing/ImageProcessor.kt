package com.coordextractor.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage

/**
 * ImageProcessor — Screen bitmap ko OCR ke liye prepare karta hai.
 *
 * Strategy:
 * 1. Bottom-right 30% x 28% crop karo (jahan coordinates hote hain)
 * 2. Agar bahut bada hai to downscale karo (performance optimization)
 * 3. ML Kit InputImage banao
 */
class ImageProcessor {

    companion object {
        // Bottom-right ROI percentages
        private const val ROI_WIDTH_FRACTION = 0.30f   // 30% width
        private const val ROI_HEIGHT_FRACTION = 0.28f  // 28% height

        // Max dimension for OCR (pixels) — balance accuracy vs speed
        private const val MAX_OCR_DIMENSION = 1500
    }

    /**
     * Full screen bitmap se bottom-right area crop karo
     * Portrait aur Landscape dono handle karta hai
     */
    fun cropBottomRight(fullBitmap: Bitmap): Bitmap {
        val w = fullBitmap.width
        val h = fullBitmap.height

        val cropW = (w * ROI_WIDTH_FRACTION).toInt().coerceAtLeast(100)
        val cropH = (h * ROI_HEIGHT_FRACTION).toInt().coerceAtLeast(100)

        val startX = (w - cropW).coerceAtLeast(0)
        val startY = (h - cropH).coerceAtLeast(0)

        // Safe crop — dimensions check
        val safeW = minOf(cropW, w - startX)
        val safeH = minOf(cropH, h - startY)

        return Bitmap.createBitmap(fullBitmap, startX, startY, safeW, safeH)
    }

    /**
     * Bitmap ko ML Kit InputImage me convert karo.
     * Agar bada hai to downscale karo for performance.
     */
    fun toInputImage(bitmap: Bitmap): InputImage {
        val processedBitmap = downsampleIfNeeded(bitmap)
        return InputImage.fromBitmap(processedBitmap, 0)
    }

    /**
     * Downscale if any dimension exceeds MAX_OCR_DIMENSION
     */
    private fun downsampleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_OCR_DIMENSION) return bitmap

        val scale = MAX_OCR_DIMENSION.toFloat() / maxDim
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
