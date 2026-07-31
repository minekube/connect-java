package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.FriendCardExchangeConsent
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.fabric.ui.FriendSummary
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.util.UUID

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
    private var removeConfirmation = false
    private var friendLinkState = FriendLinkState.IDLE
    private var requestOperationInProgress = false
    private val requestJobs = mutableMapOf<String, Job>()
    private val requestStates =
        mutableMapOf<String, RequestDeliveryState>()

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
        if (mode == Mode.MANAGE && removeConfirmation) {
            removeConfirmation = false
            rebuildWidgets()
            return
        }
        when (mode) {
            Mode.FRIENDS -> minecraft.setScreen(parent)
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

        val state = friends.state.value
        val outgoing = state.outgoingRequests.take(MAX_VISIBLE_RELATIONSHIPS)
        val saved = state.friends.take(
            MAX_VISIBLE_RELATIONSHIPS - outgoing.size,
        )
        if (outgoing.isEmpty() && saved.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.friends.empty"),
                    82,
                ).setMaxWidth(CONTENT_WIDTH),
            )
        }
        outgoing.forEachIndexed { index, request ->
            val y = 58 + index * 26
            val deliveryState = requestStates[request.peerId]
            addRenderableWidget(
                StringWidget(
                    width / 2 - 155,
                    y,
                    174,
                    20,
                    outgoingRequestLabel(
                        request.displayName,
                        deliveryState,
                    ),
                    font,
                ),
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        deliveryState?.translationKey
                            ?: "connect_share.friends.retry_request",
                    ),
                ) {
                    deliverOutgoing(request.peerId)
                }.bounds(width / 2 + 23, y, 62, 20).build().apply {
                    active = deliveryState == null ||
                        deliveryState == RequestDeliveryState.FAILED
                },
            )
            addRenderableWidget(
                Button.builder(
                    Component.translatable(
                        "connect_share.friends.cancel_request",
                    ),
                ) {
                    cancelOutgoing(request.peerId)
                }.bounds(width / 2 + 89, y, 66, 20).build(),
            )
        }
        saved.forEachIndexed { index, friend ->
            val y = 58 + (outgoing.size + index) * 26
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

        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centered(Component.literal(message), height - 76)
                        .setMaxWidth(CONTENT_WIDTH),
                )
            }
        }
        primaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable(friendLinkState.translationKey),
            ) {
                copyMyFriendLink()
            }.bounds(width / 2 - 155, height - 52, 150, 20)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.friends.copy_my_link.tooltip",
                        ),
                    ),
                )
                .build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.add"),
            ) {
                mode = Mode.ADD
                safeMessage = null
                rebuildWidgets()
            }.bounds(width / 2 + 5, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20)
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
                Component.translatable(
                    "connect_share.friends.send_request",
                ),
            ) {
                createFriendRequest()
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
        if (removeConfirmation) {
            buildRemoveFriendConfirmation(friend)
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
                val activeScope = scope ?: return@builder
                requestOperationInProgress = true
                refresh()
                activeScope.launch {
                    withContext(Dispatchers.IO) {
                        friends.rename(friend.peerId, nameValue)
                        friends.updatePermissions(
                            friend.peerId,
                            FriendPermissions(
                                notifyWhenOnline = notify.selected(),
                                canSeeMyWorlds =
                                    friend.permissions.canSeeMyWorlds,
                                canJoinAutomatically =
                                    autoJoin.selected(),
                            ),
                        )
                    }
                    requestOperationInProgress = false
                    mode = Mode.FRIENDS
                    selectedPeerId = null
                    rebuildWidgets()
                }
            }.bounds(width / 2 - 155, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.remove"),
            ) {
                removeConfirmation = true
                rebuildWidgets()
            }.bounds(width / 2 + 5, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20)
                .build(),
        )
        refresh()
    }

    private fun buildRemoveFriendConfirmation(friend: FriendSummary) {
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.remove_confirm.title",
                    friend.displayName,
                ),
                30,
            ),
        )
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.remove_confirm.message",
                ),
                58,
            ).setMaxWidth(CONTENT_WIDTH),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "connect_share.friends.remove_confirm.confirm",
                ),
            ) {
                val activeScope = scope ?: return@builder
                requestOperationInProgress = true
                refresh()
                activeScope.launch {
                    withContext(Dispatchers.IO) {
                        friends.remove(friend.peerId)
                    }
                    requestOperationInProgress = false
                    removeConfirmation = false
                    mode = Mode.FRIENDS
                    selectedPeerId = null
                    nameValue = ""
                    rebuildWidgets()
                }
            }.bounds(width / 2 - 155, height - 28, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) {
                removeConfirmation = false
                rebuildWidgets()
            }.bounds(width / 2 + 5, height - 28, 150, 20).build(),
        )
    }

    private fun copyMyFriendLink() {
        val activeScope = scope ?: return
        if (friendLinkState == FriendLinkState.COPYING) {
            return
        }
        friendLinkState = FriendLinkState.COPYING
        rebuildWidgets()
        activeScope.launch {
            val invitation = withContext(Dispatchers.IO) {
                ConnectShareClient.friendCardIssuer().issue()
            }
            invitation.fold(
                ifLeft = {
                    friendLinkState = FriendLinkState.FAILED
                },
                ifRight = { link ->
                    minecraft.keyboardHandler.setClipboard(link)
                    friendLinkState = FriendLinkState.COPIED
                },
            )
            rebuildWidgets()
        }
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

    private fun createFriendRequest() {
        val activeScope = scope ?: return
        if (
            requestOperationInProgress ||
            invitationValue.isBlank() ||
            nameValue.isBlank()
        ) {
            return
        }
        requestOperationInProgress = true
        safeMessage = null
        refresh()
        activeScope.launch {
            val peerId = withContext(Dispatchers.IO) {
                friends.sendRequest(
                    invitationValue,
                    nameValue,
                )
            }
            requestOperationInProgress = false
            if (peerId == null) {
                rebuildWidgets()
                return@launch
            }
            mode = Mode.FRIENDS
            invitationValue = ""
            nameValue = ""
            rebuildWidgets()
            deliverOutgoing(peerId)
        }
    }

    private fun deliverOutgoing(peerId: String) {
        val activeScope = scope ?: return
        if (requestJobs[peerId]?.isActive == true) {
            return
        }
        safeMessage = null
        requestStates[peerId] = RequestDeliveryState.SENDING
        rebuildWidgets()
        val job = activeScope.launch {
            val senderCard = withContext(Dispatchers.IO) {
                ConnectShareClient.friendCardIssuer().issue().getOrNull()
            }
            if (senderCard == null) {
                requestFailed(
                    peerId,
                    Component.translatable(
                        "connect_share.friends.request_failed",
                    ).string,
                )
                return@launch
            }
            val targetResult = friends.routeOutgoing(
                peerId = peerId,
                browser = browser,
                authMode = authMode(),
            )
            val target = targetResult.getOrNull()
            if (target == null) {
                requestFailed(
                    peerId,
                    targetResult.leftOrNull()?.safeMessage
                        ?: Component.translatable(
                            "connect_share.friends.request_failed",
                        ).string,
                )
                return@launch
            }
            val displayName = friends.state.value.outgoingRequests
                .firstOrNull { it.peerId == peerId }
                ?.displayName
                ?: peerId
            val result = ConnectShareClient.friendRequestClient().exchange(
                target = target,
                request = FriendControlRequest(
                    requestId = UUID.randomUUID(),
                    displayName = minecraft.user.name,
                    invitation = senderCard,
                ),
                onReceived = {
                    minecraft.execute {
                        requestStates[peerId] =
                            RequestDeliveryState.WAITING
                        rebuildWidgets()
                    }
                },
            )
            val hostCard = result.getOrNull()
            if (hostCard == null) {
                requestFailed(
                    peerId,
                    result.leftOrNull()?.safeMessage
                        ?: Component.translatable(
                            "connect_share.friends.request_failed",
                        ).string,
                )
                return@launch
            }
            val accepted = withContext(Dispatchers.IO) {
                ConnectShareClient.friendCardReceiver().receive(
                    invitation = hostCard,
                    displayName = displayName,
                    authenticatedMinecraftUuid = null,
                )
            }
            if (accepted.isLeft()) {
                requestFailed(
                    peerId,
                    Component.translatable(
                        "connect_share.friends.request_failed",
                    ).string,
                )
                return@launch
            }
            requestStates.remove(peerId)
            friends.reload()
            safeMessage = Component.translatable(
                "connect_share.friends.request_accepted",
                displayName,
            ).string
            rebuildWidgets()
        }
        requestJobs[peerId] = job
        job.invokeOnCompletion {
            minecraft.execute {
                requestJobs.remove(peerId, job)
            }
        }
    }

    private fun cancelOutgoing(peerId: String) {
        val activeScope = scope ?: return
        requestJobs.remove(peerId)?.cancel()
        requestStates[peerId] = RequestDeliveryState.CANCELLING
        rebuildWidgets()
        activeScope.launch {
            withContext(Dispatchers.IO) {
                friends.remove(peerId)
            }
            requestStates.remove(peerId)
            rebuildWidgets()
        }
    }

    private fun requestFailed(
        peerId: String,
        message: String,
    ) {
        requestStates[peerId] = RequestDeliveryState.FAILED
        safeMessage = message
        rebuildWidgets()
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
        val client = minecraft
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
        val state = friends.state.value
        val joiningFriend = state.friends.firstOrNull {
            it.peerId == joiningPeerId
        }
        val data = ServerData(
            joiningFriend?.displayName
                ?: "Connect Share",
            address.toString(),
            ServerData.Type.OTHER,
        )
        val exchangeFriendCard = FriendCardExchangeConsent.shouldArm(
            savedFriendJoin = reciprocalPairing,
            canSeeMyWorlds =
                joiningFriend?.permissions?.canSeeMyWorlds == true,
        )
        if (exchangeFriendCard && joiningPeerId != null) {
            ConnectShareClient.armFriendCardExchange(
                checkNotNull(joiningPeerId),
            )
        }
        ConnectScreen.startConnecting(
            parent,
            client,
            address,
            data,
            false,
            null,
        )
    }

    private fun refresh() {
        val inputReady = invitationValue.isNotBlank()
        primaryButton?.active =
            !joining && !requestOperationInProgress &&
            friendLinkState != FriendLinkState.COPYING &&
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

    private fun outgoingRequestLabel(
        displayName: String,
        deliveryState: RequestDeliveryState?,
    ): Component = Component.translatable(
        if (deliveryState == null) {
            "connect_share.friends.outgoing_request"
        } else {
            "connect_share.friends.outgoing_request_active"
        },
        displayName,
    )

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

    private enum class FriendLinkState(
        val translationKey: String,
    ) {
        IDLE("connect_share.friends.copy_my_link"),
        COPYING("connect_share.friends.copying_my_link"),
        COPIED("connect_share.friends.my_link_copied"),
        FAILED("connect_share.friends.copy_my_link_failed"),
    }

    private enum class RequestDeliveryState(
        val translationKey: String,
    ) {
        SENDING("connect_share.friends.request_sending"),
        WAITING("connect_share.friends.request_waiting"),
        CANCELLING("connect_share.friends.request_cancelling"),
        FAILED("connect_share.friends.retry_request"),
    }

    private companion object {
        const val MAX_INVITATION_LENGTH = 32_768
        const val MAX_VISIBLE_RELATIONSHIPS = 5
        const val CONTENT_WIDTH = 310
    }
}
