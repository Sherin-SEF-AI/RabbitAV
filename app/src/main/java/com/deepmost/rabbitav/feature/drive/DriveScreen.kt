package com.deepmost.rabbitav.feature.drive

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.alerts.ActiveAlert
import com.deepmost.rabbitav.core.alerts.AlertLevel
import com.deepmost.rabbitav.core.imu.HazardType
import com.deepmost.rabbitav.service.AdasInactiveReason
import com.deepmost.rabbitav.service.DriveForegroundService
import com.deepmost.rabbitav.service.DriveMode
import com.deepmost.rabbitav.service.HudState

/**
 * The drive HUD (Section 5.9): giant alert banner, speed, headway bar, manual
 * report chips, calibration status, optional live preview + debug overlay,
 * single-tap screen-dim mode. Pure observer of the pipeline.
 */
@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    onNavigateToCalibration: () -> Unit,
) {
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val perf by viewModel.perf.collectAsStateWithLifecycle()
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    val debugOverlay by viewModel.debugOverlayEnabled.collectAsStateWithLifecycle()
    val previewPref by viewModel.previewEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var dimmed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RavColors.Background)
    ) {
        if (hud.running && hud.mode == DriveMode.FULL_ADAS && previewPref && !dimmed) {
            CameraPreview(viewModel)
        }

        if (hud.running && debugOverlay && !dimmed) {
            DebugOverlay(
                overlay = overlay,
                perf = perf,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // FCW-critical full-screen red flash (Section 5.5)
        val critical = hud.topAlert?.level == AlertLevel.CRITICAL
        if (critical && hud.running) {
            val flash = rememberInfiniteTransition(label = "flash")
            val alpha by flash.animateFloat(
                initialValue = 0.15f,
                targetValue = 0.55f,
                animationSpec = infiniteRepeatable(tween(220, easing = LinearEasing), RepeatMode.Reverse),
                label = "flashAlpha",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RavColors.Red.copy(alpha = alpha))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            AlertBanner(hud.topAlert, hud)

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SpeedCluster(hud)
                Spacer(Modifier.weight(1f))
                if (hud.running) HeadwayBar(hud)
            }

            Spacer(Modifier.weight(1f))

            if (!hud.running) {
                StartControls(context, calibration.valid, onNavigateToCalibration)
            } else {
                if (hud.mode == DriveMode.REPLAY) {
                    SyntheticSpeedSlider(hud, viewModel)
                }
                AdasStatusLine(hud, onNavigateToCalibration)
                Spacer(Modifier.height(8.dp))
                ReportChips(viewModel, hud)
            }
        }

        // Screen-dim mode: black screen, audio-only, small status strip.
        if (dimmed && hud.running) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { dimmed = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = "${hud.speedKmh.toInt()} ${stringResource(R.string.drive_speed_unit)} · " +
                        stringResource(R.string.hud_dim_active),
                    color = RavColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )
                // critical alerts still flash through the dim
                if (critical) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(RavColors.Red.copy(alpha = 0.4f))
                    )
                }
            }
        } else if (hud.running) {
            // tap target to enter dim mode: the whole empty HUD background
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { dimmed = true }
            ) {}
        }
    }
}

@Composable
private fun CameraPreview(viewModel: DriveViewModel) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view -> viewModel.attachPreview(view.surfaceProvider) },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) {
        onDispose { viewModel.attachPreview(null) }
    }
}

