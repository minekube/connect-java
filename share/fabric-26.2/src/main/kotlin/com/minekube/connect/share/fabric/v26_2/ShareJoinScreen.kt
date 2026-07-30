package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.FriendCardExchangeConsent
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.ui.FriendSummary
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareJoinScreen(
    private val parent: Screen,
    private val friends: FriendsViewModel,
    private val browser: FabricShareBrowser,
    private val remotePresence: FriendPresenceMonitor,
) : Screen(Component.translatable("connect_share.friends.title")) {
    private var scope: CoroutineScope? = null
    private var mode = Mode.FRIENDS
    private var selectedPeerId: String? = null
    private var nameValue = ""
    private var invitationValue = ""
    private var offlineSelected = false
    private var internetSelected = false
    private var nameBox: EditBox? = null
    private var invitationBox: EditBox? = null
    private var offlineMode: Checkbox? = null
    private var internetDirect: Checkbox? = null
    private var primaryButton: Button? = null
    private var secondaryButton: Button? = null
    private var safeMessage: String? = null
    private var fingerprint = 0
    private var joining = false
    private var joiningPeerId: String? = null
    private var reciprocalPairing = false
    private var transferred = false

    override fun init() {
        if (scope == null) {
            scope = CoroutineScope(
                SupervisorJob() + minecraft.asCoroutineDispatcher(),
            )
            browser.start().onLeft { safeMessage = it.safeMessage }
        }
        friends.updatePresence(browser.discovered.value)
        friends.updateRemotePresence(remotePresence.state.value)
        fingerprint = currentFingerprint()
        nameBox = null
        invitationBox = null
        primaryButton = null
        secondaryButton = null

        when (mode) {
            Mode.FRIENDS -> buildFriends()
            Mode.ADD -> buildAddFriend()
            Mode.MANAGE -> buildManageFriend()
        }
    }

    override fun tick() {
        super.tick()
        friends.updatePresence(browser.discovered.value)
        friends.updateRemotePresence(remotePresence.state.value)
        val next = currentFingerprint()
        if (next != fingerprint) {
            rebuildWidgets()
        } else {
            refresh()
        }
    }

    override fun onClose() {
        when (mode) {
            Mode.FRIENDS -> minecraft.gui.setScreen(parent)
            Mode.ADD,
            Mode.MANAGE,
            -> {
                mode = Mode.FRIENDS
                selectedPeerId = null
                safeMessage = null
                rebuildWidgets()
            }
        }
    }

    override fun removed() {
        scope?.cancel()
        scope = null
        if (!transferred) {
            browser.close()
        }
        super.removed()
    }

    private fun buildFriends() {
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.title"),
                16,
            ),
        )
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.description"),
                34,
            ).setMaxWidth(CONTENT_WIDTH),
        )

        val saved = friends.state.value.friends.take(MAX_VISIBLE_FRIENDS)
        if (saved.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.friends.empty"),
                    82,
                ).setMaxWidth(CONTENT_WIDTH),
            )
        } else {
            saved.forEachIndexed { index, friend ->
                val y = 58 + index * 26
                addRenderableWidget(
                    Button.builder(friendLabel(friend)) {
                        joinSaved(friend.peerId)
                    }.bounds(width / 2 - 155, y, 242, 20).build(),
                )
                addRenderableWidget(
                    Button.builder(
                        Component.translatable(
                            "connect_share.friends.manage",
                        ),
                    ) {
                        selectedPeerId = friend.peerId
                        nameValue = friend.displayName
                        mode = Mode.MANAGE
                        safeMessage = null
                        rebuildWidgets()
                    }.bounds(width / 2 + 91, y, 64, 20).build(),
                )
            }
        }

        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centered(Component.literal(message), height - 54)
                        .setMaxWidth(CONTENT_WIDTH),
                )
            }
        }
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.add"),
            ) {
                mode = Mode.ADD
                safeMessage = null
                rebuildWidgets()
            }.bounds(width / 2 - 155, height - 28, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 5, height - 28, 150, 20)
                .build(),
        )
    }

    private fun buildAddFriend() {
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.add"),
                16,
            ),
        )
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.add_description"),
                34,
            ).setMaxWidth(CONTENT_WIDTH),
        )
        nameBox = addRenderableWidget(
            EditBox(
                font,
                width / 2 - 155,
                58,
                310,
                20,
                Component.translatable("connect_share.friends.name"),
            ).apply {
                setMaxLength(64)
                setHint(Component.translatable("connect_share.friends.name_hint"))
                setValue(nameValue)
                setResponder {
                    nameValue = it
                    refresh()
                }
            },
        )
        invitationBox = addRenderableWidget(
            EditBox(
                font,
                width / 2 - 155,
                84,
                310,
                20,
                Component.translatable("connect_share.join.invitation"),
            ).apply {
                setMaxLength(MAX_INVITATION_LENGTH)
                setHint(
                    Component.translatable(
                        "connect_share.join.invitation_hint",
                    ),
                )
                setValue(invitationValue)
                setResponder {
                    invitationValue = it
                    refresh()
                }
            },
        )
        offlineMode = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.join.offline"),
                font,
            ).pos(width / 2 - 155, 112)
                .selected(offlineSelected)
                .onValueChange { _, selected ->
                    offlineSelected = selected
                }
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.join.offline.tooltip",
                        ),
                    ),
                )
                .build(),
        )
        internetDirect = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.join.internet"),
                font,
            ).pos(width / 2 - 155, 134)
                .selected(internetSelected)
                .onValueChange { _, selected ->
                    internetSelected = selected
                }
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.join.internet.tooltip",
                        ),
                    ),
                )
                .build(),
        )
        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centered(Component.literal(message), 160)
                        .setMaxWidth(CONTENT_WIDTH),
                )
            }
        }
        primaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.save"),
            ) {
                if (friends.accept(invitationValue, nameValue)) {
                    scope?.launch {
                        remotePresence.refresh()
                    }
                    invitationValue = ""
                    nameValue = ""
                    mode = Mode.FRIENDS
                    rebuildWidgets()
                } else {
                    rebuildWidgets()
                }
            }.bounds(width / 2 - 155, height - 52, 150, 20).build(),
        )
        secondaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.join_once"),
            ) {
                joinInvitation()
            }.bounds(width / 2 + 5, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20)
                .build(),
        )
        refresh()
    }

    private fun buildManageFriend() {
        val friend = selectedFriend()
        if (friend == null) {
            mode = Mode.FRIENDS
            rebuildWidgets()
            return
        }
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.manage_title",
                    friend.displayName,
                ),
                16,
            ),
        )
        nameBox = addRenderableWidget(
            EditBox(
                font,
                width / 2 - 155,
                50,
                310,
                20,
                Component.translatable("connect_share.friends.name"),
            ).apply {
                setMaxLength(64)
                setValue(nameValue.ifBlank { friend.displayName })
                setResponder {
                    nameValue = it
                    refresh()
                }
            },
        )
        val notify = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.friends.notify"),
                font,
            ).pos(width / 2 - 155, 82)
                .selected(friend.permissions.notifyWhenOnline)
                .build(),
        )
        val autoJoin = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.friends.auto_join"),
                font,
            ).pos(width / 2 - 155, 104)
                .selected(friend.permissions.canJoinAutomatically)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.friends.auto_join.tooltip",
                        ),
                    ),
                )
                .build(),
        )
        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centered(Component.literal(message), 138)
                        .setMaxWidth(CONTENT_WIDTH),
                )
            }
        }
        primaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.save_changes"),
            ) {
                friends.rename(friend.peerId, nameValue)
                friends.updatePermissions(
                    friend.peerId,
                    FriendPermissions(
                        notifyWhenOnline = notify.selected(),
                        canSeeMyWorlds =
                            friend.permissions.canSeeMyWorlds,
                        canJoinAutomatically = autoJoin.selected(),
                    ),
                )
                mode = Mode.FRIENDS
                selectedPeerId = null
                rebuildWidgets()
            }.bounds(width / 2 - 155, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.remove"),
            ) {
                minecraft.gui.setScreen(
                    ConfirmScreen(
                        { confirmed ->
                            if (confirmed) {
                                friends.remove(friend.peerId)
                                mode = Mode.FRIENDS
                                selectedPeerId = null
                            }
                            minecraft.gui.setScreen(this)
                        },
                        Component.translatable(
                            "connect_share.friends.remove_confirm.title",
                            friend.displayName,
                        ),
                        Component.translatable(
                            "connect_share.friends.remove_confirm.message",
                        ),
                    ),
                )
            }.bounds(width / 2 + 5, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20)
                .build(),
        )
        refresh()
    }

    private fun joinSaved(peerId: String) {
        if (joining) return
        joining = true
        joiningPeerId = peerId
        reciprocalPairing = true
        safeMessage = null
        refresh()
        scope?.launch {
            friends.join(
                peerId = peerId,
                browser = browser,
                authMode = authMode(),
            ).fold(
                ifLeft = ::joinFailed,
                ifRight = ::connect,
            )
        }
    }

    private fun joinInvitation() {
        if (joining || invitationValue.isBlank()) return
        joining = true
        joiningPeerId = null
        reciprocalPairing = false
        safeMessage = null
        refresh()
        scope?.launch {
            browser.join(
                invitationUri = invitationValue,
                lanAddress = null,
                internetOptIn = internetSelected,
                authMode = authMode(),
            ).fold(
                ifLeft = ::joinFailed,
                ifRight = ::connect,
            )
        }
    }

    private fun joinFailed(failure: com.minekube.connect.share.fabric.GuestJoinFailure) {
        joining = false
        joiningPeerId = null
        reciprocalPairing = false
        safeMessage = failure.safeMessage
        rebuildWidgets()
    }

    private fun connect(target: GuestJoinTarget) {
        val address = when (target) {
            is GuestJoinTarget.Connect ->
                ServerAddress.parseString(target.publicAddress)

            is GuestJoinTarget.Direct ->
                ServerAddress(
                    target.localAddress.hostString,
                    target.localAddress.port,
                )
        }
        if (target is GuestJoinTarget.Direct) {
            ConnectShareClient.holdGuestDirect(target, browser)
            transferred = true
        } else {
            browser.close()
        }
        val joiningFriend = friends.state.value.friends.firstOrNull {
            it.peerId == joiningPeerId
        }
        val data = ServerData(
            joiningFriend?.displayName ?: "Connect Share",
            address.toString(),
            ServerData.Type.OTHER,
        )
        val exchangeFriendCard = FriendCardExchangeConsent.shouldArm(
            savedFriendJoin = reciprocalPairing,
            canSeeMyWorlds =
                joiningFriend?.permissions?.canSeeMyWorlds,
        )
        if (exchangeFriendCard) {
            ConnectShareClient.armFriendCardExchange()
        }
        ConnectScreen.startConnecting(
            parent,
            minecraft,
            address,
            data,
            false,
            null,
        )
    }

    private fun refresh() {
        val inputReady = invitationValue.isNotBlank()
        primaryButton?.active = !joining &&
            when (mode) {
                Mode.ADD -> inputReady && nameValue.isNotBlank()
                Mode.MANAGE -> nameValue.isNotBlank()
                Mode.FRIENDS -> true
            }
        secondaryButton?.active = !joining && inputReady
        invitationBox?.setEditable(!joining)
        nameBox?.setEditable(!joining)
    }

    private fun friendLabel(friend: FriendSummary): Component = when {
        friend.onlineViaLan ->
            Component.translatable(
                "connect_share.friends.ready_lan",
                friend.displayName,
                friend.worldName ?: "",
            )

        friend.onlineViaConnect ->
            Component.translatable(
                "connect_share.friends.ready_connect",
                friend.displayName,
                friend.worldName ?: "",
            )

        friend.connectAvailable ->
            Component.translatable(
                "connect_share.friends.saved_connect",
                friend.displayName,
            )

        else ->
            Component.translatable(
                "connect_share.friends.saved_offline",
                friend.displayName,
            )
    }

    private fun selectedFriend(): FriendSummary? =
        friends.state.value.friends.firstOrNull {
            it.peerId == selectedPeerId
        }

    private fun safeMessage(): String? =
        safeMessage ?: friends.state.value.safeMessage

    private fun authMode(): DirectP2pAuthMode =
        if (offlineSelected) {
            DirectP2pAuthMode.OFFLINE
        } else {
            DirectP2pAuthMode.ONLINE
        }

    private fun currentFingerprint(): Int =
        31 * browser.discovered.value.hashCode() +
            31 * friends.state.value.hashCode() +
            mode.hashCode()

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(
            width / 2 - textWidth / 2,
            y,
            textWidth,
            9,
            message,
            font,
        )
    }

    private enum class Mode {
        FRIENDS,
        ADD,
        MANAGE,
    }

    private companion object {
        const val MAX_INVITATION_LENGTH = 32_768
        const val MAX_VISIBLE_FRIENDS = 5
        const val CONTENT_WIDTH = 310
    }
}
