package com.deepmost.rabbitav.feature.drive

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.deepmost.rabbitav.app.ui.RavColors
import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.service.OverlayFrame
import com.deepmost.rabbitav.service.PerfStats

/**
 * Debug overlay (Section 5.9): detection boxes with class/conf, track IDs,
 * per-track Z and TTC, corridor rungs at 10/25/50 m, horizon line, and the
 * perf footer (inference ms + FPS + delegate + thermal). Drawn over the
 * FIT_CENTER preview: coordinates map into the fitted content rect.
 */
@Composable
fun DebugOverlay(
    overlay: OverlayFrame,
    perf: PerfStats,
    modifier: Modifier = Modifier,
) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val footerPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(255, 179, 0)
            textSize = 34f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier) {
        val content = fittedContentRect(size, overlay.aspect)

        // corridor + horizon
        overlay.corridor?.let { c ->
            val hy = content.top + c.horizonVNorm * content.height
            drawLine(
                color = RavColors.Blue.copy(alpha = 0.7f),
                start = Offset(content.left, hy),
                end = Offset(content.right, hy),
                strokeWidth = 2f,
            )
            for ((i, rung) in c.rungs.withIndex()) {
                val y = content.top + rung[0] * content.height
                if (y < content.top || y > content.bottom) continue
                val xl = content.left + rung[1] * content.width
                val xr = content.left + rung[2] * content.width
                drawLine(
                    color = RavColors.Green.copy(alpha = 0.8f),
                    start = Offset(xl, y),
                    end = Offset(xr, y),
                    strokeWidth = 3f,
                )
                drawIntoText("${listOf(10, 25, 50)[i]}m", xr + 8f, y + 10f, textPaint)
            }
            // corridor side lines connecting the rungs
            if (c.rungs.size >= 2) {
                for (side in 0..1) {
                    for (j in 0 until c.rungs.size - 1) {
                        val a = c.rungs[j]
                        val b = c.rungs[j + 1]
                        drawLine(
                            color = RavColors.Green.copy(alpha = 0.4f),
                            start = Offset(content.left + a[1 + side] * content.width, content.top + a[0] * content.height),
                            end = Offset(content.left + b[1 + side] * content.width, content.top + b[0] * content.height),
                            strokeWidth = 2f,
                        )
                    }
                }
            }
        }

        // tracks
        for (t in overlay.tracks) {
            val color = when {
                !t.confirmed -> Color(0xFF808080)
                t.canonical.isVru -> Color(0xFFFF7AB6)
                t.canonical == CanonicalClass.ANIMAL -> Color(0xFFFFA040)
                t.canonical.isRoadHazard -> Color(0xFF9B59B6)
                t.inCorridor -> RavColors.Amber
                else -> RavColors.Blue
            }
            val l = content.left + (t.cx - t.w / 2f) * content.width
            val top = content.top + (t.cy - t.h / 2f) * content.height
            val w = t.w * content.width
            val h = t.h * content.height
            drawRect(
                color = color,
                topLeft = Offset(l, top),
                size = Size(w, h),
                style = Stroke(width = if (t.inCorridor) 5f else 3f),
            )
            val label = buildString {
                append('#').append(t.id).append(' ')
                append(t.canonical.name.take(3))
                append(" %.2f".format(t.score))
                if (!t.zMeters.isNaN()) append("  %.1fm".format(t.zMeters))
                if (t.ttcS.isFinite()) append("  ttc %.1fs".format(t.ttcS))
                if (t.distanceLowConfidence) append(" ~")
            }
            drawIntoText(label, l, (top - 8f).coerceAtLeast(30f), textPaint)
        }

        // perf footer
        val footer = "inf %.0f/%.0fms  det %.1ffps cam %.1ffps  %s  drop %.0f%%  %s  mem %.0fMB  T %.2f".format(
            perf.p50Ms, perf.p90Ms, perf.detectorFps, perf.cameraFps,
            perf.delegate.name, perf.dropRatio * 100f, perf.governorLevel.name,
            perf.totalMemMb, perf.thermalPressure,
        )
        drawIntoText(footer, 16f, size.height - 18f, footerPaint)
    }
}

private fun DrawScope.drawIntoText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** Rect the FIT_CENTER-scaled upright frame occupies inside the canvas. */
private fun fittedContentRect(canvas: Size, aspect: Float): Rect {
    val canvasAspect = canvas.width / canvas.height
    return if (canvasAspect > aspect) {
        val w = canvas.height * aspect
        val x = (canvas.width - w) / 2f
        Rect(x, 0f, x + w, canvas.height)
    } else {
        val h = canvas.width / aspect
        val y = (canvas.height - h) / 2f
        Rect(0f, y, canvas.width, y + h)
    }
}
