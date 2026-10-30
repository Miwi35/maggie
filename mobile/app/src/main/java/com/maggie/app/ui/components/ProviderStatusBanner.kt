package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags

/** « Maggie ne peut plus répondre » under the header of every screen (MAG-727); not dismissable, it goes when the state is `ok`. */
@Composable
fun ProviderStatusBanner(banner: ProviderBanner, modifier: Modifier = Modifier, clearsStatusBar: Boolean = false) {
    val subtitle = banner.subtitle ?: return
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag(UiTags.PROVIDER_BANNER),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.then(if (clearsStatusBar) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("Maggie ne peut plus répondre", style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { banner.url?.let(uriHandler::openUri) },
                modifier = Modifier.testTag(UiTags.PROVIDER_BANNER_ACTION),
            ) { Text(banner.action.orEmpty()) }
        }
    }
}
