package com.screenclicker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.AccessibilityCapture
import com.screenclicker.capture.CaptureResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    // Status can flip outside the app (user toggles the service in settings), so poll.
    var serviceRunning by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = ClickerAccessibilityService.isRunning
            foreground = ClickerAccessibilityService.foregroundPackage
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Screen Clicker", style = MaterialTheme.typography.headlineMedium)

        Text(
            text = "Setup (M1 diagnostics — scripts and detection arrive in later milestones)",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accessibility service", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (serviceRunning) "Running" else "Not enabled",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val context = LocalContext.current
                OutlinedButton(onClick = { context.openAccessibilitySettings() }) {
                    Text(if (serviceRunning) "Manage in settings" else "Enable in settings")
                }
                Text(
                    text = foreground?.let { "Foreground app: $it" } ?: "Foreground app: unknown",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        DiagnosticsCard()
    }
}

@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var captureInfo by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf<Int?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val capturer = AccessibilityCapture()
                        if (!capturer.isAvailable()) {
                            captureInfo = "Capture unavailable: accessibility service is off"
                            return@launch
                        }
                        val started = System.currentTimeMillis()
                        when (val result = capturer.capture()) {
                            is CaptureResult.Success -> {
                                val ms = System.currentTimeMillis() - started
                                captureInfo = "Captured ${result.width}x${result.height} in ${ms}ms"
                                preview = toPreviewBitmap(result)
                            }
                            is CaptureResult.Failure -> {
                                captureInfo = "Capture failed: ${result.reason}"
                            }
                        }
                    }
                }) {
                    Text("Test screenshot")
                }

                Button(onClick = {
                    scope.launch {
                        for (seconds in 5 downTo 1) {
                            countdown = seconds
                            delay(1_000)
                        }
                        countdown = null
                        val ok = ClickerAccessibilityService.tap(
                            context.resources.displayMetrics.widthPixels / 2f,
                            context.resources.displayMetrics.heightPixels / 2f,
                        )
                        Toast.makeText(
                            context,
                            if (ok) "Tap dispatched" else "Tap failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Text(text = countdown?.let { "Tapping in $it…" } ?: "Test tap (5s)")
                }
            }

            if (captureInfo.isNotEmpty()) Text(captureInfo, style = MaterialTheme.typography.bodySmall)
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Last captured screenshot preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
            Text(
                text = "Tap test: switch to another app within the countdown — the tap " +
                    "lands at the exact center of the screen. The screenshot test needs " +
                    "no setup and shows what the matcher will see.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun toPreviewBitmap(result: CaptureResult.Success): ImageBitmap {
    val full = Bitmap.createBitmap(result.argb, result.width, result.height, Bitmap.Config.ARGB_8888)
    val scale = 360f / result.width
    val scaled = Bitmap.createScaledBitmap(
        full,
        360,
        (result.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    return scaled.asImageBitmap()
}

private fun Context.openAccessibilitySettings() {
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
