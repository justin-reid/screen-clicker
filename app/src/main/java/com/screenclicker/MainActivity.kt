package com.screenclicker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.screenclicker.store.ScriptStore
import com.screenclicker.store.SettingsRepo
import com.screenclicker.ui.Screen
import com.screenclicker.ui.ScriptEditorScreen
import com.screenclicker.ui.RuleEditorScreen
import com.screenclicker.ui.ScriptsScreen
import com.screenclicker.ui.AppSettingsScreen

class MainActivity : ComponentActivity() {

    private lateinit var store: ScriptStore
    private lateinit var settingsRepo: SettingsRepo

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        settingsRepo = SettingsRepo(this)
        requestNotificationsIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        var screen by remember { mutableStateOf<Screen>(Screen.Scripts) }
        when (val current = screen) {
            Screen.Scripts -> ScriptsScreen(
                store = store,
                onOpenScript = { screen = Screen.EditScript(it) },
                onOpenSettings = { screen = Screen.Settings },
            )

            is Screen.EditScript -> ScriptEditorScreen(
                store = store,
                defaults = settingsRepo.load(),
                scriptId = current.scriptId,
                onBack = { screen = Screen.Scripts },
                onOpenRule = { ruleId -> screen = Screen.EditRule(current.scriptId, ruleId) },
            )

            is Screen.EditRule -> RuleEditorScreen(
                store = store,
                scriptId = current.scriptId,
                ruleId = current.ruleId,
                onBack = { screen = Screen.EditScript(current.scriptId) },
            )

            Screen.Settings -> AppSettingsScreen(
                settingsRepo = settingsRepo,
                onBack = { screen = Screen.Scripts },
            )
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}


