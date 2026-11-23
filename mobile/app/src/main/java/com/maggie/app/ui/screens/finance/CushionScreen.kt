package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.api.CushionConfigRequest
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.cushionStateLabel
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.components.MaggieCard
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CushionScreen(
    viewModel: CushionViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSavedMessage()
        }
    }

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = viewModel::refresh,
    )

    Scaffold(
        topBar = {
            FinanceTopBar(Screen.Cushion.route) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Trésorerie",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        val status = uiState.status

        if (status == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        uiState.error ?: "Matelas indisponible",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            CushionHead(status)
            CushionSavingCard(status)
            CushionAccounts(status)
            CushionSettingsRows(status, onOpen = { sheetOpen = true })
        }

        if (sheetOpen) {
            CushionSettingsSheet(
                status = status,
                isSaving = uiState.isSaving,
                onSave = viewModel::configure,
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

@Composable
internal fun CushionHead(status: CushionStatus) {
    val surface = maggieSurface()
    val stateColor = cushionStateColor(status.state)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Matelas", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(stateColor.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    cushionStateLabel(status.state),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = stateColor,
                )
            }
        }

        Text(formatCentsRounded(status.currentCents), fontSize = 52.sp, fontWeight = FontWeight.Light)
        Text(cushionGapLine(status), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(status.coveragePercent.coerceIn(0, 100) / 100f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaggieTokens.Rhythm.onTrack.second),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("seuil critique ${formatCentsRounded(status.criticalCents)}", fontSize = 13.sp, color = surface.caption)
            Text("cible ${formatCentsRounded(status.targetCents)}", fontSize = 13.sp, color = surface.caption)
        }
    }
}

@Composable
internal fun CushionSavingCard(status: CushionStatus) {
    MaggieCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(cushionSavingTitle(status), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            cushionSavingLine(status)?.let {
                Text(
                    text = it,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.CUSHION_SHARE_RULE),
                )
            }
        }
    }
}

@Composable
internal fun CushionAccounts(status: CushionStatus) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CushionSectionLabel("Comptes du matelas")

        if (status.accounts.isEmpty()) {
            Text(
                text = "Aucun compte n'est marqué « matelas ».",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        status.accounts.forEach { account ->
            CushionLine(
                title = account.name,
                subtitle = account.bank,
                amount = formatCentsRounded(account.balanceCents, account.currency),
            )
        }
        if (status.peerClaimsCents > 0) {
            CushionLine(
                title = "Avancé à des proches",
                subtitle = status.peerClaimObjects.joinToString(", "),
                amount = "−${formatCentsRounded(status.peerClaimsCents)}",
                amountColor = MaggieTokens.Signal.info,
                modifier = Modifier.testTag(UiTags.CUSHION_PEER_CLAIMS),
            )
        }
    }
}

@Composable
internal fun CushionSettingsRows(status: CushionStatus, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CushionSectionLabel("Réglages")
        CushionLine(
            title = "Seuil critique",
            amount = "${status.criticalMonths} mois de dépenses · ${formatCentsRounded(status.criticalCents)}",
            amountColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpen),
        )
        CushionLine(
            title = "Part du matelas",
            amount = "${status.cushionSharePercent} % au-delà du seuil",
            amountColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpen),
        )
        CushionLine(
            title = "Cible",
            amount = "${status.targetMonths} mois de salaire · ${formatCentsRounded(status.targetCents)}",
            amountColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

@Composable
private fun CushionSectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 1.7.sp, color = maggieSurface().caption)
}

@Composable
private fun CushionLine(
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 13.sp, color = maggieSurface().caption)
                }
            }
            Text(amount, fontSize = 15.sp, color = amountColor, modifier = Modifier.padding(start = 12.dp))
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CushionSettingsSheet(
    status: CushionStatus,
    isSaving: Boolean,
    onSave: (CushionConfigRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var targetMonths by remember(status.targetMonths) {
        mutableStateOf(status.targetMonths.toString())
    }
    var income by remember(status.monthlyNetIncomeCents) {
        mutableStateOf((status.monthlyNetIncomeCents / 100.0).toString())
    }
    var cap by remember(status.rechargeCapCents) {
        mutableStateOf((status.rechargeCapCents / 100.0).toString())
    }
    var criticalMonths by remember(status.criticalMonths) {
        mutableStateOf(status.criticalMonths.toString())
    }
    var sharePercent by remember(status.cushionSharePercent) {
        mutableStateOf(status.cushionSharePercent.toString())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.uiTagRoot()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = targetMonths,
                onValueChange = { targetMonths = it },
                label = { Text("Cible (mois de revenu)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = income,
                onValueChange = { income = it },
                label = { Text("Revenu net mensuel (€)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = criticalMonths,
                onValueChange = { criticalMonths = it },
                label = { Text("Seuil critique (mois de dépenses)") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.CUSHION_CRITICAL_MONTHS),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = sharePercent,
                onValueChange = { sharePercent = it },
                label = { Text("Part du matelas (%)") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.CUSHION_SHARE_PERCENT),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = cap,
                onValueChange = { cap = it },
                label = { Text("Plafond de recharge (€/mois)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Button(
                onClick = {
                    onSave(
                        CushionConfigRequest(
                            targetMonths = targetMonths.toIntOrNull(),
                            monthlyNetIncomeCents = income.toDoubleOrNull()
                                ?.let { (it * 100).roundToInt() },
                            rechargeCapCents = cap.toDoubleOrNull()
                                ?.let { (it * 100).roundToInt() },
                            criticalMonths = criticalMonths.toIntOrNull(),
                            cushionSharePercent = sharePercent.toIntOrNull(),
                        ),
                    )
                    onDismiss()
                },
                enabled = !isSaving,
                modifier = Modifier.testTag(UiTags.CUSHION_SAVE),
            ) {
                Text("Enregistrer")
            }
        }
    }
}

private fun cushionStateColor(state: String): Color = when (state) {
    "complete" -> MaggieTokens.Rhythm.onTrack.second
    "recharging" -> MaggieTokens.Signal.info
    else -> MaggieTokens.Signal.warning
}

internal fun cushionGapLine(status: CushionStatus): String {
    if (status.deficitCents <= 0) return "La cible est atteinte."

    val lent = min(status.deficitCents, status.peerClaimsCents)
    val tail = if (lent > 0) ", dont ${formatCentsRounded(lent)} avancés à des proches" else ""

    return "Il manque ${formatCentsRounded(status.deficitCents)}$tail."
}

internal fun cushionSavingTitle(status: CushionStatus): String = when {
    status.criticalDeficitCents > 0 -> "Sous le seuil critique"
    status.deficitCents > 0 -> "Au-dessus du seuil critique"
    else -> "Rien à faire"
}

internal fun cushionSavingLine(status: CushionStatus): String? {
    if (status.deficitCents <= 0) return null
    val rule = when {
        status.criticalDeficitCents > 0 -> "Toute l'épargne va au matelas"
        status.cushionSharePercent >= 100 -> "Matelas d'abord : toute l'épargne va au matelas"
        else -> "Seuil critique atteint : ${status.cushionSharePercent} % de l'épargne va au matelas"
    }
    if (status.monthlyRechargeCents <= 0) return "$rule."
    val separator = if (status.criticalDeficitCents > 0) " : " else ", "

    return "$rule$separator${formatCentsRounded(status.monthlyRechargeCents)} par mois pendant ${status.rechargeMonths} mois."
}
