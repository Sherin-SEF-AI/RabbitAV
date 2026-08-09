package com.deepmost.rabbitav.feature.calibration

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.geometry.CalibrationState
import com.deepmost.rabbitav.core.geometry.CameraIntrinsics
import com.deepmost.rabbitav.core.geometry.GroundGeometry
import com.deepmost.rabbitav.core.geometry.VehiclePreset
import kotlin.math.atan

/**
 * Calibration wizard (Section 5.9). Steps: vehicle preset -> stationary pitch
 * capture -> horizon drag over live preview -> distance-rung verification.
 * Binds its own Preview use case to the activity lifecycle (the wizard runs
 * while the drive service is stopped).
 */
@Composable
fun CalibrationScreen(
    onDone: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.calib_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = RavColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))

        when (state.step) {
            0 -> StepVehicle(viewModel, state)
            1 -> StepPitch(viewModel, state)
            2 -> StepHorizon(viewModel, state, cameraGranted)
            3 -> StepVerify(viewModel, state, cameraGranted, onDone)
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(
                onClick = { if (state.step == 0) onDone() else viewModel.setStep(state.step - 1) },
            ) { Text(stringResource(if (state.step == 0) R.string.cancel else R.string.calib_back)) }
            if (state.step < 3) {
                Button(
                    onClick = { viewModel.setStep(state.step + 1) },
                    enabled = state.step != 1 || !state.capturedPitchRad.isNaN(),
                ) { Text(stringResource(R.string.calib_next)) }
            }
        }
    }
}

@Composable
private fun StepVehicle(viewModel: CalibrationViewModel, state: CalibrationViewModel.UiState) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.calib_step1_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RavColors.Amber)
        Text(stringResource(R.string.calib_step1_body), fontSize = 15.sp, color = RavColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        val options = listOf(
            VehiclePreset.HATCHBACK to stringResource(R.string.calib_vehicle_hatchback),
            VehiclePreset.SEDAN to stringResource(R.string.calib_vehicle_sedan),
            VehiclePreset.SUV to stringResource(R.string.calib_vehicle_suv),
            VehiclePreset.CUSTOM to stringResource(R.string.calib_vehicle_custom),
        )
        for ((preset, label) in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.preset == preset, onClick = { viewModel.setPreset(preset) })
                Text(label, color = RavColors.TextPrimary, fontSize = 16.sp)
            }
        }
        if (state.preset == VehiclePreset.CUSTOM) {
            Text(
                stringResource(R.string.calib_custom_height_label) + ": %.2f m".format(state.customHeightM),
                color = RavColors.TextSecondary,
            )
            Slider(
                value = state.customHeightM,
                onValueChange = { viewModel.setCustomHeight(it) },
                valueRange = 0.8f..2.5f,
            )
        }
    }
}

@Composable
private fun StepPitch(viewModel: CalibrationViewModel, state: CalibrationViewModel.UiState) {
    Column {
        Text(stringResource(R.string.calib_step2_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RavColors.Amber)
        Text(stringResource(R.string.calib_step2_body), fontSize = 15.sp, color = RavColors.TextSecondary)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.capturePitch() },
            enabled = !state.capturing,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text(
                if (state.capturing) stringResource(R.string.calib_step2_capturing)
                else stringResource(R.string.calib_step2_capture),
                fontSize = 17.sp,
            )
        }
        if (!state.capturedPitchRad.isNaN()) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(
                    R.string.calib_step2_done,
                    Math.toDegrees(state.capturedPitchRad.toDouble()).toFloat()
                ),
                color = RavColors.Green,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CalibrationPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                } catch (t: Throwable) {
                    timber.log.Timber.tag("RAV-Calib").e(t, "calibration preview bind failed")
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}

@Composable
private fun StepHorizon(
    viewModel: CalibrationViewModel,
    state: CalibrationViewModel.UiState,
    cameraGranted: Boolean,
) {
    Column {
        Text(stringResource(R.string.calib_step3_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RavColors.Amber)
        Text(stringResource(R.string.calib_step3_body), fontSize = 15.sp, color = RavColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        if (!cameraGranted) {
            Text(stringResource(R.string.calib_no_camera), color = RavColors.Red)
            return
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            CalibrationPreview(Modifier.fillMaxSize())
            var boxHeightPx by remember { mutableStateOf(1f) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // pixels -> pitch: dv maps through atan(dv/fy); the
                            // preview box height stands in for fy scale here,
                            // which is exact enough for a guided fine-tune.
                            val fyApprox = boxHeightPx * 1.2f
                            viewModel.adjustPitchOffset(atan(dragAmount.y / fyApprox))
                        }
                    }
            ) {
                boxHeightPx = size.height
                // horizon line for the CURRENT effective pitch
                val intr = CameraIntrinsics.fallback(size.width.toInt(), size.height.toInt())
                val geo = GroundGeometry(
                    intr,
                    CalibrationState(true, state.preset, state.heightM, state.effectivePitchRad),
                )
                val y = geo.horizonV().coerceIn(0f, size.height)
                drawLine(
                    color = RavColors.Amber,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 4f,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "pitch %.1f°".format(Math.toDegrees(state.effectivePitchRad.toDouble())),
            color = RavColors.TextSecondary,
        )
    }
}

@Composable
private fun StepVerify(
    viewModel: CalibrationViewModel,
    state: CalibrationViewModel.UiState,
    cameraGranted: Boolean,
    onDone: () -> Unit,
) {
    Column {
        Text(stringResource(R.string.calib_step4_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RavColors.Amber)
        Text(stringResource(R.string.calib_step4_body), fontSize = 15.sp, color = RavColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        if (cameraGranted) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                CalibrationPreview(Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val intr = CameraIntrinsics.fallback(size.width.toInt(), size.height.toInt())
                    val geo = GroundGeometry(
                        intr,
                        CalibrationState(true, state.preset, state.heightM, state.effectivePitchRad),
                    )
                    val labelPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 34f
                        isAntiAlias = true
                        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                    for (z in listOf(10f, 25f, 50f)) {
                        val v = geo.vForDistance(z)
                        if (v < 0f || v > 1.05f) continue
                        val y = (v * size.height).coerceIn(0f, size.height)
                        drawLine(
                            color = RavColors.Green,
                            start = Offset(size.width * 0.2f, y),
                            end = Offset(size.width * 0.8f, y),
                            strokeWidth = 4f,
                        )
                        drawContext.canvas.nativeCanvas.drawText("${z.toInt()} m", size.width * 0.82f, y + 12f, labelPaint)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        var name by remember(state.profileName) { mutableStateOf(state.profileName) }
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                viewModel.setProfileName(it)
            },
            label = { Text(stringResource(R.string.calib_profile_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { viewModel.save(onDone) },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Text(stringResource(R.string.calib_save), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
