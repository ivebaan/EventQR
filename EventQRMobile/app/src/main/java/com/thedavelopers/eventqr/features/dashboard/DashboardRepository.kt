package com.thedavelopers.eventqr.features.dashboard

import android.content.Context
import com.thedavelopers.eventqr.core.api.ApiClient
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.session.cachedCurrentUser
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse

open class DashboardRepository(private val context: Context) {
    private val apiService = ApiClient.getService(context)
    // Shared with the profile screen: one users/me answer per 30 s window.
    private val sessionManager by lazy { SessionManager(context.applicationContext ?: context) }

    open suspend fun getSummary(): NetworkResult<DashboardSummary> {
        return runCatching {
            apiService.getDashboard()
        }.fold(
            onSuccess = { response ->
                if (response.success && response.data != null) {
                    NetworkResult.Success(response.data, response.message)
                } else {
                    NetworkResult.Error(response.message ?: "Unable to load dashboard")
                }
            },
            onFailure = { throwable ->
                NetworkResult.Error(throwable.message ?: "Unable to load dashboard", throwable)
            }
        )
    }

    open suspend fun getCurrentUser(): NetworkResult<UserResponse> =
        cachedCurrentUser(sessionManager.getUserId()) { fetchCurrentUser() }

    internal open suspend fun fetchCurrentUser(): NetworkResult<UserResponse> {
        return runCatching {
            apiService.getUsersMe()
        }.fold(
            onSuccess = { response ->
                if (response.success && response.data != null) {
                    NetworkResult.Success(response.data, response.message)
                } else {
                    NetworkResult.Error(response.message ?: "Unable to refresh account role")
                }
            },
            onFailure = { throwable ->
                NetworkResult.Error(throwable.message ?: "Unable to refresh account role", throwable)
            }
        )
    }
}
