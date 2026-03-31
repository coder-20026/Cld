package com.coordextractor.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * CaptureManager — MediaProjection API use karke screen capture karta hai.
 *
 * Flow:
 * 1. ImageReader create karo (RGBA_8888 format)
 * 2. VirtualDisplay create karo via MediaProjection
 * 3. ImageReader se latest frame lo
 * 4. Frame → Bitmap convert karo
 * 5. Resources release karo
 *
 * Performance:
 * - ImageReader max 2 frames buffer rakhta hai
 * - acquireLatestImage() — sirf latest frame lena (outdated frames skip)
 * - After capture, VirtualDisplay immediately release
 */
class CaptureManager(
    context: Context,
    private val mediaProjection: MediaProjection
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val metrics = DisplayMetrics().also { m ->
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(m)
    }

    val screenWidth: Int = metrics.widthPixels
    val screenHeight: Int = metrics.heightPixels
    private val screenDensity: Int = metrics.densityDpi

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /**
     * Screen ka ek frame capture karo.
     * @return Bitmap ya null agar capture fail ho
     */
    fun capture(): Bitmap? {
        return try {
            setupCapture()
            acquireFrame()
        } catch (e: Exception) {
            null
        } finally {
            teardown()
        }
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2  // max images in queue
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CoordExtractor_Capture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    private fun acquireFrame(): Bitmap? {
        val deadline = System.currentTimeMillis() + 2500L

        while (System.currentTimeMillis() < deadline) {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                return image.use { img ->
                    val planes = img.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    // Create bitmap with correct dimensions (row padding account)
                    val rawBitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    // Crop to exact screen width if there's row padding
                    if (rawBitmap.width > screenWidth) {
                        val cropped = Bitmap.createBitmap(
                            rawBitmap, 0, 0, screenWidth, screenHeight
                        )
                        rawBitmap.recycle()
                        cropped
                    } else {
                        rawBitmap
                    }
                }
            }
            Thread.sleep(80) // 80ms poll interval
        }
        return null // Timeout
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    /**
     * MediaProjection permanently release karo (service destroy pe)
     */
    fun release() {
        teardown()
        try { mediaProjection.stop() } catch (_: Exception) {}
    }
}
