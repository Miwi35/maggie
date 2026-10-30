package com.maggie.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.repository.ProviderStatusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the banner « Maggie ne peut plus répondre » says; [Ok] draws nothing. */
enum class ProviderBanner(val subtitle: String?, val action: String?, val url: String?) {
    Ok(null, null, null),
    Credit("Crédit Anthropic épuisé", "Recharger", "https://console.anthropic.com/settings/billing"),
    Auth("Clé Anthropic refusée", "Ouvrir la console", "https://console.anthropic.com/settings/keys"),
}

/**
 * The provider's state (MAG-727): read when the app opens or comes back ([refresh]), then every
 * [POLL_MS] while it is not `ok`. A failed read keeps what the banner said before.
 */
class ProviderStatusViewModel(private val repository: ProviderStatusRepository) : ViewModel() {
    private val _banner = MutableStateFlow(ProviderBanner.Ok)
    val banner: StateFlow<ProviderBanner> = _banner

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                if (_banner.value != ProviderBanner.Ok) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.get().onSuccess { status ->
                _banner.value = when (status.status) {
                    "credit" -> ProviderBanner.Credit
                    "auth" -> ProviderBanner.Auth
                    else -> ProviderBanner.Ok
                }
            }
        }
    }

    companion object {
        const val POLL_MS = 5 * 60 * 1000L
    }
}
