package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Notification

class NotificationRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getNotifications(unreadOnly: Boolean = false): Result<List<Notification>> = runCatching {
        apiService.getNotifications(unreadOnly = unreadOnly)
    }

    suspend fun getUnreadCount(): Result<Int> = runCatching {
        apiService.getNotifications(unreadOnly = true).size
    }

    suspend fun markRead(id: String): Result<Notification> = runCatching {
        apiService.markNotificationRead(id)
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiService.deleteNotification(id)
    }
}
