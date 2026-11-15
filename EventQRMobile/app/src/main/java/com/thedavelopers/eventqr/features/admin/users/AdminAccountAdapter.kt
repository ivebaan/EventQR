package com.thedavelopers.eventqr.features.admin.users

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.AccountStatus
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import com.thedavelopers.eventqr.ui.components.ListRowBinder
import java.util.UUID

enum class AccountAction {
    ENABLE,
    DISABLE,
    DELETE,
}

/**
 * The overflow's contents for one row (5.5). ACTIVE offers Disable alone; INACTIVE and SUSPENDED
 * offer Enable then Delete; PENDING keeps its Delete-only rule. A SUPER_ADMIN row and the
 * signed-in user's own row offer nothing, so their overflow is hidden outright - the server
 * rejects both cases anyway.
 */
internal fun accountRowActions(user: UserResponse, selfUserId: String?): List<AccountAction> {
    if (user.role == AccountRole.SUPER_ADMIN) return emptyList()
    if (selfUserId != null && user.userId.toString() == selfUserId) return emptyList()
    return when (user.status) {
        AccountStatus.ACTIVE -> listOf(AccountAction.DISABLE)
        AccountStatus.INACTIVE, AccountStatus.SUSPENDED ->
            listOf(AccountAction.ENABLE, AccountAction.DELETE)
        AccountStatus.PENDING -> listOf(AccountAction.DELETE)
        else -> emptyList()
    }
}

/**
 * Account rows (5.5). One badge for the account status, chosen from the shared tone table
 * (5.4); the role is not shown because role is not a state. No per-status drawables and no
 * per-role colour tiles. The overflow carries this row's own actions, and it is hidden when the
 * row has none.
 *
 * The adapter owns the row list, so a status change repaints that one row instead of reloading
 * the page: [updateUser] swaps the row and [setPending] takes the overflow out of play while the
 * request is in flight.
 */
