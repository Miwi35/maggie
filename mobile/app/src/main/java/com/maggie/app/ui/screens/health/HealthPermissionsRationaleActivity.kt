package com.maggie.app.ui.screens.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTheme
import com.maggie.app.ui.uiTagRoot

/**
 * Why Maggie asks for the Health Connect permissions (MAG-708): opened by Health Connect's permission screen
 * (`ACTION_SHOW_PERMISSIONS_RATIONALE`) and, on Android 14+, by the system's permission usage screen.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaggieTheme {
                Box(modifier = Modifier.fillMaxSize().uiTagRoot()) {
                    HealthPermissionsRationale(onClose = ::finish)
                }
            }
        }
    }
}

@Composable
fun HealthPermissionsRationale(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.HEALTH_RATIONALE)) {
        Box(modifier = Modifier.safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Maggie et Health Connect", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Maggie lit dans Health Connect vos séances, vos pesées, vos pas et votre dépense active, " +
                        "pour tenir à jour Santé › Suivi et Sport et calculer votre besoin du jour.",
                )
                Text(
                    "Elle y écrit les séances que vous notez dans Maggie, pour que vos autres applications " +
                        "ne les comptent pas deux fois.",
                )
                Text("Ces données ne servent qu'à Maggie : elles ne sont ni partagées ni revendues.")
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Fermer") }
            }
        }
    }
}
