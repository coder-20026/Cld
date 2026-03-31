package com.coordextractor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─── UI States ───────────────────────────────────────────────────────────────

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(
        val rawOcrText: String,
        val matchedText: String,
        val coordinates: String,
        val latitude: Double,
        val longitude: Double
    ) : UiState()
    data class Error(val message: String) : UiState()
}

// ─── ViewModel ───────────────────────────────────────────────────────────────

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isRealtimeMode = MutableStateFlow(false)
    val isRealtimeMode: StateFlow<Boolean> = _isRealtimeMode.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setRealtimeMode(enabled: Boolean) {
        _isRealtimeMode.value = enabled
    }

    fun onSuccess(rawOcrText: String, matchedText: String, coordinates: String,
                  latitude: Double, longitude: Double) {
        _uiState.value = UiState.Success(rawOcrText, matchedText, coordinates, latitude, longitude)
    }

    fun onError(message: String) {
        _uiState.value = UiState.Error(message)
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }
}
