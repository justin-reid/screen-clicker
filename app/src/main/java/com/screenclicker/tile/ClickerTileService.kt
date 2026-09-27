package com.screenclicker.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.store.ScriptStore
import com.screenclicker.store.SettingsRepo

/**
 * Quick Settings tile: shows the running state and toggles the last-run script.
 * If no script has ever been started, tapping sends the user to the app instead.
 */
class ClickerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = ClickerAccessibilityService.runningScriptId.value
        if (running != null) {
            ClickerAccessibilityService.stopScript()
        } else {
            val repo = SettingsRepo(applicationContext)
            val last = repo.lastRunScriptId()
            if (last == null || !ClickerAccessibilityService.startScript(applicationContext, last)) {
                Toast.makeText(
                    applicationContext,
                    if (last == null) {
                        "No script to run — start one from Screen Clicker"
                    } else {
                        "Enable the Screen Clicker accessibility service first"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val runningId = ClickerAccessibilityService.runningScriptId.value
        if (runningId != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = "Running: " + (ScriptStore(applicationContext)
                .list()
                .firstOrNull { it.id == runningId }?.name ?: "script")
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Idle"
        }
        tile.updateTile()
    }
}
