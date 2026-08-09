package com.deepmost.rabbitav.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.BuildConfig
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.alerts.AlertTuning
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.data.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    val volume = settings.audioVolume.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val ttsEnabled = settings.ttsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val language = settings.language.stateIn(viewModelScope, SharingStarted.Eagerly, "en")
    val syncEnabled = settings.syncEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val wrongSide = settings.wrongSideEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val incidentClip = settings.incidentClipEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tuning: StateFlow<AlertTuning> = settings.tuning.stateIn(viewModelScope, SharingStarted.Eagerly, AlertTuning())

    val syncConfigured: Boolean = BuildConfig.SYNC_BASE_URL.isNotBlank()

    fun setVolume(v: Float) = viewModelScope.launch { settings.setAudioVolume(v) }
    fun setTts(v: Boolean) = viewModelScope.launch { settings.setTtsEnabled(v) }
    fun setLanguage(lang: String) = viewModelScope.launch { settings.setLanguage(lang) }
    fun setWrongSide(v: Boolean) = viewModelScope.launch { settings.setWrongSideEnabled(v) }
    fun setIncidentClip(v: Boolean) = viewModelScope.launch { settings.setIncidentClipEnabled(v) }
    fun setTuning(t: AlertTuning) = viewModelScope.launch { settings.setTuning(t) }
    fun resetTuning() = viewModelScope.launch { settings.setTuning(AlertTuning()) }
    fun resetDeviceId() = viewModelScope.launch { settings.resetDeviceId() }

    fun setSync(context: android.content.Context, v: Boolean) = viewModelScope.launch {
        settings.setSyncEnabled(v)
        if (v) SyncWorker.schedule(context) else SyncWorker.cancel(context)
    }
}

@Composable
fun SettingsScreen(
    onOpenOemGuide: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val tts by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val sync by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val wrongSide by viewModel.wrongSide.collectAsStateWithLifecycle()
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.settings_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = RavColors.TextPrimary,
        )

        SectionHeader(stringResource(R.string.settings_audio_header))
        SettingCard {
            Text(stringResource(R.string.settings_volume), color = RavColors.TextPrimary)
            Slider(value = volume, onValueChange = { viewModel.setVolume(it) })
            ToggleRow(stringResource(R.string.settings_tts), tts) { viewModel.setTts(it) }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_language), color = RavColors.TextPrimary)
            Row {
                LangChip(stringResource(R.string.settings_language_en), language == "en") {
                    viewModel.setLanguage("en"); applyAppLocale(context, "en")
                }
                Spacer(Modifier.padding(4.dp))
                LangChip(stringResource(R.string.settings_language_hi), language == "hi") {
                    viewModel.setLanguage("hi"); applyAppLocale(context, "hi")
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_alerts_header))
        SettingCard {
            ToggleRow(stringResource(R.string.settings_wrong_side), wrongSide) { viewModel.setWrongSide(it) }
            TuningSlider(
                stringResource(R.string.settings_fcw_caution_ttc), tuning.fcwTtcCautionS, 1.8f..4f,
            ) { viewModel.setTuning(tuning.copy(fcwTtcCautionS = it)) }
            TuningSlider(
                stringResource(R.string.settings_fcw_critical_ttc), tuning.fcwTtcCriticalS, 1.0f..2.2f,
            ) { viewModel.setTuning(tuning.copy(fcwTtcCriticalS = it)) }
            TuningSlider(
                stringResource(R.string.settings_headway_advisory), tuning.headwayAdvisoryS, 0.8f..2f,
            ) { viewModel.setTuning(tuning.copy(headwayAdvisoryS = it)) }
            TuningSlider(
                stringResource(R.string.settings_headway_warning), tuning.headwayWarningS, 0.4f..0.9f,
            ) { viewModel.setTuning(tuning.copy(headwayWarningS = it)) }
            TextButton(onClick = { viewModel.resetTuning() }) {
                Text(stringResource(R.string.settings_tuning_reset))
            }
        }

        SectionHeader(stringResource(R.string.settings_privacy_header))
        SettingCard {
            if (viewModel.syncConfigured) {
                ToggleRow(stringResource(R.string.settings_sync), sync) { viewModel.setSync(context, it) }
                Text(
                    stringResource(R.string.settings_sync_desc),
                    color = RavColors.TextSecondary,
                    fontSize = 13.sp,
                )
                TextButton(onClick = { viewModel.resetDeviceId() }) {
                    Text(stringResource(R.string.settings_reset_device_id))
                }
            }
            TextButton(onClick = onOpenPrivacy) { Text(stringResource(R.string.settings_privacy_page)) }
        }

        SectionHeader(stringResource(R.string.settings_battery_header))
        SettingCard {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
            Button(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                enabled = !exempt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (exempt) R.string.battery_opt_done else R.string.battery_opt_button)
                )
            }
            OutlinedButton(onClick = onOpenOemGuide, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.oem_guide_button))
            }
        }

        SectionHeader(stringResource(R.string.settings_about_header))
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            color = RavColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/** Per-app locale on API 33+; below that, strings follow the system locale
 *  (full HI resources exist either way) — see DECISIONS.md. */
private fun applyAppLocale(context: android.content.Context, lang: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        val lm = context.getSystemService(android.app.LocaleManager::class.java)
        lm?.applicationLocales = android.os.LocaleList.forLanguageTags(lang)
    }
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = RavColors.Amber,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RavColors.Surface)
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RavColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: %.1f".format(value), color = RavColors.TextPrimary, fontSize = 14.sp)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
