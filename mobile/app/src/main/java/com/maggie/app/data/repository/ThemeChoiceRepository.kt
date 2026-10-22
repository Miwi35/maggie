package com.maggie.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maggie.app.ui.theme.THEME_DEFAULT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

/**
 * The theme this phone wears: « dark », « light » or « system », [THEME_DEFAULT] until the user picks one.
 * It lives on the device, not in the account's preferences — those default to « system », which cannot
 * tell a choice from "never chose", and the admin follows them.
 */
class ThemeChoiceRepository(private val context: Context) {

    private val key = stringPreferencesKey("theme")

    val choice: Flow<String> = context.themeDataStore.data.map { it[key] ?: THEME_DEFAULT }

    suspend fun save(choice: String) {
        context.themeDataStore.edit { it[key] = choice }
    }
}
