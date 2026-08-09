package com.deepmost.rabbitav.feature.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.data.db.AlertEventEntity
import com.deepmost.rabbitav.core.data.db.HazardEventEntity
import com.deepmost.rabbitav.core.data.db.TripEntity
import com.deepmost.rabbitav.core.data.repo.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val repository: TripRepository,
) : ViewModel() {
    val trips: StateFlow<List<TripEntity>> = repository.recentTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun detail(id: Long) = repository.tripDetail(id)
}

@Composable
fun TripsScreen(
    onTripClick: (Long) -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(RavColors.Background).padding(16.dp)) {
        Text(
            stringResource(R.string.trips_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = RavColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        if (trips.isEmpty()) {
            Text(stringResource(R.string.trips_empty), color = RavColors.TextSecondary)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trips, key = { it.id }) { trip ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RavColors.Surface)
                        .clickable { onTripClick(trip.id) }
                        .padding(14.dp)
                ) {
                    Row {
                        Text(
                            fmt.format(Date(trip.startMs)),
                            color = RavColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (trip.endMs == null) {
                            Text(
                                stringResource(R.string.trip_ongoing),
                                color = RavColors.Green,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val minutes = ((trip.endMs ?: System.currentTimeMillis()) - trip.startMs) / 60000
                    Text(
                        stringResource(R.string.trip_stats_format, trip.distanceM / 1000.0, minutes) + "  ·  " +
                            stringResource(
                                R.string.trip_alerts_format,
                                trip.fcwCount + trip.headwayCount + trip.vruCount + trip.hazardAheadCount,
                                trip.hazardsLogged,
                            ),
                        color = RavColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun TripDetailScreen(
    tripId: Long,
    onBack: () -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    var trip by remember { mutableStateOf<TripEntity?>(null) }
    var alerts by remember { mutableStateOf<List<AlertEventEntity>>(emptyList()) }
    var hazards by remember { mutableStateOf<List<HazardEventEntity>>(emptyList()) }
    LaunchedEffect(tripId) {
        val (t, a, h) = viewModel.detail(tripId)
        trip = t
        alerts = a
        hazards = h
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(RavColors.Background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text(
                stringResource(R.string.trip_detail_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = RavColors.TextPrimary,
            )
        }
        trip?.let { t ->
            val minutes = ((t.endMs ?: System.currentTimeMillis()) - t.startMs) / 60000
            Text(
                stringResource(R.string.trip_stats_format, t.distanceM / 1000.0, minutes) +
                    "  ·  max ${(t.maxSpeedMps * 3.6f).toInt()} km/h  ·  ${t.mode}",
                color = RavColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        // merged timeline
        data class Event(val timeMs: Long, val label: String, val color: Color)

        val events = remember(alerts, hazards) {
            (alerts.map {
                Event(it.timeMs, "${it.kind} ${it.level}  ·  ${(it.speedMps * 3.6f).toInt()} km/h", RavColors.Amber)
            } + hazards.map {
                Event(it.timeMs, "${it.type}  ·  conf %.2f  ·  %s".format(it.confidence, it.source), RavColors.Blue)
            }).sortedBy { it.timeMs }
        }
        if (events.isEmpty()) {
            Text(stringResource(R.string.trip_timeline_empty), color = RavColors.TextSecondary)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events) { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(e.color)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(fmt.format(Date(e.timeMs)), color = RavColors.TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(e.label, color = RavColors.TextPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}
