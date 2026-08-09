package com.deepmost.rabbitav.feature.map

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.data.repo.HazardRepository
import com.deepmost.rabbitav.core.data.repo.SettingsRepository
import com.deepmost.rabbitav.core.hazard.StoredSite
import com.deepmost.rabbitav.core.imu.HazardType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

@HiltViewModel
class MapViewModel @Inject constructor(
    hazardRepository: HazardRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val sites: StateFlow<List<StoredSite>> = hazardRepository.allSitesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun lastPosition(): Pair<Double, Double>? = settings.lastPosition()
}

/** Hazard map (Section 5.9): osmdroid, type-colored confidence-alpha markers,
 *  heatmap toggle, tap for details. */
@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    var heatmap by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<StoredSite?>(null) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    Column(Modifier.fillMaxSize().background(RavColors.Background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.map_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = RavColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.map_heatmap), color = RavColors.TextSecondary, fontSize = 14.sp)
            Switch(checked = heatmap, onCheckedChange = { heatmap = it })
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = {
                    Configuration.getInstance().apply {
                        userAgentValue = context.packageName
                        osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
                        osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")
                    }
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(14.0)
                        controller.setCenter(GeoPoint(12.9716, 77.5946))
                        mapRef = this
                    }
                },
                update = { map ->
                    map.overlays.removeAll { it is HazardOverlay }
                    map.overlays.add(HazardOverlay(sites, heatmap) { site -> selected = site })
                    map.invalidate()
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (sites.isEmpty()) {
                Text(
                    stringResource(R.string.map_empty),
                    color = RavColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }

        selected?.let { site -> SiteDetailCard(site) }
    }

    // Center on last known position once
    val vm = viewModel
    androidx.compose.runtime.LaunchedEffect(mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        vm.lastPosition()?.let { (lat, lon) ->
            map.controller.setCenter(GeoPoint(lat, lon))
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapRef?.onDetach() }
    }
}

@Composable
private fun SiteDetailCard(site: StoredSite) {
    val fmt = remember { SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RavColors.SurfaceHigh)
            .padding(16.dp)
    ) {
        Text(
            site.type.name.replace('_', ' '),
            color = androidx.compose.ui.graphics.Color(typeColor(site.type)),
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.map_site_confidence, (site.confidence * 100).toInt()) + " · " +
                stringResource(R.string.map_site_hits, site.hitCount),
            color = RavColors.TextPrimary,
            fontSize = 15.sp,
        )
        Text(
            stringResource(R.string.map_site_last_seen, fmt.format(Date(site.lastSeenMs))),
            color = RavColors.TextSecondary,
            fontSize = 13.sp,
        )
        Text(
            stringResource(R.string.map_site_avg_speed, site.avgSpeedMps * 3.6f),
            color = RavColors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

private fun typeColor(type: HazardType): Int = when (type) {
    HazardType.POTHOLE -> 0xFFFF3B30.toInt()
    HazardType.SPEED_BREAKER -> 0xFFFFB300.toInt()
    HazardType.ROUGH_PATCH -> 0xFF9B59B6.toInt()
    HazardType.WATERLOGGING -> 0xFF4FA3FF.toInt()
    HazardType.UNKNOWN -> 0xFF9AA7B4.toInt()
}

/**
 * Single overlay drawing every site (markers or heatmap circles) — far cheaper
 * than per-site Marker objects for hundreds of sites, and it gives us
 * confidence-scaled alpha for free.
 */
private class HazardOverlay(
    private val sites: List<StoredSite>,
    private val heatmap: Boolean,
    private val onTap: (StoredSite) -> Unit,
) : Overlay() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.BLACK
    }
    private val point = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        for (site in sites) {
            projection.toPixels(GeoPoint(site.lat, site.lon), point)
            val alpha = (60 + site.confidence * 195).toInt().coerceIn(40, 255)
            paint.color = typeColor(site.type)
            paint.alpha = if (heatmap) (alpha / 2).coerceAtLeast(30) else alpha
            val radius = if (heatmap) {
                // ~30 m at current zoom
                (projection.metersToEquatorPixels(30f)).coerceIn(18f, 120f)
            } else {
                14f
            }
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, paint)
            if (!heatmap) {
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 14f, stroke)
            }
        }
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        var best: StoredSite? = null
        var bestDist = Float.MAX_VALUE
        for (site in sites) {
            projection.toPixels(GeoPoint(site.lat, site.lon), point)
            val dx = e.x - point.x
            val dy = e.y - point.y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = site
            }
        }
        if (best != null && bestDist < 48f * 48f) {
            onTap(best)
            return true
        }
        return false
    }
}
