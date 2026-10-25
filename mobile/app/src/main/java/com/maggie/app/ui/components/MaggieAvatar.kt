package com.maggie.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.maggie.app.R

/** Maggie's face, round. The one avatar of the drawer, the bottom bar and the chat. */
@Composable
fun MaggieAvatar(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.maggie),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}
