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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.screenclicker.model.Script
import com.screenclicker.store.ScriptStore

@Composable
fun ScriptsScreen(
    store: ScriptStore,
    onOpenScript: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var scripts by remember { mutableStateOf(store.list()) }

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
            Text("Scripts", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        if (scripts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No scripts yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "A script is a set of rules: watch for image A in region X, " +
                            "tap region Y. Create one, then add rules to it.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(scripts, key = { it.id }) { script ->
                ScriptRow(
                    script = script,
                    onOpen = { onOpenScript(script.id) },
                    onDelete = {
                        store.delete(script.id)
                        scripts = store.list()
                    },
                )
            }
        }

        Button(
            onClick = {
                val fresh = Script(name = "Script ${scripts.size + 1}")
                store.save(fresh)
                scripts = store.list()
                onOpenScript(fresh.id)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("New script")
        }
    }
}

@Composable
private fun ScriptRow(
    script: Script,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(script.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOfNotNull(
                            "${script.rules.size} rule(s)",
                            script.targetPackage?.let { "app: $it" },
                            if (script.enabled) null else "disabled",
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
}
