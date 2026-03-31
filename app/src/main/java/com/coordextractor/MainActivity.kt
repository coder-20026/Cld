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
 * Android 14 / 15 / 16 CORRECT ORDER:
 *
 * STEP 1 — Overlay permission check karo
 * STEP 2 — MediaProjection dialog dikhao (service start mat karo abhi)
 * STEP 3 — User "Allow" kare TAB startForegroundService() call karo
 * STEP 4 — Token service ko do, service startForeground() call kare
 *
 * Galat order (pehle service, phir permission) = crash on Android 14+
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // Overlay permission launcher
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (Settings.canDrawOverlays(this)) {
                launchProjectionDialog()          // overlay OK → projection dialog
            } else {
                snack("⚠️ 'Display over other apps' permission required")
            }
        }, 400)
    }

    // MediaProjection launcher — service sirf yahan start hota hai
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Token mile — ABHI service start karo with token
            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra(FloatingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingService.EXTRA_PROJECTION_DATA, result.data)
            }
            startForegroundService(intent)
            viewModel.setServiceRunning(true)
        } else {
            snack("⚠️ Screen capture permission denied")
        }
    }

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

    private fun setupUI() {
        binding.btnStartFloating.setOnClickListener {
            if (FloatingService.isRunning) stopService()
            else checkOverlayThenStart()
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

    private fun checkOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            snack("Grant 'Display over other apps' permission first")
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        } else {
            launchProjectionDialog()
        }
    }

    private fun launchProjectionDialog() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopService() {
        stopService(Intent(this, FloatingService::class.java))
        viewModel.setServiceRunning(false)
    }

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
