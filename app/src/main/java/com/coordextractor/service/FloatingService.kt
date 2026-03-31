package com.coordextractor.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.coordextractor.MainActivity
import com.coordextractor.R
import com.coordextractor.capture.CaptureManager
import com.coordextractor.data.repository.OCRRepository
import com.coordextractor.databinding.OverlayFloatingButtonBinding
import com.coordextractor.databinding.OverlayResultCardBinding
import com.coordextractor.domain.TextParser
import com.coordextractor.processing.ImageProcessor
import kotlinx.coroutines.*

class FloatingService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE     = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val CHANNEL_ID            = "coord_extractor_overlay"
        const val NOTIFICATION_ID       = 7001

        @Volatile var isRunning      = false
        @Volatile var isRealtimeMode = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingBinding: OverlayFloatingButtonBinding
    private var resultBinding: OverlayResultCardBinding? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureManager: CaptureManager? = null
    private val ocrRepository = OCRRepository()
    private val imageProcessor = ImageProcessor()
    private var realtimeJob: Job? = null
    private var lastCoord = ""

    // Touch
    private var iX = 0; private var iY = 0
    private var iTX = 0f; private var iTY = 0f
    private var dragging = false
    private val lpHandler = Handler(Looper.getMainLooper())
    private var lpRunnable: Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val projData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        // ── CRITICAL: startForeground() with mediaProjection type ──────────
        // Must be called BEFORE getMediaProjection() on Android 14+
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // ──────────────────────────────────────────────────────────────────

        // Now safe to call getMediaProjection()
        if (resultCode == Activity.RESULT_OK && projData != null) {
            try {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj: MediaProjection = mgr.getMediaProjection(resultCode, projData)
                captureManager = CaptureManager(this, proj)
            } catch (e: Exception) {
                showToast("Capture init failed: ${e.message}")
            }
        }

        // Show floating button after foreground is established
        if (!::floatingBinding.isInitialized) {
            setupFloatingButton()
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

    // ── Floating Button ───────────────────────────────────────────────────────

    private fun setupFloatingButton() {
        floatingBinding = OverlayFloatingButtonBinding.inflate(LayoutInflater.from(this))
        val params = makeParams(80, 400)
        windowManager.addView(floatingBinding.root, params)
        setupTouch(params)
        floatingBinding.root.apply {
            scaleX = 0f; scaleY = 0f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(350).setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    private fun makeParams(x: Int, y: Int) = WindowManager.LayoutParams(
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
                    iX = params.x; iY = params.y
                    iTX = ev.rawX; iTY = ev.rawY
                    dragging = false
                    lpRunnable = Runnable { onLongPress() }
                    lpHandler.postDelayed(lpRunnable!!, 700L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - iTX).toInt()
                    val dy = (ev.rawY - iTY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        dragging = true; cancelLp()
                    }
                    if (dragging) {
                        params.x = iX + dx; params.y = iY + dy
                        runCatching { windowManager.updateViewLayout(floatingBinding.root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLp()
                    if (!dragging) onFabClick()
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
        if (captureManager == null) { showToast("⏳ Capture not ready"); return }
        if (isRealtimeMode) {
            if (realtimeJob?.isActive == true) stopRealtime() else startRealtime()
        } else {
            doCapture()
        }
    }

    // ── Single Capture ────────────────────────────────────────────────────────

    private fun doCapture() {
        serviceScope.launch {
            setBtnEnabled(false)
            floatingBinding.root.visibility = View.INVISIBLE
            removeResultCard()
            delay(220)
            try {
                val bmp = withContext(Dispatchers.IO) { captureManager!!.capture() }
                floatingBinding.root.visibility = View.VISIBLE
                setBtnEnabled(true)
                if (bmp == null) { showToast("Capture failed — try again"); return@launch }
                val cropped = withContext(Dispatchers.Default) { imageProcessor.cropBottomRight(bmp) }
                val img     = withContext(Dispatchers.Default) { imageProcessor.toInputImage(cropped) }
                val raw     = ocrRepository.recognizeText(img)
                val result  = TextParser.extractCoordinates(raw)
                bmp.recycle(); cropped.recycle()
                if (result != null)
                    showResultCard(raw, result.rawMatch, result.formatted, result.latitude, result.longitude)
                else
                    showToast("⚠️ No coordinates found in bottom-right area")
            } catch (e: Exception) {
                floatingBinding.root.visibility = View.VISIBLE
                setBtnEnabled(true)
                showToast("Error: ${e.message}")
            }
        }
    }

    // ── Real-time ─────────────────────────────────────────────────────────────

    private fun startRealtime() {
        floatingBinding.btnFloat.setImageResource(R.drawable.ic_stop)
        lastCoord = ""
        realtimeJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    withContext(Dispatchers.Main) { floatingBinding.root.visibility = View.INVISIBLE }
                    delay(180)
                    val bmp = captureManager!!.capture()
                    withContext(Dispatchers.Main) { floatingBinding.root.visibility = View.VISIBLE }
                    if (bmp != null) {
                        val cropped = imageProcessor.cropBottomRight(bmp)
                        val raw     = ocrRepository.recognizeText(imageProcessor.toInputImage(cropped))
                        val result  = TextParser.extractCoordinates(raw)
                        bmp.recycle(); cropped.recycle()
                        if (result != null && result.formatted != lastCoord) {
                            lastCoord = result.formatted
                            withContext(Dispatchers.Main) {
                                showResultCard(raw, result.rawMatch, result.formatted,
                                    result.latitude, result.longitude)
                            }
                        }
                    }
                    delay(1200)
                } catch (e: CancellationException) { break }
                  catch (_: Exception)              { delay(2000) }
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

    private fun setBtnEnabled(on: Boolean) {
        floatingBinding.btnFloat.apply { isEnabled = on; alpha = if (on) 1f else 0.45f }
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
            description = "Floating coordinate extractor"; setShowBadge(false)
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
