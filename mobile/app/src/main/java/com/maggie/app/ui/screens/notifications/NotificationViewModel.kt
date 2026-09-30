package com.maggie.app.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Notification
import com.maggie.app.data.repository.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class NotificationUiState(
    val notifications: List<Notification> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class NotificationViewModel(
    private val notificationRepository: NotificationRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState

    init {
        refresh()
        subscribeToMercure()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val notifications = notificationRepository.getNotifications().getOrThrow()
                val unreadCount = notifications.count { !it.isRead }
                _uiState.value = NotificationUiState(
                    notifications = notifications,
                    unreadCount = unreadCount,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoading = false,
                )
            }
        }
    }

    fun refreshUnreadCount() {
        viewModelScope.launch {
            try {
                val count = notificationRepository.getUnreadCount().getOrDefault(0)
                _uiState.value = _uiState.value.copy(unreadCount = count)
            } catch (_: Exception) {}
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            notificationRepository.markRead(id)
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            notificationRepository.delete(id)
            refresh()
        }
    }

    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.NOTIFICATIONS))
                .catch { /* SSE reconnects automatically */ }
                .collect { refresh() }
        }
    }
}
