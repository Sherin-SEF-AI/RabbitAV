package com.deepmost.rabbitav.feature.drive

import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.core.data.repo.CalibrationRepository
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.geometry.CalibrationState
import com.deepmost.rabbitav.core.imu.HazardType
import com.deepmost.rabbitav.service.DrivePipeline
import com.deepmost.rabbitav.service.HudState
import com.deepmost.rabbitav.service.OverlayFrame
import com.deepmost.rabbitav.service.PerfStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val pipeline: DrivePipeline,
    private val settings: SettingsRepository,
    calibrationRepository: CalibrationRepository,
) : ViewModel() {

    val hud: StateFlow<HudState> = pipeline.hud
    val overlay: StateFlow<OverlayFrame> = pipeline.overlay
    val perf: StateFlow<PerfStats> = pipeline.perf

    val calibration: StateFlow<CalibrationState> = calibrationRepository.activeState
        .stateIn(viewModelScope, SharingStarted.Eagerly, CalibrationState.INVALID)

    val debugOverlayEnabled: StateFlow<Boolean> = settings.debugOverlay
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val previewEnabled: StateFlow<Boolean> = settings.previewEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isRunning: Boolean get() = pipeline.isRunning

    fun reportHazard(type: HazardType) = pipeline.reportManualHazard(type)

    fun setSyntheticSpeed(kmh: Float) = pipeline.setSyntheticSpeedKmh(kmh)

    fun attachPreview(provider: Preview.SurfaceProvider?) {
        viewModelScope.launch { pipeline.attachPreview(provider) }
    }
}
