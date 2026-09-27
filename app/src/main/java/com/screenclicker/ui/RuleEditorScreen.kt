package com.screenclicker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.screenclicker.model.Cadence
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.overlay.ConfigOverlayService
import com.screenclicker.store.ScriptStore

/**
 * Rule editor. Regions are also editable numerically here, but the intended flow is
 * the on-screen editor ([ConfigOverlayService]): drag rectangles over the live app,
 * capture the template from the screen itself, check the match live, save — the saved
 * rule arrives back here through [ConfigOverlayService.savedRules].
 */
@Composable
fun RuleEditorScreen(
    store: ScriptStore,
    scriptId: String,
    ruleId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var script by remember {
        mutableStateOf(store.list().firstOrNull { it.id == scriptId })
    }
    var rule by remember { mutableStateOf(script?.ruleById(ruleId)) }

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

    fun update(transform: (Rule) -> Rule) {
        val updatedRule = transform(editing)
        val updatedScript = current.withRule(updatedRule)
        rule = updatedRule
        script = updatedScript
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
                        "Template captured (${editing.templateFile})"
                    } else {
                        "No template yet — capture one with the on-screen editor below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
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
                    Text("Edit regions & capture on screen")
                }
                Text(
                    text = "The on-screen editor draws over whatever app you like: drag the " +
                        "search and click rectangles into place, capture the template, and " +
                        "check the live match before saving.",
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
                Text("Search region (px)", style = MaterialTheme.typography.titleSmall)
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

@Composable
private fun RegionFields(rect: PxRect, onChange: (PxRect) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberField("L", rect.left.toLong()) { v -> onChange(rect.copy(left = v.toInt())) }
        NumberField("T", rect.top.toLong()) { v -> onChange(rect.copy(top = v.toInt())) }
        NumberField("R", rect.right.toLong()) { v -> onChange(rect.copy(right = v.toInt())) }
        NumberField("B", rect.bottom.toLong()) { v -> onChange(rect.copy(bottom = v.toInt())) }
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
