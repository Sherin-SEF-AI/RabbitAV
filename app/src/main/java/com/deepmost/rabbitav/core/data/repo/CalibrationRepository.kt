package com.deepmost.rabbitav.core.data.repo

import com.deepmost.rabbitav.core.data.db.CalibrationDao
import com.deepmost.rabbitav.core.data.db.CalibrationProfileEntity
import com.deepmost.rabbitav.core.geometry.CalibrationState
import com.deepmost.rabbitav.core.geometry.VehiclePreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persistence for mount calibration profiles (Section 5.9, per vehicle). */
@Singleton
class CalibrationRepository @Inject constructor(
    private val dao: CalibrationDao,
) {
    val activeState: Flow<CalibrationState> = dao.activeProfileFlow().map { it.toState() }

    suspend fun activeStateNow(): CalibrationState = dao.activeProfile().toState()

    fun profiles(): Flow<List<CalibrationProfileEntity>> = dao.profiles()

    suspend fun saveProfile(
        name: String,
        preset: VehiclePreset,
        cameraHeightM: Float,
        pitchRad: Float,
        makeActive: Boolean = true,
    ): Long {
        val now = System.currentTimeMillis()
        if (makeActive) dao.clearActive()
        return dao.insert(
            CalibrationProfileEntity(
                name = name,
                preset = preset.name,
                cameraHeightM = cameraHeightM,
                pitchRad = pitchRad,
                createdMs = now,
                updatedMs = now,
                active = makeActive,
            )
        )
    }

    suspend fun activate(id: Long) {
        val profile = requireNotNull(dao.byId(id)) { "profile $id missing" }
        dao.clearActive()
        dao.update(profile.copy(active = true, updatedMs = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun CalibrationProfileEntity?.toState(): CalibrationState =
        if (this == null) {
            CalibrationState.INVALID
        } else {
            CalibrationState(
                valid = true,
                preset = runCatching { VehiclePreset.valueOf(preset) }.getOrDefault(VehiclePreset.CUSTOM),
                cameraHeightM = cameraHeightM,
                pitchRad = pitchRad,
                profileName = name,
            )
        }
}
