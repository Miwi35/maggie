package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.BANK_COUNTRIES
import com.maggie.app.data.model.Bank
import com.maggie.app.ui.UiTags

/**
 * Where a first bank is chosen: a full-screen search over what the provider offers
 * in a country, never an inline dropdown. Picking a bank starts the journey and
 * goes back to the list, which opens the browser.
 */
@Composable
fun BankPickerScreen(
    viewModel: BankConnectionViewModel,
    onBack: () -> Unit,
    onPicked: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadBanks(uiState.banksCountry) }

    BankPickerContent(
        banks = uiState.banks,
        loading = uiState.banksLoading,
        country = uiState.banksCountry,
        onCountry = viewModel::loadBanks,
        onPick = { bank ->
            viewModel.connect(bank.name, uiState.banksCountry)
            onPicked()
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BankPickerContent(
    banks: List<Bank>,
    loading: Boolean,
    country: String,
    onCountry: (String) -> Unit,
    onPick: (Bank) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(banks, query) {
        banks
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choisir une banque") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher une banque") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(UiTags.BANK_PICKER_SEARCH),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BANK_COUNTRIES.forEach { code ->
                    FilterChip(
                        selected = code == country,
                        onClick = { onCountry(code) },
                        label = { Text(code) },
                    )
                }
            }
            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                "Aucune banque ne correspond.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(shown, key = { it.name }) { bank ->
                        Text(
                            bank.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(bank) }
                                .padding(16.dp)
                                .testTag(UiTags.BANK_PICKER_ITEM),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
