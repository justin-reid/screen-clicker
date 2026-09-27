package com.screenclicker.ui

/** Manual navigation — deliberately no nav library for four screens. */
sealed interface Screen {
    data object Scripts : Screen
    data class EditScript(val scriptId: String) : Screen
    data class EditRule(val scriptId: String, val ruleId: String) : Screen
    data object Settings : Screen
}
