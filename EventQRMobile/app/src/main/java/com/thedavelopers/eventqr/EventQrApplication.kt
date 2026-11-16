package com.thedavelopers.eventqr

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.thedavelopers.eventqr.core.navigation.AppNavigator
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.CurrentUserCache
import com.thedavelopers.eventqr.core.session.SessionEvents
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.session.SessionScopedCaches
import com.thedavelopers.eventqr.core.util.padUCropForSystemBars
import com.thedavelopers.eventqr.features.dashboard.DashboardRouter
import com.thedavelopers.eventqr.features.dashboard.RoleReroutePolicy
import com.thedavelopers.eventqr.features.notifications.EventDecisionNotifier
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.staff.AssignedEventsCache
import com.thedavelopers.eventqr.features.auth.login.LoginActivity
import com.thedavelopers.eventqr.features.auth.register.RegistrationActivity
import com.thedavelopers.eventqr.ui.theme.applyRequestedEventQrSystemBarAppearance
import com.yalantis.ucrop.UCropActivity

class EventQrApplication : Application() {
    // The activity currently in the resumed state, or null while the app is backgrounded.
    private var resumedActivity: Activity? = null

    // A role change that arrived while no dashboard was in front; applied when the user next lands on one.
    private var pendingRole: String? = null

    override fun onCreate() {
        super.onCreate()
        // Every process cache that holds the signed-in user's data; SessionManager clears them all through
        // SessionScopedCaches on login, logout and session expiry, without core importing a feature package.
        SessionScopedCaches.register(LastKnownCache.get(this))
        SessionScopedCaches.register { RegistrationsCache.clear() }
        SessionScopedCaches.register { CurrentUserCache.invalidate() }
        SessionScopedCaches.register { OrganizerEventsCache.shared.clear() }
        SessionScopedCaches.register { AssignedEventsCache.shared.clear() }
        // The remembered organizer selection is the previous session's pick; it must not survive a logout,
        // a login or an expiry either. The clearer reads the latest context off the companion instead of
        // capturing this Application: the registry is process-static and would otherwise pin every instance.
        appContext = this
        SessionScopedCaches.register {
            appContext
                ?.getSharedPreferences(OrganizerRepository.SELECTION_PREFS, MODE_PRIVATE)
                ?.edit()
                ?.clear()
                ?.commit()
        }
        // Start the slow Keystore/EncryptedSharedPreferences init now, in parallel with activity creation.
        SessionManager.warmUp(this)
        EventDecisionNotifier.schedule(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Pre-35 devices only draw behind the status bar once asked to; 35+ enforces it.
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity is UCropActivity) {
                    // Inset hook lives here, not in onActivityCreated: Activity.onCreate dispatches
                    // onActivityCreated from its super call, before UCropActivity calls setContentView, so the
                    // crop views do not exist yet there. onStart always runs after onCreate.
                    activity.padUCropForSystemBars()
                    // UCrop paints a near-white status bar (#f5f5f5 / its white toolbar on 35+) and a dark
                    // controls bar (#20242F), so its icons invert the app default. The shared pass is skipped:
                    // it forces light navigation icons and resamples the decor, which the crop photo would flip.
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = false
                    }
                } else {
                    activity.applyRequestedEventQrSystemBarAppearance()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                if (activity is LoginActivity || activity is RegistrationActivity) {
                    // Signed-out screens: a pending role belongs to the session that just ended.
                    pendingRole = null
                } else if (activity::class.java in DashboardRouter.dashboardClasses) {
                    pendingRole?.let { role ->
                        pendingRole = null
                        rerouteIfNeeded(activity, role)
                    }
                }
                if (activity::class.java in DashboardRouter.dashboardClasses) {
                    // Foreground sweep for decisions that arrived while the app was closed or idle.
                    EventDecisionNotifier.sweepInBackground(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        SessionEvents.onRoleChanged = { role ->
            // A token refresh (on a 401 or on launch/resume) or auth/me found a different role than the one the
            // dashboard launched with; it can arrive on an OkHttp/IO thread. Re-route only while a dashboard is in
            // front (never on login/registration or a sub-screen); otherwise remember it for the next dashboard
            // visit. The persisted role already applies to the next cold launch.
            Handler(Looper.getMainLooper()).post {
                val foreground = resumedActivity
                when {
                    foreground != null && foreground::class.java in DashboardRouter.dashboardClasses ->
                        rerouteIfNeeded(foreground, role)
                    foreground is LoginActivity || foreground is RegistrationActivity -> Unit
                    else -> pendingRole = role
                }
            }
        }
        SessionEvents.onSessionExpired = {
            // Called from an OkHttp thread once the server has refused the refresh token.
            Handler(Looper.getMainLooper()).post {
                pendingRole = null
                // Last-known data must never outlive the session it belongs to.
                SessionScopedCaches.clearAll()
                Toast.makeText(this, this.getString(R.string.event_qr_application_your_session_has_expired_please_sign), Toast.LENGTH_LONG).show()
                startActivities(AppNavigator.signedOutStack(this))
            }
        }
    }

    private fun rerouteIfNeeded(foreground: Activity, role: String) {
        if (!RoleReroutePolicy.shouldReroute(foreground::class.java, role)) return
        Toast.makeText(this, getString(R.string.event_qr_application_your_role_was_updated), Toast.LENGTH_SHORT).show()
        // Cross-fade into the new dashboard instead of the app's default instant swap, so the change reads as an
        // update rather than a crash-and-relaunch.
        val fade = ActivityOptions.makeCustomAnimation(foreground, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(
            DashboardRouter.intentFor(this, role)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            fade.toBundle()
        )
    }

    private companion object {
        /**
         * The latest application context, for clearers registered in the process-static
         * [SessionScopedCaches] registry: capturing `this` in a lambda there would pin every instance.
         */
        @Volatile
        private var appContext: Application? = null
    }
}
