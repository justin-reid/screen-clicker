package com.screenclicker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.screenclicker.accessibility.ClickerAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first-run tutorial, per docs/mocks/tutorial.html (the approved v3 mock):
 * a fixed-size card over the Scripts screen, four text pages, swipe or Next.
 * Deliberately text-only — no screenshots to keep current — and deliberately terse:
 * no page justifies a design decision to the user.
 *
 * Shown once on first open (tutorialSeen, which skipping also sets) and on demand
 * from Settings → Help → "Show the tutorial again".
 */
private const val PAGE_COUNT = 4

// One fixed size for every page — page content never resizes the card.
private val CARD_HEIGHT = 480.dp

@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == PAGE_COUNT - 1

    // Back press counts as seen too: re-opening the tutorial on every launch until
    // "Got it" would be the tutorial nagging.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(CARD_HEIGHT),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Screen Clicker",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!onLastPage) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Skip",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    when (page) {
                        0 -> PremisePage()
                        1 -> PermissionsPage()
                        2 -> BuildPage()
                        else -> RunPage()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(PAGE_COUNT) { i ->
                            val active = i == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                            enabled = pagerState.currentPage > 0,
                        ) {
                            Text("Back")
                        }
                        Button(onClick = {
                            if (onLastPage) {
                                onDismiss()
                            } else {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        }) {
                            Text(if (onLastPage) "Got it" else "Next")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun NumberedRow(number: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun PremisePage() {
    PageColumn {
        Kicker("What it does")
        Text(
            text = "Watch for an image.\nTap where you choose.",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "A rule watches part of the screen for an image. When it appears, " +
                "the app taps the spot you picked — until you stop it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "No root, no ADB. Everything stays on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    // The same checks the rest of the app reports with: the service being bound
    // (not merely enabled-but-unbound) and the overlay grant. Polled on the same
    // 2s cadence as every other live card here, so the tick lands after returning
    // from the Android settings screen without wiring lifecycle callbacks.
    var accessOn by remember { mutableStateOf(ClickerAccessibilityService.isRunning) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            accessOn = ClickerAccessibilityService.isRunning
            overlayOn = Settings.canDrawOverlays(context)
            delay(2_000)
        }
    }

    PageColumn {
        Kicker("Set up")
        Text(
            text = "Two permissions",
            style = MaterialTheme.typography.titleLarge,
        )
        NumberedRow(1, "Accessibility service — see the screen and tap for you.")
        NumberedRow(2, "Display over other apps — keep the rule editor and panel on top.")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (!context.openAccessibilitySettings()) {
                        Toast.makeText(
                            context,
                            "Couldn't open Accessibility settings — turn the service on in Settings",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                enabled = !accessOn,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (accessOn) "✓ Enabled" else "Enable accessibility")
            }
            Button(
                onClick = {
                    if (!openOverlaySettings(context)) {
                        Toast.makeText(
                            context,
                            "Couldn't open Settings — allow \"display over other apps\" manually",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                enabled = !overlayOn,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (overlayOn) "✓ Allowed" else "Allow over apps")
            }
        }
    }
}

/**
 * Opens the system screen for the "display over other apps" grant. Same contract as
 * [Context.openAccessibilitySettings]: returns whether the screen opened, so a failure
 * is a message rather than a dead button.
 */
private fun openOverlaySettings(context: Context): Boolean =
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

@Composable
private fun BuildPage() {
    PageColumn {
        Kicker("Build")
        Text(
            text = "New script → Add rule",
            style = MaterialTheme.typography.titleLarge,
        )
        NumberedRow(1, "Search area — where to look.")
        NumberedRow(2, "Template area — the image to find, then Capture.")
        NumberedRow(3, "Click area — where to tap.")
        NumberedRow(4, "Check, then Save.")
        Text(
            text = "Switch app keeps the toolbar up while you bring your game forward.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunPage() {
    PageColumn {
        Kicker("Run")
        Text(
            text = "Run it",
            style = MaterialTheme.typography.titleLarge,
        )
        Text("Start from the app or the floating panel.", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "First run: Android asks what to share — pick Entire screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Options ▸: Highlight matches, Show taps, runner log.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
