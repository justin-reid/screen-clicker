package com.screenclicker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.model.Cadence
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.overlay.ConfigOverlayService
import com.screenclicker.store.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rule editor. Regions are also editable numerically here, but the intended flow is
 * the on-screen editor ([ConfigOverlayService]): drag rectangles over the live app,
 * capture the template from the screen itself, check the match live, save — the saved
 * rule arrives back here through [ConfigOverlayService.savedRules].
 *
 * The template can instead be imported from the gallery/files. That is useful when the
 * reference image already exists as a file, but the scale has to match what the app
 * renders on screen — matching is a pixel-by-pixel comparison, not a smart search.
 */
@Composable
fun RuleEditorScreen(
    store: ScriptStore,
    scriptId: String,
    ruleId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var script by remember {
        mutableStateOf(store.list().firstOrNull { it.id == scriptId })
    }
    var rule by remember { mutableStateOf(script?.ruleById(ruleId)) }

    // Accessibility state drives whether capture can work at all. Android disables the
    // service whenever the app is reinstalled, so this is a common starting point.
    var serviceEnabled by remember { mutableStateOf(ClickerAccessibilityService.isEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(ClickerAccessibilityService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceEnabled = ClickerAccessibilityService.isEnabled(context)
            serviceRunning = ClickerAccessibilityService.isRunning
            delay(2_000)
        }
    }

    // Hot-reload when the overlay saves.
    LaunchedEffect(Unit) {
        ConfigOverlayService.savedRules.collect { saved ->
            if (saved?.id == ruleId) {
                rule = saved
                script = store.list().firstOrNull { it.id == scriptId }
            }
        }
    }

    val current = script ?: return
    val editing = rule ?: return

    var templateNote by remember { mutableStateOf<String?>(null) }

    // The template file name is deterministic per rule, so writing a new template does
    // not change any state the preview could key off — this counter forces a re-decode.
    var templateVersion by remember { mutableStateOf(0) }

    fun update(transform: (Rule) -> Rule) {
        val updatedRule = transform(editing)
        val updatedScript = current.withRule(updatedRule)
        rule = updatedRule
        script = updatedScript
    }

    val pickTemplateImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val target = editing
            val decoded = withContext(Dispatchers.IO) {
                decodeTemplateImage(context, uri, target.searchRegion)
            }
            if (decoded == null) {
                templateNote = "Could not read that image."
                return@launch
            }
            val name = withContext(Dispatchers.IO) {
                store.saveTemplateFromBitmap(target.id, decoded)
            }
            val updatedRule = target.copy(templateFile = name)
            val updatedScript = current.withRule(updatedRule)
            rule = updatedRule
            script = updatedScript
            // Persist now: the PNG already replaced this rule's fixed template path.
            store.save(updatedScript)
            templateNote = "Imported ${decoded.width} × ${decoded.height} px from gallery."
            templateVersion++
        }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Rule", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                script?.let { store.save(it) }
                onBack()
            }) { Text("Save") }
        }

        if (!serviceRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (serviceEnabled) {
                            "Accessibility service is connecting"
                        } else {
                            "Accessibility service is off"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Screen Clicker needs it to take the screenshot and to tap " +
                            "for you. Android switches it off every time the app is " +
                            "reinstalled, so this is expected after an update.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { context.openAccessibilitySettings() }) {
                            Text("Open accessibility settings")
                        }
                        OutlinedButton(onClick = {
                            serviceEnabled = ClickerAccessibilityService.isEnabled(context)
                            serviceRunning = ClickerAccessibilityService.isRunning
                        }) { Text("Recheck") }
                    }
                    Text(
                        text = "In the list: tap Screen Clicker → turn it on → come back.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = editing.name,
            onValueChange = { name -> update { it.copy(name = name) } },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enabled")
            Switch(
                checked = editing.enabled,
                onCheckedChange = { on -> update { it.copy(enabled = on) } },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Match", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (editing.hasTemplate) {
                        "Template ready."
                    } else {
                        "No template yet — capture one on screen, or import an image."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TemplatePreview(
                    store = store,
                    ruleId = editing.id,
                    templateFile = editing.templateFile,
                    version = templateVersion,
                )
                templateNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            ConfigOverlayService.start(context, editing.id)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }) {
                        Text("Capture on screen")
                    }
                    OutlinedButton(onClick = { pickTemplateImage.launch("image/*") }) {
                        Text("Import image")
                    }
                }
                Text(
                    text = "Capture on screen opens the floating editor: drag the boxes " +
                        "over the live app, then Capture the template from the screen " +
                        "itself. Import image uses a picture you already have — keep it " +
                        "at the size it appears on screen, because matching compares " +
                        "pixels 1:1. It is automatically scaled down to fit inside the " +
                        "search region.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Similarity threshold: ${(editing.threshold * 100).toInt()}%")
                Slider(
                    value = editing.threshold,
                    onValueChange = { t -> update { it.copy(threshold = t.coerceIn(0.05f, 1f)) } },
                    valueRange = 0.5f..1f,
                )
                Text(
                    text = "Higher = stricter. 90% is a good start; lower it if legitimate " +
                        "matches are missed, raise it if wrong things trigger.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Search region", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "This is where the app looks. The template is only searched for " +
                        "inside this box — everything outside is ignored. Make it just big " +
                        "enough to contain the thing you are watching: smaller is faster " +
                        "and much less likely to trigger on something unintended.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Currently ${editing.searchRegion.width} × " +
                        "${editing.searchRegion.height} px, top-left at " +
                        "(${editing.searchRegion.left}, ${editing.searchRegion.top}). " +
                        "Easiest way to set it: draw it in the on-screen editor.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RegionFields(
                    rect = editing.searchRegion,
                    onChange = { r -> update { it.copy(searchRegion = r) } },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Click", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = editing.clickMode == ClickMode.ON_IMAGE,
                        onClick = { update { it.copy(clickMode = ClickMode.ON_IMAGE) } },
                        label = { Text("On the image") },
                    )
                    FilterChip(
                        selected = editing.clickMode == ClickMode.ON_REGION,
                        onClick = { update { it.copy(clickMode = ClickMode.ON_REGION) } },
                        label = { Text("Elsewhere") },
                    )
                }
                if (editing.clickMode == ClickMode.ON_REGION) {
                    Text(
                        text = "Click area: where the tap lands when the image is found.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    RegionFields(
                        rect = editing.clickRegion ?: PxRect(0, 0, 0, 0),
                        onChange = { r -> update { it.copy(clickRegion = r) } },
                    )
                }
                NumberField("Delay before click (ms)", editing.delayMs) { v ->
                    update { it.copy(delayMs = v.coerceAtLeast(0)) }
                }
                NumberField("Jitter ± (ms)", editing.jitterMs) { v ->
                    update { it.copy(jitterMs = v.coerceAtLeast(0)) }
                }
                Text(
                    text = "The click lands at a random point inside the bounds, inset " +
                        "10% from the edges.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("When to click", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = editing.cadence == Cadence.ONCE_PER_APPEARANCE,
                        onClick = { update { it.copy(cadence = Cadence.ONCE_PER_APPEARANCE) } },
                        label = { Text("Once per appearance") },
                    )
                    FilterChip(
                        selected = editing.cadence == Cadence.REPEAT_WHILE_VISIBLE,
                        onClick = { update { it.copy(cadence = Cadence.REPEAT_WHILE_VISIBLE) } },
                        label = { Text("Repeat while visible") },
                    )
                }
                if (editing.cadence == Cadence.REPEAT_WHILE_VISIBLE) {
                    NumberField("Interval between clicks (ms)", editing.intervalMs) { v ->
                        update { it.copy(intervalMs = v.coerceAtLeast(0)) }
                    }
                }
                Text(
                    text = "Once per appearance clicks when the image appears, and waits " +
                        "for it to disappear before clicking again. Repeat fires on an " +
                        "interval for as long as the image is visible.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("Priority (higher first)", editing.priority.toLong()) { v ->
                    update { it.copy(priority = v.toInt()) }
                }
            }
        }
    }
}

