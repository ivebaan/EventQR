package com.thedavelopers.eventqr.features.admin.users

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.AccountStatus
import com.thedavelopers.eventqr.core.api.dto.PageResponse
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.admin.AdminRepository
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

/**
 * §5.5 / §6.5: the account row overflow offers Disable on a live account, Enable + Delete on an
 * inactive or suspended one, Delete alone on pending, and nothing at all on a super-admin row or
 * the signed-in user's own row. Every action asks first; a status change repaints its row in place.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AdminAccountManagementActivityTest {

    private val selfId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val otherId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    @Before
    fun setUp() {
        SessionManager(ApplicationProvider.getApplicationContext()).apply {
            clearSession()
            saveLoginResponse(
                LoginResponse(
                    accessToken = "token",
                    userId = selfId,
                    email = "admin@example.com",
                    fullName = "Admin Person",
                    role = AccountRole.ADMIN,
                    refreshToken = null,
                )
            )
        }
    }

    // --- menu matrix (the pure rule the row and the handler share) -------------------------------

    @Test
    fun activeAccount_offersDisableOnly() {
        assertEquals(
            listOf(AccountAction.DISABLE),
            accountRowActions(user(status = AccountStatus.ACTIVE), selfId.toString()),
        )
    }

    @Test
    fun inactiveAccount_offersEnableThenDelete() {
        assertEquals(
            listOf(AccountAction.ENABLE, AccountAction.DELETE),
            accountRowActions(user(status = AccountStatus.INACTIVE), selfId.toString()),
        )
    }

    @Test
    fun suspendedAccount_offersEnableThenDelete() {
        assertEquals(
            listOf(AccountAction.ENABLE, AccountAction.DELETE),
            accountRowActions(user(status = AccountStatus.SUSPENDED), selfId.toString()),
        )
    }

    @Test
    fun pendingAccount_stillOffersDeleteOnly() {
        assertEquals(
            listOf(AccountAction.DELETE),
            accountRowActions(user(status = AccountStatus.PENDING), selfId.toString()),
        )
    }

    @Test
    fun superAdminRow_offersNothing() {
        assertTrue(
            accountRowActions(
                user(status = AccountStatus.ACTIVE, role = AccountRole.SUPER_ADMIN),
                selfId.toString(),
            ).isEmpty()
        )
        assertTrue(
            accountRowActions(
                user(status = AccountStatus.INACTIVE, role = AccountRole.SUPER_ADMIN),
                selfId.toString(),
            ).isEmpty()
        )
    }

    @Test
    fun ownRow_offersNothing() {
        assertTrue(
            accountRowActions(
                user(userId = selfId, status = AccountStatus.INACTIVE),
                selfId.toString(),
            ).isEmpty()
        )
    }

    // --- bound row ------------------------------------------------------------------------------

    @Test
    fun activeRow_showsOverflowAndActiveBadge() {
        val user = user(status = AccountStatus.ACTIVE)
        val activity = activityWith(user)

        val row = activity.boundRow(0)
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.actionMenu).visibility)
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_status_active),
            row.findViewById<TextView>(R.id.textAccountStatusBadge).text.toString(),
        )
    }

    @Test
    fun superAdminRow_hidesOverflow() {
        val activity = activityWith(user(status = AccountStatus.ACTIVE, role = AccountRole.SUPER_ADMIN))

        val row = activity.boundRow(0)
        assertEquals(View.GONE, row.findViewById<View>(R.id.actionMenu).visibility)
        assertTrue(activity.adapter.actionsAt(0).isEmpty())
    }

    @Test
    fun ownRow_hidesOverflow() {
        val activity = activityWith(user(userId = selfId, status = AccountStatus.INACTIVE))

        val row = activity.boundRow(0)
        assertEquals(View.GONE, row.findViewById<View>(R.id.actionMenu).visibility)
        assertTrue(activity.adapter.actionsAt(0).isEmpty())
    }

    @Test
    fun overflowIsTakenOutOfPlayWhileTheRequestRuns() {
        val user = user(status = AccountStatus.ACTIVE)
        val activity = activityWith(user)

        activity.adapter.setPending(user.userId)
        val row = activity.boundRow(0)
        val menu = row.findViewById<View>(R.id.actionMenu)
        assertFalse(menu.isEnabled)

        activity.adapter.setPending(null)
        assertTrue(activity.boundRow(0).findViewById<View>(R.id.actionMenu).isEnabled)
    }

    // --- disable ---------------------------------------------------------------------------------

    @Test
    fun disableAsksFirst_andRepaintsTheRowInPlace() {
        val user = user(status = AccountStatus.ACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DISABLE)
        // Disable must confirm first; nothing is sent until the commit is tapped.
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_disable_title),
            dialog.findViewById<TextView>(R.id.dialogTitle)!!.text.toString(),
        )
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_disable_body, user.fullName),
            dialog.findViewById<TextView>(R.id.dialogBody)!!.text.toString(),
        )
        assertTrue("nothing may be sent before the confirm", repo.disableCalls.isEmpty())

        dialog.findViewById<Button>(R.id.dialogPositive)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(user.userId.toString()), repo.disableCalls)
        assertEquals(AccountStatus.INACTIVE, activity.adapter.itemAt(0)?.status)
        val row = activity.boundRow(0)
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_status_inactive),
            row.findViewById<TextView>(R.id.textAccountStatusBadge).text.toString(),
        )
        // The repainted row now offers the way back.
        assertEquals(listOf(AccountAction.ENABLE, AccountAction.DELETE), activity.adapter.actionsAt(0))
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_disabled),
            activity.snackbarText(),
        )
    }

    @Test
    fun disableCancelLeavesEverythingUntouched() {
        val user = user(status = AccountStatus.ACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DISABLE)
        ShadowDialog.getLatestDialog().findViewById<Button>(R.id.dialogNegative)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(repo.disableCalls.isEmpty())
        assertEquals(AccountStatus.ACTIVE, activity.adapter.itemAt(0)?.status)
        assertNull(activity.snackbarText())
    }

    @Test
    fun disableFailureShowsTheServerSentence_andKeepsTheRow() {
        val user = user(status = AccountStatus.ACTIVE)
        val repo = FakeRepo().apply {
            disableFailure = NetworkResult.Error("boom", serverMessage = "Cannot disable this account")
        }
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DISABLE)
        ShadowDialog.getLatestDialog().findViewById<Button>(R.id.dialogPositive)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(user.userId.toString()), repo.disableCalls)
        assertEquals(AccountStatus.ACTIVE, activity.adapter.itemAt(0)?.status)
        assertEquals(listOf(AccountAction.DISABLE), activity.adapter.actionsAt(0))
        assertEquals("Cannot disable this account", activity.snackbarText())
    }

    // --- enable ----------------------------------------------------------------------------------

    @Test
    fun enableRestoresAccess_afterConfirm() {
        val user = user(status = AccountStatus.INACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.ENABLE)
        val dialog = ShadowDialog.getLatestDialog()
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_enable_title),
            dialog.findViewById<TextView>(R.id.dialogTitle)!!.text.toString(),
        )
        assertTrue(repo.enableCalls.isEmpty())

        dialog.findViewById<Button>(R.id.dialogPositive)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(user.userId.toString()), repo.enableCalls)
        assertEquals(AccountStatus.ACTIVE, activity.adapter.itemAt(0)?.status)
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_status_active),
            activity.boundRow(0).findViewById<TextView>(R.id.textAccountStatusBadge).text.toString(),
        )
        assertEquals(listOf(AccountAction.DISABLE), activity.adapter.actionsAt(0))
        assertEquals(activity.getString(R.string.ui_admin_accounts_enabled), activity.snackbarText())
    }

    // --- self and delete rules -------------------------------------------------------------------

    @Test
    fun ownRow_actionIsRefusedWithTheSelfMessage() {
        val own = user(userId = selfId, status = AccountStatus.ACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(own, repo = repo)

        activity.onAccountActionClick(own, AccountAction.DISABLE)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("no dialog for your own row", ShadowDialog.getLatestDialog())
        assertTrue(repo.disableCalls.isEmpty())
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_self_disable),
            activity.snackbarText(),
        )
    }

    @Test
    fun deleteIsStillRefusedForAnActiveAccount() {
        val user = user(status = AccountStatus.ACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DELETE)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(ShadowDialog.getLatestDialog())
        assertTrue(repo.deleteCalls.isEmpty())
    }

    @Test
    fun deleteIsStillRefusedForASuperAdminRow() {
        val user = user(status = AccountStatus.INACTIVE, role = AccountRole.SUPER_ADMIN)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DELETE)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(ShadowDialog.getLatestDialog())
        assertTrue(repo.deleteCalls.isEmpty())
    }

    @Test
    fun deleteOfAnInactiveAccountStillConfirmsAndDeletes() {
        val user = user(status = AccountStatus.INACTIVE)
        val repo = FakeRepo()
        val activity = activityWith(user, repo = repo)

        activity.onAccountActionClick(user, AccountAction.DELETE)
        val dialog = ShadowDialog.getLatestDialog()
        assertEquals(
            activity.getString(R.string.ui_admin_accounts_delete_body, user.fullName),
            dialog.findViewById<TextView>(R.id.dialogBody)!!.text.toString(),
        )
        dialog.findViewById<Button>(R.id.dialogPositive)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(user.userId.toString()), repo.deleteCalls)
    }

    // --- fixture ---------------------------------------------------------------------------------

    private fun user(
        userId: UUID = otherId,
        status: AccountStatus,
        role: AccountRole = AccountRole.ORGANIZER,
    ) = UserResponse(
        userId = userId,
        email = "member@example.com",
        fullName = "Maria Santos",
        phoneNumber = null,
        role = role,
        status = status,
    )

    private fun activityWith(vararg users: UserResponse, repo: FakeRepo = FakeRepo()): AdminAccountManagementActivity {
        repo.users = users.toMutableList()
        val controller = Robolectric.buildActivity(AdminAccountManagementActivity::class.java).create()
        val activity = controller.get()
        activity.repositoryOverride = repo
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun AdminAccountManagementActivity.boundRow(position: Int): View {
        val holder = adapter.onCreateViewHolder(FrameLayout(this), 0)
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
    }

    private fun AdminAccountManagementActivity.snackbarText(): String? {
        val host = window.decorView.snackbarHost() ?: return null
        return host.textViews().firstOrNull { it.isNotBlank() }
    }

    private fun ViewGroup.textViews(): List<String> =
        (0 until childCount).flatMap { index ->
            val child = getChildAt(index)
            when (child) {
                is TextView -> listOf(child.text.toString())
                is ViewGroup -> child.textViews()
                else -> emptyList()
            }
        }

    private fun View.snackbarHost(): ViewGroup? {
        if (this::class.java.name.contains("Snackbar") && this is ViewGroup) return this
        if (this is ViewGroup) {
            return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).snackbarHost() }
        }
        return null
    }

    private class FakeRepo : AdminRepository(ApplicationProvider.getApplicationContext()) {
        var users: MutableList<UserResponse> = mutableListOf()
        var disableFailure: NetworkResult.Error? = null
        val disableCalls = mutableListOf<String>()
        val enableCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()

        override suspend fun loadUsersPage(
            role: AccountRole?,
            query: String?,
            page: Int,
            size: Int,
        ): NetworkResult<PageResponse<UserResponse>> = NetworkResult.Success(
            PageResponse(
                content = users.toList(),
                totalElements = users.size.toLong(),
                totalPages = 1,
                number = 0,
                size = users.size,
                first = true,
                last = true,
                empty = users.isEmpty(),
            )
        )

        override suspend fun disableUser(userId: String): NetworkResult<UserResponse> {
            disableCalls += userId
            disableFailure?.let { return it }
            return NetworkResult.Success(withStatus(userId, AccountStatus.INACTIVE))
        }

        override suspend fun enableUser(userId: String): NetworkResult<UserResponse> {
            enableCalls += userId
            return NetworkResult.Success(withStatus(userId, AccountStatus.ACTIVE))
        }

        override suspend fun deleteUser(userId: String): NetworkResult<Unit> {
            deleteCalls += userId
            return NetworkResult.Success(Unit)
        }

        private fun withStatus(userId: String, status: AccountStatus): UserResponse {
            val current = users.first { it.userId.toString() == userId }
            return current.copy(status = status).also { updated ->
                users.replaceAll { if (it.userId == updated.userId) updated else it }
            }
        }
    }
}
