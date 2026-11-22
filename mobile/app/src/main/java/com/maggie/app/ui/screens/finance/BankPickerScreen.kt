package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.maggie.app.ui.theme.MaggieTokens
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.maggie.app.ui.components.maggieCardContainer
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.uiTagRoot

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
                title = { Text("Connecter une banque") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            val green = MaggieTokens.Signal.success
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Rechercher une banque") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = maggieCardContainer(isMaggieDark(), true),
                    unfocusedContainerColor = maggieCardContainer(isMaggieDark(), true),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(1.dp, green, RoundedCornerShape(12.dp))
                    .testTag(UiTags.BANK_PICKER_SEARCH),
            )
            CountryPill(country = country, onCountry = onCountry)
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
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(bank) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag(UiTags.BANK_PICKER_ITEM),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape)
                                    .background(maggieCardContainer(isMaggieDark(), true)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    bank.name.trim().take(1).uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                bank.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val COUNTRY_NAMES = mapOf("FR" to "France", "CH" to "Suisse", "DE" to "Allemagne", "BE" to "Belgique")

@Composable
private fun CountryPill(country: String, onCountry: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(maggieCardContainer(isMaggieDark(), true))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(COUNTRY_NAMES[country] ?: country, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.uiTagRoot()) {
            BANK_COUNTRIES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(COUNTRY_NAMES[code] ?: code) },
                    onClick = {
                        open = false
                        onCountry(code)
                    },
                )
            }
        }
    }
}
