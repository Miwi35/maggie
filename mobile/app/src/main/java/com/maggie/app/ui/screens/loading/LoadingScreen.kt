package com.maggie.app.ui.screens.loading

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.maggie.app.R
import com.maggie.app.ui.theme.MaggieTokens

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaggieTokens.Night.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.maggie),
            contentDescription = "Maggie",
            modifier = Modifier.width(180.dp),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Image(
            painter = painterResource(R.drawable.maggie_logo),
            contentDescription = "Maggie",
            modifier = Modifier.width(180.dp),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}
