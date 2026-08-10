package com.deepmost.rabbitav.feature.debug

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.data.export.HazardExporter
import com.deepmost.rabbitav.core.data.log.LogRingBuffer
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.imu.ImuPipeline
import com.deepmost.rabbitav.core.inference.DelegateBenchmark
import com.deepmost.rabbitav.core.inference.ModelManager
import com.deepmost.rabbitav.service.DriveForegroundService
import com.deepmost.rabbitav.service.DriveMode
import com.deepmost.rabbitav.service.DrivePipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val pipeline: DrivePipeline,
    private val modelManager: ModelManager,
    private val settings: SettingsRepository,
    private val exporter: HazardExporter,
    private val logRingBuffer: LogRingBuffer,
    val imuPipeline: ImuPipeline,
) : ViewModel() {

    val perf = pipeline.perf
    val benchmarkReport = settings.benchmarkReport
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val debugOverlay = settings.debugOverlay
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val replayLoop = settings.replayLoop
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val activeModel = modelManager.active

    val message = MutableStateFlow("")

    init {
        viewModelScope.launch { runCatching { modelManager.load() } }
    }

    fun testVideoDir(): File = File(context.getExternalFilesDir(null), "test").apply { mkdirs() }
    fun listTestVideos(): List<File> =
        testVideoDir().listFiles { f -> f.extension.lowercase() in setOf("mp4", "mov", "mkv") }
            ?.sortedBy { it.name }.orEmpty()

    fun setDebugOverlay(v: Boolean) = viewModelScope.launch { settings.setDebugOverlay(v) }
    fun setReplayLoop(v: Boolean) = viewModelScope.launch { settings.setReplayLoop(v) }

    fun startReplay(file: File) {
        viewModelScope.launch {
            settings.setReplayVideoPath(file.absolutePath)
            DriveForegroundService.start(context, DriveMode.REPLAY, file.absolutePath)
        }
    }

    fun rerunBenchmark() {
        viewModelScope.launch {
            message.value = "benchmarking…"
            val report = pipeline.rerunBenchmark()
            message.value = if (report != null) "winner: ${report.winner}" else "benchmark failed (drive must be running)"
        }
    }

    fun importStagedModel() {
        viewModelScope.launch {
            val result = modelManager.importStaged()
            message.value = result.fold(
                onSuccess = { context.getString(R.string.debug_model_imported, it.config.name) },
                onFailure = { context.getString(R.string.debug_model_import_failed, it.message ?: "?") },
            )
        }
    }

    fun clearModelOverride() {
        viewModelScope.launch {
            modelManager.clearOverride()
            message.value = "override cleared"
        }
    }

    fun toggleImuRecording() {
        if (imuPipeline.isRecording) {
            val f = imuPipeline.stopRecording()
            message.value = if (f != null) "saved ${f.name}" else "recorder stopped"
        } else {
            val f = imuPipeline.startRecording()
            message.value = if (f != null) "recording ${f.name}" else "recorder unavailable (start a drive first)"
        }
    }

    fun incidentClips(): List<File> =
        File(context.filesDir, "incidents").listFiles { f -> f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun shareClip(file: File) {
        try {
            val intent = Intent.createChooser(exporter.shareIntent(file, "video/mp4"), file.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            message.value = "share failed: ${t.message}"
        }
    }

    fun export(kind: String) {
        viewModelScope.launch {
            try {
                val (file, mime) = withContext(Dispatchers.IO) {
                    when (kind) {
                        "geojson" -> exporter.exportGeoJson() to "application/geo+json"
                        "csv" -> exporter.exportCsv() to "text/csv"
                        else -> exporter.writeTextForShare(
                            "rabbitav_logs_${System.currentTimeMillis()}.txt",
                            logRingBuffer.exportText(),
                        ) to "text/plain"
                    }
                }
                val intent = Intent.createChooser(exporter.shareIntent(file, mime), file.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
                message.value = "export failed: ${t.message}"
            }
        }
    }
}

@Composable
fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val perf by viewModel.perf.collectAsStateWithLifecycle()
    val report by viewModel.benchmarkReport.collectAsStateWithLifecycle()
    val overlay by viewModel.debugOverlay.collectAsStateWithLifecycle()
    val loop by viewModel.replayLoop.collectAsStateWithLifecycle()
    val model by viewModel.activeModel.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var videos by remember { mutableStateOf(listOf<File>()) }
    var selectedVideo by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { videos = viewModel.listTestVideos() }

    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.debug_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = RavColors.TextPrimary,
        )
        if (message.isNotEmpty()) {
            Text(message, color = RavColors.Green, fontSize = 14.sp)
        }

        Section(stringResource(R.string.debug_source_header)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedVideo == null, onClick = { selectedVideo = null })
                Text(stringResource(R.string.debug_source_live), color = RavColors.TextPrimary)
            }
            if (videos.isEmpty()) {
                Text(
                    stringResource(R.string.debug_video_none),
                    color = RavColors.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            for (video in videos) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedVideo == video, onClick = { selectedVideo = video })
                    Text(video.name, color = RavColors.TextPrimary, fontSize = 14.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.debug_video_loop),
                    color = RavColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = loop, onCheckedChange = { viewModel.setReplayLoop(it) })
            }
            Button(
                onClick = {
                    val v = selectedVideo
                    if (v != null) viewModel.startReplay(v)
                    else DriveForegroundService.start(context, DriveMode.FULL_ADAS)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selectedVideo != null) "Start replay: ${selectedVideo?.name}" else "Start live drive")
            }
        }

        Section(stringResource(R.string.debug_perf_header)) {
            Mono("model  ${perf.modelName} @${perf.inputSize}")
            Mono("delegate  ${perf.delegate}  gov ${perf.governorLevel}")
            Mono("inference p50 %.1f ms  p90 %.1f ms".format(perf.p50Ms, perf.p90Ms))
            Mono("detector %.1f fps  camera %.1f fps  drop %.0f%%".format(perf.detectorFps, perf.cameraFps, perf.dropRatio * 100))
            Mono("memory %.0f MB  thermal %.2f  batt %.1f°C".format(perf.totalMemMb, perf.thermalPressure, perf.batteryTempC))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.debug_overlay_toggle),
                    color = RavColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = overlay, onCheckedChange = { viewModel.setDebugOverlay(it) })
            }
            Text(
                stringResource(R.string.debug_thermal_hint),
                color = RavColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Section(stringResource(R.string.debug_bench_header)) {
            val r = report
            if (r == null) {
                Text(stringResource(R.string.debug_bench_none), color = RavColors.TextSecondary, fontSize = 13.sp)
            } else {
                Mono("model ${r.modelName} → winner ${r.winner}")
                for (res in r.results) {
                    Mono(
                        if (res.ok) "%-8s p50 %6.1f ms  p90 %6.1f ms  (%d det)".format(res.kind, res.p50Ms, res.p90Ms, res.detections)
                        else "%-8s DISQUALIFIED: %s".format(res.kind, res.reason)
                    )
                }
            }
            OutlinedButton(onClick = { viewModel.rerunBenchmark() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_bench_rerun))
            }
        }

        Section(stringResource(R.string.debug_model_header)) {
            val m = model
            if (m != null) {
                Mono(stringResource(R.string.debug_model_active, m.config.name, m.source.name))
                Mono(stringResource(R.string.debug_model_caps, m.config.capabilities.toString()))
            }
            Text(
                stringResource(R.string.debug_model_staging_hint),
                color = RavColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                Button(onClick = { viewModel.importStagedModel() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_model_import), fontSize = 13.sp)
                }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { viewModel.clearModelOverride() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_model_clear), fontSize = 13.sp)
                }
            }
        }

        Section(stringResource(R.string.debug_imu_header)) {
            Mono(
                stringResource(
                    R.string.debug_imu_gyro,
                    if (viewModel.imuPipeline.hasGyro) "yes" else "no",
                    viewModel.imuPipeline.measuredRateHz,
                )
            )
            ImuTrace(viewModel)
            Button(onClick = { viewModel.toggleImuRecording() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (viewModel.imuPipeline.isRecording) R.string.debug_imu_record_stop
                        else R.string.debug_imu_record_start
                    )
                )
            }
        }

        Section(stringResource(R.string.debug_clips_header)) {
            var clips by remember { mutableStateOf(listOf<File>()) }
            LaunchedEffect(message) { clips = viewModel.incidentClips() }
            if (clips.isEmpty()) {
                Text(
                    stringResource(R.string.debug_clips_none),
                    color = RavColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
            for (clip in clips.take(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${clip.name}  ·  %.1f MB".format(clip.length() / 1048576.0),
                        color = RavColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { viewModel.shareClip(clip) }) {
                        Text(stringResource(R.string.debug_clips_share), fontSize = 12.sp)
                    }
                }
            }
        }

        Section(stringResource(R.string.debug_export_header)) {
            Row {
                OutlinedButton(onClick = { viewModel.export("geojson") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_export_geojson), fontSize = 12.sp)
                }
                Spacer(Modifier.padding(3.dp))
                OutlinedButton(onClick = { viewModel.export("csv") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_export_csv), fontSize = 12.sp)
                }
                Spacer(Modifier.padding(3.dp))
                OutlinedButton(onClick = { viewModel.export("logs") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.debug_export_logs), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ImuTrace(viewModel: DebugViewModel) {
    var trace by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(Unit) {
        while (true) {
            trace = viewModel.imuTrace()
            kotlinx.coroutines.delay(200)
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RavColors.Background)
    ) {
        if (trace.isEmpty()) return@Canvas
        val mid = size.height / 2f
        val scale = size.height / 2f / 6f // ±6 m/s² full scale
        val dx = size.width / trace.size
        for (i in 1 until trace.size) {
            drawLine(
                color = RavColors.Green,
                start = Offset((i - 1) * dx, mid - trace[i - 1] * scale),
                end = Offset(i * dx, mid - trace[i] * scale),
                strokeWidth = 2f,
            )
        }
        drawLine(RavColors.SurfaceHigh, Offset(0f, mid), Offset(size.width, mid), 1f)
    }
}

private fun DebugViewModel.imuTrace(): FloatArray = imuPipeline.traceSnapshot()

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        color = RavColors.Amber,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RavColors.Surface)
            .padding(12.dp)
    ) { content() }
}

@Composable
private fun Mono(text: String) {
    Text(text, color = RavColors.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
}
