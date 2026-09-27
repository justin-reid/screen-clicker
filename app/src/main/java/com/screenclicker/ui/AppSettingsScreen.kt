package com.screenclicker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.AccessibilityCapture
import com.screenclicker.capture.CaptureResult
import com.screenclicker.capture.CaptureProjectionService
import com.screenclicker.capture.Capturers
import com.screenclicker.model.GlobalSettings
import com.screenclicker.overlay.ControlPanelService
import com.screenclicker.store.SettingsRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global settings: engine defaults, the accessibility diagnostics from M1, and (from
 * M7) the reaction-time calibration that feeds the default click delay.
 */
@Composable
fun AppSettingsScreen(
    settingsRepo: SettingsRepo,
    onGrantProjection: () -> Unit,
    onBack: () -> Unit,
) {
    var settings by remember { mutableStateOf(settingsRepo.load()) }

    fun save(next: GlobalSettings) {
        settings = next
        settingsRepo.save(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Defaults for new rules", style = MaterialTheme.typography.titleSmall)
                NumberField("Delay before click (ms)", settings.defaultDelayMs) { v ->
                    save(settings.copy(defaultDelayMs = v.coerceAtLeast(0)))
                }
                NumberField("Jitter ± (ms)", settings.defaultJitterMs) { v ->
                    save(settings.copy(defaultJitterMs = v.coerceAtLeast(0)))
                }
                NumberField("Interval for repeat mode (ms)", settings.defaultIntervalMs) { v ->
                    save(settings.copy(defaultIntervalMs = v.coerceAtLeast(0)))
                }
            }
        }

        CalibrationCard(settingsRepo)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Engine", style = MaterialTheme.typography.titleSmall)
                NumberField("Minimum time between scans (ms)", settings.scanIntervalMs) { v ->
                    save(settings.copy(scanIntervalMs = v.coerceIn(100, 5_000)))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Show detections while running")
                    Switch(
                        checked = settings.showDetections,
                        onCheckedChange = { on -> save(settings.copy(showDetections = on)) },
                    )
                }
            }
        }

        CaptureCard(
            settings = settings,
            onChange = ::save,
            onGrantProjection = onGrantProjection,
        )
        FloatingPanelCard()
        AccessibilityCard()
        DiagnosticsCard()
    }
}

@Composable
private fun CaptureCard(
    settings: GlobalSettings,
    onChange: (GlobalSettings) -> Unit,
    onGrantProjection: () -> Unit,
) {
    val context = LocalContext.current
    var projectionReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            projectionReady = CaptureProjectionService.isReady
            delay(2_000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Screen capture", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.captureBackend == Capturers.BACKEND_AUTO,
                    onClick = { onChange(settings.copy(captureBackend = Capturers.BACKEND_AUTO)) },
                    label = { Text("Auto") },
                )
                FilterChip(
                    selected = settings.captureBackend == Capturers.BACKEND_ACCESSIBILITY,
                    onClick = {
                        onChange(settings.copy(captureBackend = Capturers.BACKEND_ACCESSIBILITY))
                    },
                    label = { Text("Accessibility") },
                )
                FilterChip(
                    selected = settings.captureBackend == Capturers.BACKEND_MEDIA_PROJECTION,
                    onClick = {
                        onChange(settings.copy(captureBackend = Capturers.BACKEND_MEDIA_PROJECTION))
                    },
                    label = { Text("Fast") },
                )
            }
            Text(
                text = "Fast capture (MediaProjection) can read the screen many times per " +
                    "second for fast reaction; accessibility capture is limited to about " +
                    "one per second. Auto uses fast capture when granted, else " +
                    "accessibility. The backend choice is read when a script starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (projectionReady) {
                    "Fast capture: running. Stopping a script ends it, so grant it again here " +
                        "before the next start."
                } else {
                    "Fast capture: not granted — press Grant screen capture above to turn it on."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrantProjection) {
                    Text(if (projectionReady) "Re-grant" else "Grant screen capture")
                }
                if (projectionReady) {
                    OutlinedButton(onClick = { CaptureProjectionService.stop(context) }) {
                        Text("Stop capture")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityCard() {
    var serviceRunning by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = ClickerAccessibilityService.isRunning
            foreground = ClickerAccessibilityService.foregroundPackage
            delay(2_000)
        }
    }
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Accessibility service", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (serviceRunning) "Running" else "Not enabled",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { context.openAccessibilitySettings() }) {
                Text(if (serviceRunning) "Manage in settings" else "Enable in settings")
            }
            Text(
                text = foreground?.let { "Foreground app: $it" } ?: "Foreground app: unknown",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
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
                    "lands at the exact center of the screen. The screenshot test shows " +
                    "what the matcher will see.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun toPreviewBitmap(result: CaptureResult.Success): ImageBitmap {
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

internal fun Context.openAccessibilitySettings() {
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

/**
 * Turns the floating control panel on or off, remembering the choice and asking for the
 * overlay permission when it is missing (the panel is a window over other apps).
 */
internal fun setFloatingPanel(context: Context, enabled: Boolean) {
    val repo = SettingsRepo(context)
    repo.save(repo.load().copy(controlPanelEnabled = enabled))
    if (!enabled) {
        ControlPanelService.stop(context)
        return
    }
    if (!Settings.canDrawOverlays(context)) {
        Toast.makeText(
            context,
            "Allow display over other apps to show the panel",
            Toast.LENGTH_LONG,
        ).show()
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        return
    }
    ControlPanelService.start(context)
}

/**
 * The panel is how the app is meant to be used day to day: run a script, watch it scan,
 * edit a rule — all on top of the app being automated, with no trips to the launcher.
 */
@Composable
private fun FloatingPanelCard() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(ControlPanelService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            running = ControlPanelService.isRunning
            delay(2_000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Floating control panel", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Show the floating panel")
                Switch(
                    checked = running,
                    onCheckedChange = { on ->
                        running = on
                        setFloatingPanel(context, on)
                    },
                )
            }
            Text(
                text = "Tap the bubble to expand it: run or stop any script, watch the scan " +
                    "loop's timings and detections, and open any rule in the on-screen " +
                    "editor. Drag the bubble to move it, ✕ collapses it back. This is the " +
                    "intended way to drive the clicker — the app itself is only needed to " +
                    "create scripts and change settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
