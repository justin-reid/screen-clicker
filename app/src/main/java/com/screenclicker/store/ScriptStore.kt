package com.screenclicker.store

import android.content.Context
import android.graphics.Bitmap
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.model.Script
import com.screenclicker.vision.GrayImage
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Scripts as one JSON file each, template crops as PNGs beside them:
 *
 *   files/scripts/<scriptId>.json      (Script + rules)
 *   files/templates/tpl_<ruleId>.png
 *
 * Import/export later means copying one directory. The engine re-reads scripts on
 * start, so edits take effect the next time a script starts.
 */
class ScriptStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val scriptsDir: File get() = File(context.filesDir, "scripts").apply { mkdirs() }
    private val templatesDir: File get() = File(context.filesDir, "templates").apply { mkdirs() }

    fun list(): List<Script> =
        scriptsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString<Script>(file.readText()) }
                    .onFailure { android.util.Log.w(TAG, "unreadable script ${file.name}", it) }
                    .getOrNull()
            }
            .sortedBy { it.name }

    fun save(script: Script) {
        File(scriptsDir, script.id + ".json").writeText(json.encodeToString(script))
    }

    fun delete(scriptId: String) {
        val script = File(scriptsDir, scriptId + ".json")
            .takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<Script>(it.readText()) }.getOrNull() }
        script?.rules?.forEach { rule -> deleteTemplate(rule.id) }
        File(scriptsDir, scriptId + ".json").delete()
    }

    /** Crops the template out of a full-screen capture and stores it as a PNG. */
    fun saveTemplate(ruleId: String, argb: IntArray, width: Int, height: Int, region: PxRect): String {
        val file = templateFile(ruleId)
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(
            bitmap,
            region.left,
            region.top,
            region.width,
            region.height,
        )
        file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.name
    }

    fun deleteTemplate(ruleId: String) {
        templateFile(ruleId).delete()
    }

    /** Decodes the rule's PNG into the matcher's grayscale format; null if missing/corrupt. */
    fun loadTemplate(rule: Rule): GrayImage? {
        val name = rule.templateFile ?: return null
        val file = File(templatesDir, name)
        if (!file.exists()) return null
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return GrayImage.fromArgb(argb, bitmap.width, bitmap.height)
    }

    fun templateFile(ruleId: String): File = File(templatesDir, "tpl_$ruleId.png")

    private companion object {
        const val TAG = "ScriptStore"
    }
}
