package com.coordextractor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
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
 * MainActivity — App ka entry point.
 *
 * Responsibilities:
 * 1. SYSTEM_ALERT_WINDOW permission check/request
 * 2. MediaProjection permission request
 * 3. FloatingService start/stop
 * 4. Real-time mode toggle
 * 5. UI state observe via ViewModel
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // ─── Permission Launchers ─────────────────────────────────────────────────

    /**
     * SYSTEM_ALERT_WINDOW permission ke liye Settings screen
     */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            snackbar("⚠️ Overlay permission required to show floating button")
        }
    }

    /**
     * MediaProjection permission — screen capture ke liye
     */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            snackbar("⚠️ Screen capture permission denied")
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupUI()
        observeViewModel()
        updateStatusFromService()
    }

    override fun onResume() {
        super.onResume()
        updateStatusFromService()
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Start / Stop floating button
        binding.btnStartFloating.setOnClickListener {
            if (FloatingService.isRunning) {
                stopFloatingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Real-time mode toggle
        binding.switchRealtime.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRealtimeMode(isChecked)
            FloatingService.isRealtimeMode = isChecked
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isServiceRunning.collectLatest { running ->
                if (running) showStatusActive() else showStatusInactive()
            }
        }
    }

    private fun updateStatusFromService() {
        viewModel.setServiceRunning(FloatingService.isRunning)
    }

    // ─── Permission Flow ──────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        when {
            !Settings.canDrawOverlays(this) -> {
                // Step 1: Request overlay permission
                snackbar("Grant 'Draw over other apps' permission")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            else -> {
                // Step 2: Request MediaProjection
                requestMediaProjection()
            }
        }
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(intent)
        viewModel.setServiceRunning(true)
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingService::class.java))
        viewModel.setServiceRunning(false)
    }

    // ─── Status UI ────────────────────────────────────────────────────────────

    private fun showStatusActive() {
        binding.tvStatusTitle.text = "Active ✅"
        binding.tvStatusDesc.text = "Floating button visible on all screens"
        binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
        binding.btnStartFloating.text = "Disable Floating Button"
        binding.btnStartFloating.setIconResource(R.drawable.ic_close)
    }

    private fun showStatusInactive() {
        binding.tvStatusTitle.text = "Inactive"
        binding.tvStatusDesc.text = "Tap below to enable floating overlay"
        binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_inactive)
        binding.btnStartFloating.text = "Enable Floating Button"
        binding.btnStartFloating.setIconResource(R.drawable.ic_gps)
    }

    private fun snackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
