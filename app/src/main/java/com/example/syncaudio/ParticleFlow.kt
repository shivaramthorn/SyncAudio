package com.example.syncaudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ParticleFlow(
    modifier: Modifier = Modifier
) {
    var touchPosition by remember { mutableStateOf(Offset.Unspecified) }
    var animationTime by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            animationTime += 0.006f // slow movement
            delay(16)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInteropFilter { event ->
                touchPosition = when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE -> Offset(event.x, event.y)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> Offset.Unspecified
                    else -> touchPosition
                }
                false
            }
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f
        val maxDistance = width * 0.52f
        val touchRadius = 125.dp.toPx()

        val lanesPerSide = 46
        val dotsPerLane = 24
        val verticalSpreadAtEdges = height * 0.23f
        val verticalSpreadAtCenter = height * 0.035f

        // Two mirrored particle fields. Each lane narrows toward the central focal point.
        for (sideIndex in 0..1) {
            val direction = if (sideIndex == 0) 1f else -1f

            for (lane in 0 until lanesPerSide) {
                val lanePosition = lane.toFloat() / (lanesPerSide - 1) - 0.5f
                val laneCurve = lanePosition * 2f

                for (dot in 0 until dotsPerLane) {
                    val seed = sideIndex * 10000 + lane * 97 + dot * 31
                    val offset = (seed % 997) / 997f

                    // Dots start near the outer edge and move slowly toward the center.
                    val travel = (offset + animationTime * 0.12f) % 1f
                    val distanceFromCenter = maxDistance * (1f - travel)
                    val x = centerX + direction * distanceFromCenter

                    val edgeAmount = (distanceFromCenter / maxDistance).coerceIn(0f, 1f)
                    val spread = verticalSpreadAtCenter +
                            (verticalSpreadAtEdges - verticalSpreadAtCenter) * edgeAmount

                    // Gentle curved paths, wide at the sides and narrow at the center.
                    val ripple = sin(travel * 5.5f + lane * 0.22f + animationTime * 1.5f) *
                            height * 0.009f * (0.25f + edgeAmount)
                    var y = centerY + laneCurve * spread + ripple

                    // Touch gently repels nearby dots. They return to their paths when released.
                    if (touchPosition != Offset.Unspecified) {
                        val dx = x - touchPosition.x
                        val dy = y - touchPosition.y
                        val distance = sqrt(dx * dx + dy * dy)
                        if (distance < touchRadius && distance > 0.1f) {
                            val force = (1f - distance / touchRadius) * 48f
                            y += dy / distance * force
                            // A smaller horizontal push keeps the inward flow readable.
                            val pushedX = dx / distance * force * 0.35f
                            val finalX = x + pushedX
                            val alpha = (0.22f + edgeAmount * 0.5f).coerceIn(0.18f, 0.75f)
                            val radius = if (seed % 12 == 0) 1.6.dp.toPx() else 0.75.dp.toPx()
                            drawCircle(Color.White.copy(alpha = alpha), radius, Offset(finalX, y))
                            continue
                        }
                    }

                    val alpha = (0.20f + edgeAmount * 0.52f).coerceIn(0.18f, 0.75f)
                    val radius = if (seed % 12 == 0) 1.6.dp.toPx() else 0.75.dp.toPx()
                    drawCircle(
                        color = Color.White.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}