/** Shows the rule's current template so the user can see what will be matched. */
@Composable
private fun TemplatePreview(
    store: ScriptStore,
    ruleId: String,
    templateFile: String?,
    version: Int,
) {
    val preview by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = "$version|$templateFile",
    ) {
        value = withContext(Dispatchers.IO) {
            val file = store.templateFile(ruleId)
            if (templateFile == null || !file.exists()) return@withContext null
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    preview?.let {
        Image(
            bitmap = it,
            contentDescription = "Current template image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
        )
    }
}

/**
 * Decodes a picked image and scales it so the matcher can use it: the template is slid
 * across the search region, so it may never be larger than that region. Anything bigger
 * is scaled down proportionally; [DEFAULT_TEMPLATE_MAX] caps it when no region is set.
 */
private fun decodeTemplateImage(context: Context, uri: Uri, region: PxRect): Bitmap? {
    val maxWidth = if (region.width > 0) region.width else DEFAULT_TEMPLATE_MAX
    val maxHeight = if (region.height > 0) region.height else DEFAULT_TEMPLATE_MAX

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    // Subsample while decoding so a 50MP photo never lands in memory at full size.
    var sample = 1
    while (sourceWidth / (sample * 2) >= maxWidth && sourceHeight / (sample * 2) >= maxHeight) {
        sample *= 2
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } ?: return null

    val scale = minOf(
        maxWidth.toFloat() / decoded.width,
        maxHeight.toFloat() / decoded.height,
        1f,
    )
    if (scale >= 1f) return decoded
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(8),
        (decoded.height * scale).toInt().coerceAtLeast(8),
        true,
    )
}

private const val DEFAULT_TEMPLATE_MAX = 1_024

@Composable
private fun RegionFields(rect: PxRect, onChange: (PxRect) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberField("Left", rect.left.toLong(), Modifier.weight(1f)) { v ->
                onChange(rect.copy(left = v.toInt()))
            }
            NumberField("Top", rect.top.toLong(), Modifier.weight(1f)) { v ->
                onChange(rect.copy(top = v.toInt()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberField("Right", rect.right.toLong(), Modifier.weight(1f)) { v ->
                onChange(rect.copy(right = v.toInt()))
            }
            NumberField("Bottom", rect.bottom.toLong(), Modifier.weight(1f)) { v ->
                onChange(rect.copy(bottom = v.toInt()))
            }
        }
        Text(
            text = "Screen pixels; top-left is (0, 0). Left/Top = top-left corner, " +
                "Right/Bottom = bottom-right corner.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (Long) -> Unit,
) {
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
        modifier = modifier,
    )
}
