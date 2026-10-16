package com.maggie.app.ui.screens.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.contactSearch
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/**
 * Full-screen search among the contacts (MAG-450): the one place a contact is chosen, never an
 * inline dropdown. A name no contact bears yet offers « + Créer « <name> » », so the person is
 * created and chosen in the same gesture. The caller does the rest — [onPick] with an existing
 * contact, [onCreate] with the name typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPicker(
    contacts: List<Contact>,
    onPick: (Contact) -> Unit,
    onCreate: (String) -> Unit,
    onBack: () -> Unit,
    title: String = "Associer à un contact",
    isLoading: Boolean = false,
    subtitle: String? = null,
) {
    BackHandler(onBack = onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember(contacts, query) { contactSearch(contacts, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher ou créer un contact") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(UiTags.CONTACT_PICKER_SEARCH),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                search.creatable?.let { name ->
                    item {
                        Text(
                            "+ Créer « $name »",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCreate(name) }
                                .testTag(UiTags.CONTACT_PICKER_CREATE)
                                .padding(16.dp),
                        )
                        HorizontalDivider()
                    }
                }
                if (isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator() }
                    }
                } else if (search.results.isEmpty() && search.creatable == null) {
                    item {
                        Text(
                            "Aucun contact. Tapez un nom pour le créer.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(search.results, key = { it.id }) { contact ->
                    Text(
                        contact.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(contact) }
                            .testTag(UiTags.CONTACT_PICKER_RESULT)
                            .padding(16.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The same picker in a window of its own, over the screen it is chosen from. */
@Composable
fun ContactPickerDialog(
    contacts: List<Contact>,
    onPick: (Contact) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Associer à un contact",
    isLoading: Boolean = false,
    subtitle: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.uiTagRoot()) {
            ContactPicker(
                contacts = contacts,
                onPick = onPick,
                onCreate = onCreate,
                onBack = onDismiss,
                title = title,
                isLoading = isLoading,
                subtitle = subtitle,
            )
        }
    }
}
