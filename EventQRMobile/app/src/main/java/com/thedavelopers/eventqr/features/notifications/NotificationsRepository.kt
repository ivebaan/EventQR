package com.thedavelopers.eventqr.features.notifications

import android.content.Context
import com.thedavelopers.eventqr.core.api.ApiClient
import com.thedavelopers.eventqr.core.api.safeApiCall

open class NotificationsRepository(context: Context) {
    private val apiService = ApiClient.getService(context)

    /** The list screen wants the whole history; the decision sweep asks for `unreadOnly` only. */
    open suspend fun getMyNotifications(unreadOnly: Boolean? = null) =
        safeApiCall { apiService.getMyNotifications(unreadOnly = unreadOnly) }

    open suspend fun markNotificationRead(notificationId: String) =
        safeApiCall { apiService.markNotificationRead(notificationId) }

    open suspend fun markAllNotificationsRead() = safeApiCall { apiService.markAllNotificationsRead() }
}
