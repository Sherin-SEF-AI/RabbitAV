package com.deepmost.rabbitav.feature.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    suspend fun isDone(): Boolean = settings.onboardingDone.first()
    fun markDone() {
        viewModelScope.launch { settings.setOnboardingDone() }
    }
}

/**
 * First-run permission sequence (Section 5.11): explains each grant, then the
 * battery-optimization exemption helper. Skipping is allowed — the service
 * degrades gracefully (pocket mode without camera, no alerts without GPS).
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    var checkedDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (viewModel.isDone()) onFinished() else checkedDone = true
    }
    if (!checkedDone) return

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    var notifGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
    }
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = it || Build.VERSION.SDK_INT < 33
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    var batteryExempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = RavColors.TextPrimary,
        )
        Text(
            stringResource(R.string.onboarding_intro),
            fontSize = 16.sp,
            color = RavColors.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))

        if (Build.VERSION.SDK_INT >= 33) {
            PermissionCard(
                title = stringResource(R.string.perm_notifications_title),
                body = stringResource(R.string.perm_notifications_body),
                granted = notifGranted,
            ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        PermissionCard(
            title = stringResource(R.string.perm_camera_title),
            body = stringResource(R.string.perm_camera_body),
            granted = cameraGranted,
        ) { cameraLauncher.launch(Manifest.permission.CAMERA) }
        PermissionCard(
            title = stringResource(R.string.perm_location_title),
            body = stringResource(R.string.perm_location_body),
            granted = locationGranted,
        ) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        PermissionCard(
            title = stringResource(R.string.battery_opt_title),
            body = stringResource(R.string.battery_opt_body),
            granted = batteryExempt,
            grantLabel = stringResource(R.string.battery_opt_button),
        ) {
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS flow (Section 5.9)
            batteryLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                viewModel.markDone()
                onFinished()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Text(stringResource(R.string.perm_continue), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = {
                viewModel.markDone()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.perm_skip))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    grantLabel: String = stringResource(R.string.perm_grant),
    onGrant: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RavColors.Surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = RavColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (granted) {
                Text(
                    stringResource(R.string.perm_granted),
                    color = RavColors.Green,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Button(onClick = onGrant) { Text(grantLabel) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 14.sp, color = RavColors.TextSecondary)
    }
}
