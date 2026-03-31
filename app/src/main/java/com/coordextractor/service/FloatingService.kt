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
 * FloatingService — Two-phase start (Android 14 fix)
 *
 * Phase 1 (ACTION_START_ONLY):
 *   startForeground() immediately — no MediaProjection yet
 *   Floating button appears, shows "waiting" state
 *
 * Phase 2 (ACTION_SET_PROJECTION):
 *   Receives MediaProjection token from MainActivity
 *   CaptureManager initialized — button becomes active
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_START_ONLY    = "action_start_only"
        const val ACTION_SET_PROJECTION = "action_set_projection"
        const val EXTRA_RESULT_CODE    = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val CHANNEL_ID           = "coord_extractor_overlay"
        const val NOTIFICATION_ID      = 7001

        @Volatile var isRunning       = false
        @Volatile var isRealtimeMode  = false
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var floatingBinding: OverlayFloatingButtonBinding
    private var resultBinding: OverlayResultCardBinding? = null

    // ── Core ──────────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureManager: CaptureManager? = null
    private val ocrRepository = OCRRepository()
    private val imageProcessor = ImageProcessor()
    private var realtimeJob: Job? = null
    private var lastCoordResult = ""
    private var projectionReady = false

    // ── Touch tracking ────────────────────────────────────────────────────────
    private var initX = 0; private var initY = 0
    private var initTX = 0f; private var initTY = 0f
    private var isDragging = false
    private val lpHandler = Handler(Looper.getMainLooper())
    private var lpRunnable: Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // CRITICAL: startForeground IMMEDIATELY in onCreate — before any delay
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_START_ONLY -> {
                // Phase 1: just started foreground — waiting for projection
                projectionReady = false
                setButtonEnabled(false)   // grey out until projection arrives
            }

            ACTION_SET_PROJECTION -> {
                // Phase 2: projection token received — initialize capture
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val projData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

                if (resultCode != Activity.RESULT_CANCELED && projData != null) {
                    try {
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val proj = mgr.getMediaProjection(resultCode, projData)
                        captureManager = CaptureManager(this, proj)
                        projectionReady = true
                        setButtonEnabled(true)   // now active!
                    } catch (e: Exception) {
                        showToast("Capture setup failed: ${e.message}")
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        projectionReady = false
        serviceScope.cancel()
        realtimeJob?.cancel()
        captureManager?.release()
        ocrRepository.close()
        removeResultCard()
        runCatching { windowManager.removeView(floatingBinding.root) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Floating Button ───────────────────────────────────────────────────────
    private fun setupFloatingButton() {
        floatingBinding = OverlayFloatingButtonBinding.inflate(LayoutInflater.from(this))
        val params = makeFloatingParams(80, 400)
        windowManager.addView(floatingBinding.root, params)
        setupTouch(params)

        // Entrance animation
        floatingBinding.root.apply {
            scaleX = 0f; scaleY = 0f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(350).setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    private fun makeFloatingParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    // ── Touch ─────────────────────────────────────────────────────────────────
    private fun setupTouch(params: WindowManager.LayoutParams) {
        floatingBinding.root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = ev.rawX; initTY = ev.rawY
                    isDragging = false
                    lpRunnable = Runnable { onLongPress() }
                    lpHandler.postDelayed(lpRunnable!!, 700L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - initTX).toInt()
                    val dy = (ev.rawY - initTY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        isDragging = true; cancelLp()
                    }
                    if (isDragging) {
                        params.x = initX + dx; params.y = initY + dy
                        runCatching { windowManager.updateViewLayout(floatingBinding.root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLp()
                    if (!isDragging) onFabClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelLp() { lpRunnable?.let { lpHandler.removeCallbacks(it) } }

    private fun onLongPress() {
        vibrate()
        floatingBinding.root.animate()
            .scaleX(0f).scaleY(0f).alpha(0f).setDuration(250)
            .withEndAction { stopSelf() }.start()
    }

    // ── FAB Click ─────────────────────────────────────────────────────────────
    private fun onFabClick() {
        if (!projectionReady) {
            showToast("⏳ Waiting for screen permission..."); return
        }
        if (isRealtimeMode) {
            if (realtimeJob?.isActive == true) stopRealtime() else startRealtime()
        } else {
            doSingleCapture()
        }
    }

    // ── Single Capture ────────────────────────────────────────────────────────
    private fun doSingleCapture() {
        serviceScope.launch {
            setButtonEnabled(false)
            floatingBinding.root.visibility = View.INVISIBLE
            removeResultCard()
            delay(220)

            try {
                val bmp = withContext(Dispatchers.IO) { captureManager!!.capture() }
                floatingBinding.root.visibility = View.VISIBLE
                setButtonEnabled(true)

                if (bmp == null) { showToast("Capture failed — try again"); return@launch }

                val cropped = withContext(Dispatchers.Default) { imageProcessor.cropBottomRight(bmp) }
                val img     = withContext(Dispatchers.Default) { imageProcessor.toInputImage(cropped) }
                val rawText = ocrRepository.recognizeText(img)
                val result  = TextParser.extractCoordinates(rawText)

                bmp.recycle(); cropped.recycle()

                if (result != null)
                    showResultCard(rawText, result.rawMatch, result.formatted, result.latitude, result.longitude)
                else
                    showToast("⚠️ No coordinates found in bottom-right area")

            } catch (e: Exception) {
                floatingBinding.root.visibility = View.VISIBLE
                setButtonEnabled(true)
                showToast("Error: ${e.message}")
            }
        }
    }

    // ── Real-time Mode ────────────────────────────────────────────────────────
    private fun startRealtime() {
        floatingBinding.btnFloat.setImageResource(R.drawable.ic_stop)
        lastCoordResult = ""
        realtimeJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    withContext(Dispatchers.Main) { floatingBinding.root.visibility = View.INVISIBLE }
                    delay(180)
                    val bmp = captureManager!!.capture()
                    withContext(Dispatchers.Main) { floatingBinding.root.visibility = View.VISIBLE }

                    if (bmp != null) {
                        val cropped = imageProcessor.cropBottomRight(bmp)
                        val rawText = ocrRepository.recognizeText(imageProcessor.toInputImage(cropped))
                        val result  = TextParser.extractCoordinates(rawText)
                        bmp.recycle(); cropped.recycle()

                        if (result != null && result.formatted != lastCoordResult) {
                            lastCoordResult = result.formatted
                            withContext(Dispatchers.Main) {
                                showResultCard(rawText, result.rawMatch, result.formatted,
                                    result.latitude, result.longitude)
                            }
                        }
                    }
                    delay(1200)
                } catch (e: CancellationException) { break }
                  catch (_: Exception) { delay(2000) }
            }
        }
    }

    private fun stopRealtime() {
        realtimeJob?.cancel(); realtimeJob = null
        floatingBinding.btnFloat.setImageResource(R.drawable.ic_gps)
    }

    // ── Result Card ───────────────────────────────────────────────────────────
    private fun showResultCard(raw: String, matched: String, coords: String, lat: Double, lon: Double) {
        removeResultCard()
        val b = OverlayResultCardBinding.inflate(LayoutInflater.from(this))
        resultBinding = b

        b.tvRawText.text     = raw.trim().take(200)
        b.tvMatchedText.text = matched
        b.tvCoordinates.text = coords

        b.btnCopy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("coordinates", coords))
            b.btnCopy.text = "✓ Copied!"
            Handler(Looper.getMainLooper()).postDelayed({ b.btnCopy.text = "Copy" }, 2000)
        }
        b.btnMaps.setOnClickListener {
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Location)")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { showToast("Maps app not found") }
        }
        b.btnClose.setOnClickListener { removeResultCard() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = 80 }

        windowManager.addView(b.root, params)
        b.root.apply {
            alpha = 0f; translationY = 120f
            animate().alpha(1f).translationY(0f)
                .setDuration(320).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
    }

    private fun removeResultCard() {
        resultBinding?.let { b ->
            runCatching {
                b.root.animate().alpha(0f).translationY(80f).setDuration(200)
                    .withEndAction { runCatching { windowManager.removeView(b.root) } }.start()
            }
            resultBinding = null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setButtonEnabled(enabled: Boolean) {
        floatingBinding.btnFloat.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun showToast(msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun vibrate() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(60)
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "CoordExtractor Overlay",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Floating coordinate extractor"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FloatingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CoordExtractor Active")
            .setContentText("Tap floating button to extract coordinates")
            .setSmallIcon(R.drawable.ic_gps)
            .setContentIntent(tap)
            .addAction(R.drawable.ic_close, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
