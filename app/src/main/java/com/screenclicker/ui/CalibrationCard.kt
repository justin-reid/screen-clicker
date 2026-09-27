package com.screenclicker.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.screenclicker.store.SettingsRepo
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class Phase { IDLE, WAITING, GO, RESULT, DONE }

/**
 * Measures the user's visual reaction time: wait a random 1–4s, flash, tap as fast as
 * possible. Five trials, worst dropped, average offered as the global default click
 * delay — the "how fast would a human have clicked" baseline.
 */
@Composable
fun CalibrationCard(settingsRepo: SettingsRepo) {
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var attempt by remember { mutableStateOf(0) }
    var goAt by remember { mutableStateOf(0L) }
    var message by remember {
        mutableStateOf("Tap the box to start, then tap again the instant it turns green.")
    }
    var times by remember { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(phase, attempt) {
        if (phase == Phase.WAITING) {
            delay(Random.nextLong(1_000, 4_000))
            goAt = SystemClock.uptimeMillis()
            phase = Phase.GO
        }
    }

    val average = if (times.size >= 2) {
        times.sorted().dropLast(1).average().toInt()
    } else {
        times.firstOrNull()?.toInt()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reaction time", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Trial ${times.size + (if (phase == Phase.DONE) 0 else 1)} of 5 · " +
                    "the average becomes your default click delay",
                style = MaterialTheme.typography.bodySmall,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        when (phase) {
                            Phase.GO -> Color(0xFF2E7D32)
                            Phase.WAITING -> Color(0xFF8B1E1E)
                            else -> Color(0xFF22262E)
                        },
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            when (phase) {
                                Phase.IDLE -> {
                                    message = "Get ready…"
                                    times = emptyList()
                                    phase = Phase.WAITING
                                    attempt += 1
                                }

                                Phase.WAITING -> {
                                    // False start: restart the same trial with a fresh delay.
                                    message = "Too early — wait for green!"
                                    attempt += 1
                                }

                                Phase.GO -> {
                                    val rt = SystemClock.uptimeMillis() - goAt
                                    times = times + rt
                                    message = "$rt ms"
                                    phase = Phase.RESULT
                                }

                                Phase.RESULT -> {
                                    if (times.size >= 5) {
                                        phase = Phase.DONE
                                    } else {
                                        phase = Phase.WAITING
                                        attempt += 1
                                    }
                                }

                                Phase.DONE -> {
                                    message = "Get ready…"
                                    times = emptyList()
                                    phase = Phase.WAITING
                                    attempt += 1
                                }
                            }
                            waitForUpOrCancellation()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (phase) {
                        Phase.IDLE -> "Tap to start"
                        Phase.WAITING -> "Wait for green…"
                        Phase.GO -> "TAP!"
                        Phase.RESULT -> message
                        Phase.DONE -> "Tap to run again"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            Text(message, style = MaterialTheme.typography.bodyMedium)

            if (phase == Phase.DONE && average != null) {
                Text(
                    text = "Average reaction: $average ms",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = {
                    val current = settingsRepo.load()
                    settingsRepo.save(current.copy(defaultDelayMs = average.toLong()))
                    message = "Default click delay set to $average ms"
                }) {
                    Text("Use as default click delay")
                }
            }
        }
    }
}
