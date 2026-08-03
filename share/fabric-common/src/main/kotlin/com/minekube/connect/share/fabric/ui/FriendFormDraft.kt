package com.minekube.connect.share.fabric.ui

data class FriendFormDraft(
    val displayName: String = "",
    val invitation: String = "",
    val offlineMode: Boolean = false,
    val internetDirect: Boolean = false,
) {
    fun newRequest(): FriendFormDraft = FriendFormDraft()

    companion object {
        fun forManage(displayName: String): FriendFormDraft =
            FriendFormDraft(displayName = displayName)
    }
}