class AdminAccountAdapter(
    private val selfUserId: String?,
    private val onActionClick: (UserResponse, AccountAction) -> Unit,
) : RecyclerView.Adapter<AdminAccountAdapter.AdminAccountViewHolder>() {

    private val items = mutableListOf<UserResponse>()
    private var pendingUserId: UUID? = null

    fun submitItems(users: List<UserResponse>) {
        items.clear()
        items.addAll(users)
        notifyDataSetChanged()
    }

    /** Replaces one row with the server's fresh copy; no page reload, no scroll movement. */
    fun updateUser(user: UserResponse) {
        val index = items.indexOfFirst { it.userId == user.userId }
        if (index < 0) return
        items[index] = user
        notifyItemChanged(index)
    }

    /** Blocks the overflow of [userId]'s row while its request runs; null restores it. */
    fun setPending(userId: UUID?) {
        if (pendingUserId == userId) return
        val previous = pendingUserId
        pendingUserId = userId
        listOfNotNull(previous, userId).distinct().forEach { id ->
            val index = items.indexOfFirst { it.userId == id }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    internal fun itemAt(position: Int): UserResponse? = items.getOrNull(position)

    /** The overflow's contents for a loaded row, without opening the popup. */
    internal fun actionsAt(position: Int): List<AccountAction> =
        items.getOrNull(position)?.let { accountRowActions(it, selfUserId) }.orEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdminAccountViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_account, parent, false)
        return AdminAccountViewHolder(
            itemView = view,
            selfUserId = selfUserId,
            pendingUserIdProvider = { pendingUserId },
            onActionClick = onActionClick,
        )
    }

    override fun onBindViewHolder(holder: AdminAccountViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class AdminAccountViewHolder(
        itemView: View,
        private val selfUserId: String?,
        private val pendingUserIdProvider: () -> UUID?,
        private val onActionClick: (UserResponse, AccountAction) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val textAvatar: TextView = itemView.findViewById(R.id.textAccountAvatar)
        private val textName: TextView = itemView.findViewById(R.id.textAccountName)
        private val textEmail: TextView = itemView.findViewById(R.id.textAccountEmail)
        private val textStatus: TextView = itemView.findViewById(R.id.textAccountStatusBadge)
        private val actionMenu: View = itemView.findViewById(R.id.actionMenu)

        fun bind(user: UserResponse) {
            textAvatar.text = user.fullName.trim().take(1).uppercase().ifBlank { "U" }
            textName.text = user.fullName
            // Role is not a state, so it stays meta text - but it must be visible before delete.
            textEmail.text = itemView.context.getString(
                R.string.ui_admin_accounts_meta,
                user.email,
                roleLabel(user.role),
            )
            textStatus.text = statusLabel(user.status)
            ListRowBinder.applyBadgeTone(textStatus, toneFor(user.status))
            // No action on this row means no overflow at all: never a menu that opens empty.
            val actions = accountRowActions(user, selfUserId)
            actionMenu.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
            if (actions.isEmpty()) {
                actionMenu.setOnClickListener(null)
                return
            }
            // The row announces whose menu this is; a bare "More" is ambiguous in a long list.
            actionMenu.contentDescription =
                itemView.context.getString(R.string.ui_admin_accounts_actions_for, user.fullName)
            // One request at a time per row: a double tap cannot fire two status changes.
            val pending = user.userId == pendingUserIdProvider()
            actionMenu.isEnabled = !pending
            if (pending) {
                actionMenu.setOnClickListener(null)
            } else {
                actionMenu.setOnClickListener { showActionMenu(user, actions) }
            }
        }

        private fun showActionMenu(user: UserResponse, actions: List<AccountAction>) {
            val menu = PopupMenu(itemView.context, actionMenu)
            menu.menuInflater.inflate(R.menu.admin_account_actions, menu.menu)
            if (AccountAction.ENABLE !in actions) menu.menu.removeItem(R.id.action_enable)
            if (AccountAction.DISABLE !in actions) menu.menu.removeItem(R.id.action_disable)
            if (AccountAction.DELETE !in actions) menu.menu.removeItem(R.id.action_delete)
            menu.setOnMenuItemClickListener { item ->
                val action = when (item.itemId) {
                    R.id.action_enable -> AccountAction.ENABLE
                    R.id.action_disable -> AccountAction.DISABLE
                    R.id.action_delete -> AccountAction.DELETE
                    else -> return@setOnMenuItemClickListener false
                }
                onActionClick(user, action)
                true
            }
            menu.show()
        }

        private fun roleLabel(role: AccountRole): String = itemView.context.getString(
            when (role) {
                AccountRole.ATTENDEE -> R.string.ui_admin_role_attendee
                AccountRole.STAFF -> R.string.ui_admin_role_staff
                AccountRole.ORGANIZER -> R.string.ui_admin_role_organizer
                AccountRole.ADMIN -> R.string.ui_admin_role_admin
                AccountRole.SUPER_ADMIN -> R.string.ui_admin_role_super_admin
            },
        )

        private fun statusLabel(status: AccountStatus): String = itemView.context.getString(
            when (status) {
                AccountStatus.ACTIVE -> R.string.ui_admin_accounts_status_active
                AccountStatus.INACTIVE -> R.string.ui_admin_accounts_status_inactive
                AccountStatus.SUSPENDED -> R.string.ui_admin_accounts_status_suspended
                AccountStatus.PENDING -> R.string.ui_admin_accounts_status_pending
                else -> R.string.ui_admin_accounts_status_unknown
            },
        )

        /**
         * `Active` confirms nothing on this screen, so it is the only success tone. Suspended is
         * the error state; inactive/pending and an unmapped status stay neutral - never success.
         */
        private fun toneFor(status: AccountStatus): Int = when (status) {
            AccountStatus.ACTIVE -> R.style.Widget_EventQR_Ui_StatusBadge_Success
            AccountStatus.SUSPENDED -> R.style.Widget_EventQR_Ui_StatusBadge_Error
            AccountStatus.INACTIVE, AccountStatus.PENDING -> R.style.Widget_EventQR_Ui_StatusBadge_Neutral
            else -> R.style.Widget_EventQR_Ui_StatusBadge_Neutral
        }
    }
}
