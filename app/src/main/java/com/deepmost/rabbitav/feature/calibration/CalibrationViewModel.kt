package com.deepmost.rabbitav.feature.calibration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.core.data.repo.CalibrationRepository
import com.deepmost.rabbitav.core.geometry.VehiclePreset
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Guided mount calibration (Section 5.9): vehicle preset -> stationary pitch
 * capture (3 s gravity average) -> horizon fine-tune -> verification rungs.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CalibrationRepository,
) : ViewModel() {

    data class UiState(
        val step: Int = 0,
        val preset: VehiclePreset = VehiclePreset.SEDAN,
        val customHeightM: Float = 1.30f,
        val capturing: Boolean = false,
        /** Pitch from gravity capture, radians (positive = camera looks down). */
        val capturedPitchRad: Float = Float.NaN,
        /** User adjustment from the horizon drag, radians. */
        val pitchOffsetRad: Float = 0f,
        val profileName: String = "My car",
        val saved: Boolean = false,
    ) {
        val heightM: Float get() = if (preset == VehiclePreset.CUSTOM) customHeightM else preset.cameraHeightM
        val effectivePitchRad: Float get() = (if (capturedPitchRad.isNaN()) 0f else capturedPitchRad) + pitchOffsetRad
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setStep(step: Int) = _state.value.let { _state.value = it.copy(step = step.coerceIn(0, 3)) }
    fun setPreset(preset: VehiclePreset) = _state.value.let { _state.value = it.copy(preset = preset) }
    fun setCustomHeight(h: Float) = _state.value.let { _state.value = it.copy(customHeightM = h.coerceIn(0.8f, 3.0f)) }
    fun setProfileName(name: String) = _state.value.let { _state.value = it.copy(profileName = name) }

    /** Horizon drag: vertical delta translates into a pitch offset upstream. */
    fun adjustPitchOffset(deltaRad: Float) = _state.value.let {
        _state.value = it.copy(pitchOffsetRad = (it.pitchOffsetRad + deltaRad).coerceIn(-0.35f, 0.35f))
    }

    /**
     * 3 s stationary gravity average -> camera pitch. Camera optical axis is
     * device -Z; theta = asin(-az/|a|) (positive when looking down). Verified
     * in GeometryTest against synthetic gravity vectors.
     */
    fun capturePitch() {
        if (_state.value.capturing) return
        _state.value = _state.value.copy(capturing = true)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            Timber.tag(TAG).e("no accelerometer for pitch capture")
            _state.value = _state.value.copy(capturing = false)
            return
        }
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var n = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sx += event.values[0]
                sy += event.values[1]
                sz += event.values[2]
                n++
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        viewModelScope.launch {
            delay(3000)
            sensorManager.unregisterListener(listener)
            if (n < 10) {
                _state.value = _state.value.copy(capturing = false)
                return@launch
            }
            val ax = (sx / n).toFloat()
            val ay = (sy / n).toFloat()
            val az = (sz / n).toFloat()
            val mag = sqrt(ax * ax + ay * ay + az * az)
            if (mag < 5f) {
                _state.value = _state.value.copy(capturing = false)
                return@launch
            }
            val pitch = asin((-az / mag).coerceIn(-1f, 1f))
            Timber.tag(TAG).i("pitch captured: %.2f deg (n=%d)", Math.toDegrees(pitch.toDouble()), n)
            _state.value = _state.value.copy(
                capturing = false,
                capturedPitchRad = pitch,
                pitchOffsetRad = 0f,
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            repository.saveProfile(
                name = s.profileName.ifBlank { "Vehicle" },
                preset = s.preset,
                cameraHeightM = s.heightM,
                pitchRad = s.effectivePitchRad,
                makeActive = true,
            )
            _state.value = s.copy(saved = true)
            Timber.tag(TAG).i(
                "calibration saved: %s h=%.2fm pitch=%.2fdeg",
                s.profileName, s.heightM, Math.toDegrees(s.effectivePitchRad.toDouble())
            )
            onSaved()
        }
    }

    companion object {
        private const val TAG = "RAV-Calib"
    }
}
