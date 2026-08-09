package com.deepmost.rabbitav.feature.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepmost.rabbitav.R
import com.deepmost.rabbitav.app.ui.RavColors

/** OEM autostart guidance (Section 5.9): per-manufacturer instructions. */
@Composable
fun OemGuidanceScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Header(stringResource(R.string.oem_title), onBack)
        Text(stringResource(R.string.oem_intro), color = RavColors.TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        for (res in listOf(
            R.string.oem_xiaomi, R.string.oem_vivo, R.string.oem_oppo,
            R.string.oem_realme, R.string.oem_samsung, R.string.oem_generic,
        )) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RavColors.Surface)
                    .padding(14.dp)
            ) {
                val text = stringResource(res)
                val split = text.split("·", limit = 2)
                Text(split[0].trim(), color = RavColors.Amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (split.size > 1) {
                    Text(split[1].trim(), color = RavColors.TextPrimary, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Privacy page (Section 5.9): exactly what is stored and what sync sends. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(RavColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Header(stringResource(R.string.privacy_title), onBack)
        Text(
            stringResource(R.string.privacy_body),
            color = RavColors.TextPrimary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = RavColors.TextPrimary)
    }
}