@Composable
private fun AlertBanner(alert: ActiveAlert?, hud: HudState) {
    val (bg, fg) = when (alert?.level) {
        AlertLevel.CRITICAL -> RavColors.Red to Color.White
        AlertLevel.WARNING -> Color(0xFFFF6A00) to Color.Black
        AlertLevel.CAUTION -> RavColors.CautionYellow to Color.Black
        AlertLevel.ADVISORY -> RavColors.Blue to Color.Black
        else -> RavColors.Surface to RavColors.TextSecondary
    }
    val text = alert?.let { hudAlertText(it) }
        ?: if (hud.running && hud.adasActive) stringResource(R.string.hud_adas_active) else " "
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = fg,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (alert != null && !alert.distanceM.isNaN()) {
                Text(
                    text = "%.0f m".format(alert.distanceM),
                    color = fg.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun hudAlertText(alert: ActiveAlert): String {
    val res = when (alert.hudTextKey) {
        "alert_fcw_critical" -> R.string.alert_fcw_critical
        "alert_fcw_caution" -> R.string.alert_fcw_caution
        "alert_headway_warning" -> R.string.alert_headway_warning
        "alert_headway_advisory" -> R.string.alert_headway_advisory
        "alert_vru" -> R.string.alert_vru
        "alert_animal" -> R.string.alert_animal
        "alert_wrong_side" -> R.string.alert_wrong_side
        "alert_breaker_ahead" -> R.string.alert_breaker_ahead
        "alert_pothole_ahead" -> R.string.alert_pothole_ahead
        "alert_water_ahead" -> R.string.alert_water_ahead
        "alert_rough_ahead" -> R.string.alert_rough_ahead
        "alert_breaker_visual" -> R.string.alert_breaker_visual
        "alert_pothole_visual" -> R.string.alert_pothole_visual
        else -> R.string.hud_adas_active
    }
    return stringResource(res)
}

@Composable
private fun SpeedCluster(hud: HudState) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (hud.speedValid) "${hud.speedKmh.toInt()}" else "--",
                color = if (hud.speedValid) RavColors.TextPrimary else RavColors.TextSecondary,
                fontSize = 76.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.drive_speed_unit),
                    color = RavColors.TextSecondary,
                    fontSize = 18.sp,
                )
                if (!hud.speedValid && hud.running) {
                    Text(
                        stringResource(R.string.drive_no_gps),
                        color = RavColors.Red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (hud.running) {
            Text(
                text = stringResource(R.string.hud_hazards_label) + ": ${hud.hazardsThisTrip}" +
                    "  ·  %.1f km".format(hud.tripDistanceKm),
                color = RavColors.TextSecondary,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun HeadwayBar(hud: HudState) {
    val headway = hud.leadHeadwayS
    if (!headway.isFinite()) return
    val fraction = (headway / 3f).coerceIn(0.05f, 1f)
    val color = when {
        headway < 0.6f -> RavColors.Red
        headway < 1.0f -> RavColors.CautionYellow
        else -> RavColors.Green
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = stringResource(R.string.hud_headway_label) + " %.1f s".format(headway),
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier
                .width(140.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(RavColors.SurfaceHigh)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(color)
            )
        }
        if (!hud.leadDistanceM.isNaN()) {
            Text(
                text = "%.0f m".format(hud.leadDistanceM),
                color = RavColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun AdasStatusLine(hud: HudState, onNavigateToCalibration: () -> Unit) {
    val text = when (hud.adasInactiveReason) {
        AdasInactiveReason.NONE -> return
        AdasInactiveReason.NO_CALIBRATION -> stringResource(R.string.hud_calibration_needed)
        AdasInactiveReason.NO_GPS -> stringResource(R.string.hud_adas_off_no_gps)
        AdasInactiveReason.LOW_SPEED -> stringResource(R.string.hud_adas_off_low_speed)
        AdasInactiveReason.GOVERNOR_SUSPENDED -> stringResource(R.string.hud_adas_off_governor)
        AdasInactiveReason.BENCHMARKING -> stringResource(R.string.hud_adas_off_benchmarking)
        AdasInactiveReason.POCKET_MODE -> stringResource(R.string.hud_adas_off_pocket)
        AdasInactiveReason.NOT_RUNNING -> return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RavColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = RavColors.Amber, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (hud.adasInactiveReason == AdasInactiveReason.NO_CALIBRATION) {
            OutlinedButton(onClick = onNavigateToCalibration) {
                Text(stringResource(R.string.hud_calibrate_now))
            }
        }
    }
}

@Composable
private fun ReportChips(viewModel: DriveViewModel, hud: HudState) {
    // Three large one-tap chips (Section 5.7); min 64 dp touch targets.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReportChip(stringResource(R.string.hud_report_pothole), Modifier.weight(1f)) {
            viewModel.reportHazard(HazardType.POTHOLE)
        }
        ReportChip(stringResource(R.string.hud_report_breaker), Modifier.weight(1f)) {
            viewModel.reportHazard(HazardType.SPEED_BREAKER)
        }
        ReportChip(stringResource(R.string.hud_report_water), Modifier.weight(1f)) {
            viewModel.reportHazard(HazardType.WATERLOGGING)
        }
    }
}

@Composable
private fun ReportChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RavColors.SurfaceHigh,
            contentColor = RavColors.TextPrimary,
        ),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartControls(
    context: android.content.Context,
    calibrationValid: Boolean,
    onNavigateToCalibration: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!calibrationValid) {
            OutlinedButton(onClick = onNavigateToCalibration, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.hud_calibration_needed), fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = { DriveForegroundService.start(context, DriveMode.FULL_ADAS) },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RavColors.Amber,
                contentColor = Color.Black,
            ),
        ) {
            Text(stringResource(R.string.drive_start), fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { DriveForegroundService.start(context, DriveMode.POCKET) },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(stringResource(R.string.drive_start_pocket), fontSize = 17.sp)
        }
    }
}

@Composable
private fun SyntheticSpeedSlider(hud: HudState, viewModel: DriveViewModel) {
    var value by remember { mutableStateOf(hud.speedKmh) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RavColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            stringResource(R.string.hud_replay_speed) + ": ${value.toInt()} km/h",
            color = RavColors.Blue,
            fontSize = 14.sp,
        )
        Slider(
            value = value,
            onValueChange = {
                value = it
                viewModel.setSyntheticSpeed(it)
            },
            valueRange = 0f..120f,
        )
    }
}

@Composable
fun DriveStopButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(
        onClick = { DriveForegroundService.stop(context) },
        modifier = modifier.size(width = 160.dp, height = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RavColors.Red, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(stringResource(R.string.drive_stop), fontWeight = FontWeight.Bold)
    }
}
