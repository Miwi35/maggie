package com.maggie.app.ui.screens.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.maggie.app.R
import com.maggie.app.data.auth.BiometricLockManager
import kotlinx.coroutines.delay

private val DarkBackground = Color(0xFF1A1A2E)
private val SubtitleColor = Color(0x99FFFFFF)

@Composable
fun LockScreen(lockManager: BiometricLockManager) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var unlocking by remember { mutableStateOf(false) }

    fun onAuthSuccess() {
        unlocking = true
    }

    LaunchedEffect(unlocking) {
        if (unlocking) {
            delay(800)
            lockManager.unlock()
        }
    }

    LaunchedEffect(Unit) {
        if (activity == null) return@LaunchedEffect
        if (!lockManager.canAuthenticate(activity)) {
            unlocking = true
            return@LaunchedEffect
        }
        lockManager.showBiometricPrompt(
            activity = activity,
            onSuccess = { onAuthSuccess() },
            onError = {},
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
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
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (unlocking) "Chargement..." else "Authentifiez-vous pour continuer",
            fontSize = 14.sp,
            color = SubtitleColor,
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (unlocking) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White.copy(alpha = 0.7f),
            )
        } else if (activity != null && lockManager.canAuthenticate(activity)) {
            IconButton(
                onClick = {
                    lockManager.showBiometricPrompt(
                        activity = activity,
                        onSuccess = { onAuthSuccess() },
                        onError = {},
                    )
                },
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Appuyez pour d\u00e9verrouiller",
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Appuyez pour d\u00e9verrouiller",
                fontSize = 12.sp,
                color = SubtitleColor,
            )
        }
    }
}
