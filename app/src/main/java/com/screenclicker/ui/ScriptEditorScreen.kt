package com.screenclicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.screenclicker.model.GlobalSettings
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.model.Script
import com.screenclicker.store.ScriptStore

@Composable
fun ScriptEditorScreen(
    store: ScriptStore,
    defaults: GlobalSettings,
    scriptId: String,
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
) {
    var script by remember { mutableStateOf(store.list().firstOrNull { it.id == scriptId }) }

    LaunchedEffect(script == null) {
        if (script == null) onBack()
    }

    val current = script ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Script", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        OutlinedTextField(
            value = current.name,
            onValueChange = { name ->
                val updated = current.copy(name = name)
                script = updated
                store.save(updated)
            },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = current.targetPackage.orEmpty(),
            onValueChange = { pkg ->
                val updated = current.copy(targetPackage = pkg.ifBlank { null })
                script = updated
                store.save(updated)
            },
            label = { Text("Run only in this app (package name, optional)") },
            supportingText = { Text("e.g. com.supercell.clashofclans — leave blank to run anywhere") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("Rules", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(current.rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    onOpen = { onOpenRule(rule.id) },
                    onDelete = {
                        store.deleteTemplate(rule.id)
                        val updated = current.withoutRule(rule.id)
                        script = updated
                        store.save(updated)
                    },
                )
            }
        }

        Button(
            onClick = {
                val fresh = Rule(
                    name = "Rule ${current.rules.size + 1}",
                    searchRegion = PxRect(0, 0, 0, 0),
                    delayMs = defaults.defaultDelayMs,
                    jitterMs = defaults.defaultJitterMs,
                    threshold = defaults.defaultThreshold,
                    intervalMs = defaults.defaultIntervalMs,
                )
                val updated = current.withRule(fresh)
                script = updated
                store.save(updated)
                onOpenRule(fresh.id)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add rule")
        }
    }
}

@Composable
private fun RuleRow(
    rule: Rule,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(rule.name.ifBlank { "(unnamed rule)" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = listOfNotNull(
                        if (rule.enabled) null else "disabled",
                        if (rule.hasTemplate) "template ready" else "no template yet",
                        "≥${(rule.threshold * 100).toInt()}%",
                        "${rule.delayMs}ms delay",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
