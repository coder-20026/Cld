package com.coordextractor.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.coordextractor.MainActivity
import com.coordextractor.R
import com.coordextractor.capture.CaptureManager
import com.coordextractor.data.repository.OCRRepository
import com.coordextractor.databinding.OverlayFloatingButtonBinding
import com.coordextractor.databinding.OverlayResultCardBinding
import com.coordextractor.domain.TextParser
import com.coordextractor.processing.ImageProcessor
import kotlinx.coroutines.*

/**
 * FloatingService — Foreground service jo floating button + result card manage karta hai.
 *
 * Architecture:
 * - WindowManager pe 2 overlay views: floating button + result card
 * - MediaProjection se screen capture
 * - Coroutines se async processing (no UI block)
 * - Single-shot + Real-time mode dono support
 */
class FloatingService : Service() {

    // ─── Companion Object ─────────────────────────────────────────────────────

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val CHANNEL_ID = "coord_extractor_overlay"
        const val NOTIFICATION_ID = 7001

        @Volatile var isRunning = false
        @Volatile var isRealtimeMode = false
    }

    // ─── Properties ───────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBinding: OverlayFloatingButtonBinding
    private var resultBinding: OverlayResultCardBinding? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureManager: CaptureManager? = null
    private val ocrRepository = OCRRepository()
    private val imageProcessor = ImageProcessor()
    private var realtimeJob: Job? = null
    private var lastCoordResult = ""

    // Touch state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val projData = it.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

            if (resultCode != Activity.RESULT_CANCELED && projData != null) {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = mgr.getMediaProjection(resultCode, projData)
                captureManager = CaptureManager(this, proj)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        realtimeJob?.cancel()
        captureManager?.release()
        ocrRepository.close()
        removeResultCard()
        if (::floatingBinding.isInitialized) {
            runCatching { windowManager.removeView(floatingBinding.root) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Floating Button Setup ─────────────────────────────────────────────────

    private fun setupFloatingButton() {
        floatingBinding = OverlayFloatingButtonBinding.inflate(LayoutInflater.from(this))

        val params = buildFloatingParams(100, 400)
        windowManager.addView(floatingBinding.root, params)
        setupTouchListener(params)

        // Entrance animation
        floatingBinding.root.apply {
            scaleX = 0f; scaleY = 0f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(350)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }
    }

    private fun buildFloatingParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x; this.y = y
    }

    // ─── Touch Handling ───────────────────────────────────────────────────────

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        floatingBinding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false

                    longPressRunnable = Runnable { onLongPress() }
                    longPressHandler.postDelayed(longPressRunnable!!, 700L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isDragging = true
                        cancelLongPress()
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(floatingBinding.root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    if (!isDragging) onFloatingClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
    }

    private fun onLongPress() {
        // Long press → dismiss floating button
        floatingBinding.root.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { stopSelf() }
            .start()
        vibrate()
    }

    // ─── Click Handling ───────────────────────────────────────────────────────

    private fun onFloatingClick() {
        if (captureManager == null) {
            showToast("Service not ready. Restart app.")
            return
        }

        if (isRealtimeMode) {
            if (realtimeJob?.isActive == true) stopRealtime() else startRealtime()
        } else {
            captureAndProcess()
        }
    }

    // ─── Single Capture ───────────────────────────────────────────────────────

    private fun captureAndProcess() {
        serviceScope.launch {
            setButtonCapturing(true)

            try {
                // Hide overlay so it doesn't appear in screenshot
                floatingBinding.root.visibility = View.INVISIBLE
                removeResultCard()
                delay(220) // Wait for frame refresh

                val bitmap = withContext(Dispatchers.IO) { captureManager!!.capture() }

                floatingBinding.root.visibility = View.VISIBLE
                setButtonCapturing(false)

                if (bitmap == null) { showToast("Capture failed"); return@launch }

                val cropped = withContext(Dispatchers.Default) {
                    imageProcessor.cropBottomRight(bitmap)
                }
                val inputImage = withContext(Dispatchers.Default) {
                    imageProcessor.toInputImage(cropped)
                }

                val rawText = ocrRepository.recognizeText(inputImage)
                val result = TextParser.extractCoordinates(rawText)

                bitmap.recycle()
                cropped.recycle()

                if (result != null) {
                    showResultCard(rawText, result.rawMatch, result.formatted, result.latitude, result.longitude)
                } else {
                    showToast("⚠️ No coordinates found in bottom-right area")
                }

            } catch (e: Exception) {
                floatingBinding.root.visibility = View.VISIBLE
                setButtonCapturing(false)
                showToast("Error: ${e.message}")
            }
        }
    }

    // ─── Real-time Mode ───────────────────────────────────────────────────────

    private fun startRealtime() {
        floatingBinding.btnFloat.setImageResource(R.drawable.ic_stop)
        lastCoordResult = ""

        realtimeJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    floatingBinding.root.post {
                        floatingBinding.root.visibility = View.INVISIBLE
                    }
                    delay(180)

                    val bitmap = captureManager!!.capture()

                    floatingBinding.root.post {
                        floatingBinding.root.visibility = View.VISIBLE
                    }

                    if (bitmap != null) {
                        val cropped = imageProcessor.cropBottomRight(bitmap)
                        val inputImage = imageProcessor.toInputImage(cropped)
                        val rawText = ocrRepository.recognizeText(inputImage)
                        val result = TextParser.extractCoordinates(rawText)
                        bitmap.recycle(); cropped.recycle()

                        if (result != null && result.formatted != lastCoordResult) {
                            lastCoordResult = result.formatted
                            withContext(Dispatchers.Main) {
                                showResultCard(rawText, result.rawMatch, result.formatted,
                                    result.latitude, result.longitude)
                            }
                        }
                    }

                    delay(1200) // 1.2s throttle
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(2000)
                }
            }
        }
    }

    private fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        floatingBinding.btnFloat.setImageResource(R.drawable.ic_gps)
    }

    // ─── Result Card ──────────────────────────────────────────────────────────

    private fun showResultCard(
        rawOcr: String, matchedText: String, coordinates: String,
        latitude: Double, longitude: Double
    ) {
        removeResultCard()

        val binding = OverlayResultCardBinding.inflate(LayoutInflater.from(this))
        resultBinding = binding

        // Populate data
        binding.tvRawText.text = rawOcr.trim().take(200)
        binding.tvMatchedText.text = matchedText
        binding.tvCoordinates.text = coordinates

        // Copy button
        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", coordinates))
            binding.btnCopy.text = "✓ Copied!"
            Handler(Looper.getMainLooper()).postDelayed({
                binding.btnCopy.text = "Copy"
            }, 2000)
        }

        // Google Maps button
        binding.btnMaps.setOnClickListener {
            val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(Location)")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                showToast("Maps app not found")
            }
        }

        // Close button
        binding.btnClose.setOnClickListener { removeResultCard() }

        // Window params (interactive overlay at bottom)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = 80 }

        windowManager.addView(binding.root, params)

        // Slide-up animation
        binding.root.apply {
            alpha = 0f; translationY = 120f
            animate().alpha(1f).translationY(0f)
                .setDuration(320).setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    private fun removeResultCard() {
        resultBinding?.let { b ->
            runCatching {
                b.root.animate().alpha(0f).translationY(80f).setDuration(200)
                    .withEndAction { runCatching { windowManager.removeView(b.root) } }
                    .start()
            }
            resultBinding = null
        }
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────────

    private fun setButtonCapturing(capturing: Boolean) {
        floatingBinding.btnFloat.apply {
            isEnabled = !capturing
            alpha = if (capturing) 0.5f else 1.0f
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CoordExtractor Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating coordinate extractor"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, FloatingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CoordExtractor Active")
            .setContentText("Tap floating button to extract coordinates")
            .setSmallIcon(R.drawable.ic_gps)
            .setContentIntent(tapIntent)
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
