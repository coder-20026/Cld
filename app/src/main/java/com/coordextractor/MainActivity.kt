package com.coordextractor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.coordextractor.databinding.ActivityMainBinding
import com.coordextractor.presentation.viewmodel.MainViewModel
import com.coordextractor.service.FloatingService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity
 *
 * ANDROID 14 FIX — Correct order:
 * 1. Check overlay permission
 * 2. Start FloatingService as foreground (mediaProjection type) — NO projection yet
 * 3. THEN request MediaProjection permission
 * 4. Send projection result to already-running service
 *
 * "Share one app" crash fix: service must be running BEFORE createScreenCaptureIntent()
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // ── Overlay permission (Settings screen) ──────────────────────────────────
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (Settings.canDrawOverlays(this)) {
                startServiceThenRequestProjection()
            } else {
                snack("⚠️ 'Display over other apps' permission is required")
            }
        }, 300)
    }

    // ── MediaProjection permission ─────────────────────────────────────────────
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Service already running — just send it the projection token
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_SET_PROJECTION
                putExtra(FloatingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(intent)
            viewModel.setServiceRunning(true)
        } else {
            // User cancelled — stop the service we started
            stopService(Intent(this, FloatingService::class.java))
            viewModel.setServiceRunning(false)
            snack("⚠️ Screen capture permission denied")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.setServiceRunning(FloatingService.isRunning)
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    private fun setupUI() {
        binding.btnStartFloating.setOnClickListener {
            if (FloatingService.isRunning) {
                stopFloatingService()
            } else {
                checkPermissionsAndStart()
            }
        }
        binding.switchRealtime.setOnCheckedChangeListener { _, checked ->
            viewModel.setRealtimeMode(checked)
            FloatingService.isRealtimeMode = checked
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isServiceRunning.collectLatest { running ->
                if (running) showActive() else showInactive()
            }
        }
    }

    // ── Permission + Start Flow ───────────────────────────────────────────────
    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            snack("Grant 'Display over other apps' permission first")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermLauncher.launch(intent)
        } else {
            startServiceThenRequestProjection()
        }
    }

    /**
     * ANDROID 14 FIX:
     * Step 1 → Start foreground service first (it calls startForeground internally)
     * Step 2 → After small delay, request MediaProjection
     * This order prevents the "Share one app" crash.
     */
    private fun startServiceThenRequestProjection() {
        // Step 1: Start service in foreground (no projection yet)
        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_START_ONLY
        }
        startForegroundService(serviceIntent)

        // Step 2: Small delay so service finishes startForeground(), THEN show permission dialog
        Handler(Looper.getMainLooper()).postDelayed({
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }, 500)
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingService::class.java))
        viewModel.setServiceRunning(false)
    }

    // ── Status UI ─────────────────────────────────────────────────────────────
    private fun showActive() {
        binding.tvStatusTitle.text = "Active ✅"
        binding.tvStatusDesc.text = "Floating button visible on all screens"
        binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
        binding.btnStartFloating.text = "Disable Floating Button"
        binding.btnStartFloating.setIconResource(R.drawable.ic_close)
    }

    private fun showInactive() {
        binding.tvStatusTitle.text = "Inactive"
        binding.tvStatusDesc.text = "Tap below to enable floating overlay"
        binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_inactive)
        binding.btnStartFloating.text = "Enable Floating Button"
        binding.btnStartFloating.setIconResource(R.drawable.ic_gps)
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
