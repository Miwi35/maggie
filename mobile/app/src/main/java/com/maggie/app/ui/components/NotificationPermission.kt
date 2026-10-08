package com.maggie.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private const val PREFS = "push"
private const val KEY_ASKED = "notification_permission_asked"

/** Whether Android will show this app's notifications: the permission (13+) and the system switch. */
fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** [notificationsAllowed], read again each time the user comes back from the system settings. */
@Composable
fun rememberNotificationsAllowed(): State<Boolean> {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed.value = notificationsAllowed(context) }
    return allowed
}

fun openAppNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Asks for the notification permission (Android 13+) once, after the sign-in, with a
 * sentence that says why. Android shows nothing without it, however well the push arrives.
 *
 * Asked once: after a refusal, Settings › Notifications says so and opens Android's settings.
 */
@Composable
fun NotificationPermissionPrompt() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val allowed by rememberNotificationsAllowed()
    var asked by remember { mutableStateOf(prefs.getBoolean(KEY_ASKED, false)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || allowed || asked) return

    fun answered() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        asked = true
    }

    AlertDialog(
        onDismissRequest = ::answered,
        title = { Text("Recevoir les notifications ?") },
        text = {
            Text(
                "Maggie vous prévient d'un rappel, d'une demande de validation ou d'un message, " +
                    "même quand l'application est fermée. Sans cette autorisation, Android n'affiche rien.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                answered()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Autoriser") }
        },
        dismissButton = { TextButton(onClick = ::answered) { Text("Plus tard") } },
    )
}

/** The line Settings › Notifications shows when Android would drop every notification. */
@Composable
fun NotificationsBlockedNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Les notifications sont bloquées dans Android : aucun rappel ni message de Maggie ne s'affichera.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = { openAppNotificationSettings(context) }) {
                Text("Ouvrir les réglages Android")
            }
        }
    }
}
