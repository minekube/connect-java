package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.FriendCardExchangeConsent
import com.minekube.connect.share.fabric.FriendJoinAttemptFailure
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.FriendActivityMonitor
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.fabric.ui.FriendSummary
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import com.minekube.connect.share.fabric.ui.FriendFormDraft
import com.minekube.connect.share.fabric.ui.FriendPresenceTone
import com.minekube.connect.share.fabric.ui.FriendPrimaryAction
import com.minekube.connect.share.fabric.ui.FriendsOverview
import com.minekube.connect.share.fabric.ui.FriendsSummaryTone
import com.minekube.connect.share.fabric.ui.FriendsScreenLayout
import com.minekube.connect.share.fabric.ui.IncomingFriendRequestSummary
import com.minekube.connect.share.fabric.ui.OutgoingFriendRequestSummary
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.fabric.ui.ShareUiMessage
import com.minekube.connect.share.fabric.ui.uiMessage
import com.minekube.connect.share.fabric.ui.overview
import com.minekube.connect.share.fabric.ui.page
import com.minekube.connect.share.fabric.ui.presentation
import com.minekube.connect.share.fabric.ui.summary
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.FriendAccessPolicy
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
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.CommonComponents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.UUID

class ShareJoinScreen(
    private val parent: Screen,
    private val friends: FriendsViewModel,
    private val browser: FabricShareBrowser,
    private val remotePresence: FriendPresenceMonitor,
    private val friendActivity: FriendActivityMonitor,
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
    private var safeMessage: ShareUiMessage? = null
    private var fingerprint = 0
    private var joining = false
    private var joiningPeerId: String? = null
    private var reciprocalPairing = false
    private var removeConfirmation = false
    private var friendLinkState = FriendLinkState.IDLE
    private var requestOperationInProgress = false
    private var relationshipOffset = 0
    private val requestJobs = mutableMapOf<String, Job>()
    private val requestStates =
        mutableMapOf<String, RequestDeliveryState>()

    override fun init() {
        if (scope == null) {
            scope = CoroutineScope(
                SupervisorJob() + minecraft!!.asCoroutineDispatcher(),
            )
            browser.start().onLeft { safeMessage = it.uiMessage() }
        }
        friends.updatePresence(browser.discovered.value)
        friends.updateRemotePresence(remotePresence.state.value)
        friends.updateActivities(friendActivity.state.value)
        friends.updateIncoming(
            ConnectShareClient.viewModel().state.value.pendingAdmissions,
        )
        fingerprint = currentFingerprint()
        nameBox = null
        invitationBox = null
        primaryButton = null
        secondaryButton = null

        when (mode) {
            Mode.FRIENDS -> buildFriends()
            Mode.ADD -> buildAddFriend()
            Mode.CONNECTION_OPTIONS -> buildConnectionOptions()
            Mode.MANAGE -> buildManageFriend()
        }
    }

    override fun tick() {
        super.tick()
        friends.updatePresence(browser.discovered.value)
        friends.updateRemotePresence(remotePresence.state.value)
        friends.updateActivities(friendActivity.state.value)
        friends.updateIncoming(
            ConnectShareClient.viewModel().state.value.pendingAdmissions,
        )
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
            Mode.FRIENDS -> minecraft!!.setScreen(parent)
            Mode.ADD,
            Mode.CONNECTION_OPTIONS,
            Mode.MANAGE,
            -> {
                val returningToAdd = mode == Mode.CONNECTION_OPTIONS
                mode = if (returningToAdd) {
                    Mode.ADD
                } else {
                    Mode.FRIENDS
                }
                if (!returningToAdd) resetFriendFormDraft()
                selectedPeerId = null
                safeMessage = null
                rebuildWidgets()
            }
        }
    }

    override fun removed() {
        scope?.cancel()
        scope = null
        super.removed()
    }

    private fun buildFriends() {
        val layout = AdaptiveShareLayout.friends(width, height)
        val state = friends.state.value
        val overview = state.overview()
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.title")
                    .withStyle(ChatFormatting.BOLD),
                layout.headerY,
            ),
        )
        addRenderableWidget(
            centered(
                friendsSummary(overview),
                layout.subtitleY,
            ),
        )

        val relationships = buildList {
            state.incomingRequests.forEach {
                add(RelationshipRow.Incoming(it))
            }
            state.outgoingRequests.forEach {
                add(RelationshipRow.Outgoing(it))
            }
            state.friends.forEach { add(RelationshipRow.Friend(it)) }
        }
        val page = relationships.page(
            offset = relationshipOffset,
            size = layout.visibleRows,
        )
        relationshipOffset = page.offset
        if (relationships.isEmpty()) {
            addRenderableWidget(
                centeredWrapped(
                    Component.translatable("connect_share.friends.empty"),
                    layout.rowsTop + 24,
                    layout.contentWidth,
                ),
            )
        }
        page.items.forEachIndexed { index, relationship ->
            val y = layout.rowY(index)
            when (relationship) {
                is RelationshipRow.Incoming ->
                    addIncomingRow(relationship.request, y, layout)

                is RelationshipRow.Outgoing ->
                    addOutgoingRow(relationship.request, y, layout)

                is RelationshipRow.Friend ->
                    addFriendRow(relationship.friend, y, layout)
            }
        }
        if (page.pageCount > 1) {
            val previousTooltip = Tooltip.create(
                Component.translatable(
                    "connect_share.page.previous_tooltip",
                    page.pageNumber,
                    page.pageCount,
                ),
            )
            val nextTooltip = Tooltip.create(
                Component.translatable(
                    "connect_share.page.next_tooltip",
                    page.pageNumber,
                    page.pageCount,
                ),
            )
            val previous = addRenderableWidget(
                Button.builder(Component.literal("‹")) {
                    relationshipOffset = page.previousOffset ?: 0
                    rebuildWidgets()
                }.bounds(layout.contentX, layout.headerY, 24, 20)
                    .createNarration {
                        Component.translatable("connect_share.page.previous")
                    }
                    .tooltip(previousTooltip)
                    .build(),
            )
            previous.active = page.hasPrevious
            val next = addRenderableWidget(
                Button.builder(Component.literal("›")) {
                    relationshipOffset = page.nextOffset ?: page.offset
                    rebuildWidgets()
                }.bounds(
                    layout.contentX + layout.contentWidth - 24,
                    layout.headerY,
                    24,
                    20,
                )
                    .createNarration {
                        Component.translatable("connect_share.page.next")
                    }
                    .tooltip(nextTooltip)
                    .build(),
            )
            next.active = page.hasNext
        }

        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centeredWrapped(
                        message.component()
                            .withStyle(ChatFormatting.YELLOW),
                        layout.messageY,
                        layout.contentWidth,
                    ),
                )
            }
        }
        primaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable(friendLinkState.translationKey),
            ) {
                copyMyFriendLink()
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            )
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
                resetFriendFormDraft()
                mode = Mode.ADD
                safeMessage = null
                rebuildWidgets()
            }.bounds(
                layout.contentX + layout.halfButtonWidth + 6,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.privacy.title"),
            ) {
                minecraft!!.setScreen(SharePrivacyScreen(this))
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                layout.halfButtonWidth,
                20,
            )
                .build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.footerTop + 24,
                    layout.halfButtonWidth,
                    20,
                )
                .build(),
        )
    }

    private fun addIncomingRow(
        request: IncomingFriendRequestSummary,
        y: Int,
        layout: FriendsScreenLayout,
    ) {
        val buttonWidth = 58
        val textWidth = layout.contentWidth - buttonWidth * 2 - 12
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                y,
                textWidth,
                20,
                Component.translatable(
                    if (request.purpose == com.minekube.connect.share.admission.AdmissionPurpose.FRIEND) {
                        "connect_share.friends.incoming_request"
                    } else {
                        "connect_share.friends.incoming_join_request"
                    },
                    request.displayName,
                    friendlyIngress(request.ingress),
                ),
                font,
            ),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.status.allow"),
            ) {
                ConnectShareClient.viewModel().allow(request.requestId)
            }.bounds(
                layout.contentX + layout.contentWidth - buttonWidth * 2 - 4,
                y + 2,
                buttonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.status.deny"),
            ) {
                ConnectShareClient.viewModel().deny(request.requestId)
            }.bounds(
                layout.contentX + layout.contentWidth - buttonWidth,
                y + 2,
                buttonWidth,
                20,
            ).build(),
        )
    }

    private fun addOutgoingRow(
        request: OutgoingFriendRequestSummary,
        y: Int,
        layout: FriendsScreenLayout,
    ) {
        val deliveryState = requestStates[request.peerId]
        val buttonWidth = 58
        val textWidth = layout.contentWidth - buttonWidth * 2 - 12
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                y,
                textWidth,
                20,
                outgoingRequestLabel(request.displayName, deliveryState),
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
            }.bounds(
                layout.contentX + layout.contentWidth - buttonWidth * 2 - 4,
                y + 2,
                buttonWidth,
                20,
            ).build().apply {
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
            }.bounds(
                layout.contentX + layout.contentWidth - buttonWidth,
                y + 2,
                buttonWidth,
                20,
            ).build(),
        )
    }

    private fun addFriendRow(
        friend: FriendSummary,
        y: Int,
        layout: FriendsScreenLayout,
    ) {
        val actionWidth = 104
        val manageWidth = 28
        val textWidth = layout.contentWidth - actionWidth - manageWidth - 12
        val presentation = friend.presentation()
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                y,
                textWidth,
                11,
                Component.literal(friend.displayName)
                    .withStyle(ChatFormatting.WHITE),
                font,
            ),
        )
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                y + 11,
                textWidth,
                11,
                friendStatus(friend).copy()
                    .withStyle(presentation.tone.color()),
                font,
            ),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable(presentation.action.translationKey),
            ) {
                when (presentation.action) {
                    FriendPrimaryAction.ASK_TO_JOIN ->
                        requestToJoin(friend.peerId)
                    FriendPrimaryAction.JOIN_NOW -> joinSaved(friend.peerId)
                    FriendPrimaryAction.CANCEL_FOLLOW ->
                        friends.cancelFollow(friend.peerId)
                    FriendPrimaryAction.JOIN_WHEN_READY ->
                        friends.follow(friend.peerId)
                }
                rebuildWidgets()
            }.bounds(
                layout.contentX + layout.contentWidth - actionWidth - manageWidth - 4,
                y + 2,
                actionWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.literal("…"),
            ) {
                selectedPeerId = friend.peerId
                applyFriendFormDraft(
                    FriendFormDraft.forManage(friend.displayName),
                )
                mode = Mode.MANAGE
                safeMessage = null
                rebuildWidgets()
            }.bounds(
                layout.contentX + layout.contentWidth - manageWidth,
                y + 2,
                manageWidth,
                20,
            ).tooltip(
                Tooltip.create(
                    Component.translatable(
                        "connect_share.friends.manage_named",
                        friend.displayName,
                    ),
                ),
            ).createNarration {
                Component.translatable(
                    "connect_share.friends.manage_named",
                    friend.displayName,
                )
            }.build(),
        )
    }

    private fun buildAddFriend() {
        val layout = AdaptiveShareLayout.form(width, height, 4)
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.friends.add")
                    .withStyle(ChatFormatting.BOLD),
                layout.headerY,
            ),
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable("connect_share.friends.add_description"),
                layout.subtitleY,
                layout.contentWidth,
            ),
        )
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.friends.link"),
                font,
            ),
        )
        invitationBox = addRenderableWidget(
            EditBox(
                font,
                layout.contentX,
                layout.bodyTop + 12,
                layout.contentWidth,
                20,
                Component.translatable("connect_share.join.invitation"),
            ).apply {
                setMaxLength(MAX_INVITATION_LENGTH)
                setHint(Component.translatable("connect_share.join.invitation_hint"))
                setValue(invitationValue)
                setResponder {
                    invitationValue = it
                    friends.suggestedDisplayName(it).getOrNull()
                        ?.let { suggested ->
                            nameValue = suggested
                            nameBox?.setValue(suggested)
                        }
                    refresh()
                }
            },
        )
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                layout.bodyTop + 38,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.friends.name"),
                font,
            ),
        )
        nameBox = addRenderableWidget(
            EditBox(
                font,
                layout.contentX,
                layout.bodyTop + 50,
                layout.contentWidth,
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
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "connect_share.friends.connection_options.show",
                ),
            ) {
                mode = Mode.CONNECTION_OPTIONS
                rebuildWidgets()
            }.bounds(
                layout.contentX,
                layout.bodyTop + 76,
                layout.contentWidth,
                20,
            ).build(),
        )
        safeMessage().let { message ->
            if (message != null) {
                addRenderableWidget(
                    centeredWrapped(
                        message.component().withStyle(ChatFormatting.YELLOW),
                        layout.footerTop - 16,
                        layout.contentWidth,
                    ),
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
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.contentWidth,
                20,
            ).build(),
        )
        secondaryButton = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.join_once"),
            ) {
                joinInvitation()
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    layout.contentX + layout.halfButtonWidth + 6,
                    layout.footerTop + 24,
                    layout.halfButtonWidth,
                    20,
                )
                .build(),
        )
        refresh()
    }

    private fun buildConnectionOptions() {
        val layout = AdaptiveShareLayout.form(width, height, 2)
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.connection_options.title",
                ).withStyle(ChatFormatting.BOLD),
                layout.headerY,
            ),
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable(
                    "connect_share.friends.connection_options.description",
                ),
                layout.subtitleY,
                layout.contentWidth,
            ),
        )
        offlineMode = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.join.offline"),
                font,
            ).pos(layout.contentX, layout.bodyTop)
                .selected(offlineSelected)
                .onValueChange { _, selected -> offlineSelected = selected }
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.join.offline.tooltip",
                        ),
                    ),
                ).build(),
        )
        internetDirect = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.join.internet"),
                font,
            ).pos(layout.contentX, layout.bodyTop + 28)
                .selected(internetSelected)
                .onValueChange { _, selected -> internetSelected = selected }
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.join.internet.tooltip",
                        ),
                    ),
                ).build(),
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable(
                    "connect_share.friends.connection_options.fallback",
                ).withStyle(ChatFormatting.GRAY),
                layout.bodyTop + 64,
                layout.contentWidth,
            ),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(
                    layout.contentX,
                    layout.footerTop + 24,
                    layout.contentWidth,
                    20,
                ).build(),
        )
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
        val layout = AdaptiveShareLayout.form(width, height, 5)
        val form = AdaptiveShareLayout.manageFriendForm(layout.bodyTop)
        var internetDirectSelected = friend.internetDirectGuestOptIn
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.manage_title",
                    friend.displayName,
                ).withStyle(ChatFormatting.BOLD),
                layout.headerY,
            ),
        )
        addRenderableWidget(
            centeredWrapped(
                safeMessage()?.let {
                    it.component().withStyle(ChatFormatting.YELLOW)
                } ?: friendStatus(friend).copy().withStyle(
                    friend.presentation().tone.color(),
                ),
                layout.subtitleY,
                layout.contentWidth,
            ),
        )
        addRenderableWidget(
            StringWidget(
                layout.contentX,
                form.nameLabelY,
                layout.contentWidth,
                11,
                Component.translatable("connect_share.friends.name"),
                font,
            ),
        )
        nameBox = addRenderableWidget(
            EditBox(
                font,
                layout.contentX,
                form.nameInputY,
                layout.contentWidth,
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
            ).pos(layout.contentX, form.notifyY)
                .selected(friend.permissions.notifyWhenOnline)
                .build(),
        )
        var accessPolicy = friend.permissions.accessPolicy
        addRenderableWidget(
            CycleButton.builder(
                { policy: FriendAccessPolicy ->
                    Component.translatable(
                        "connect_share.friends.access.${policy.name.lowercase()}",
                    )
                },
            ).withInitialValue(accessPolicy)
                .withValues(FriendAccessPolicy.entries)
                .create(
                    layout.contentX,
                    form.accessPolicyY,
                    layout.contentWidth,
                    20,
                    Component.translatable("connect_share.friends.access"),
                ) { _, selected -> accessPolicy = selected },
        )
        val shareWorlds = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.friends.share_worlds"),
                font,
            ).pos(layout.contentX, form.shareWorldsY)
                .selected(friend.permissions.canSeeMyWorlds)
                .build(),
        )
        addRenderableWidget(
            CycleButton.onOffBuilder(friend.internetDirectGuestOptIn)
                .create(
                    layout.contentX,
                    form.internetDirectY,
                    layout.contentWidth,
                    20,
                    Component.translatable(
                        "connect_share.friends.internet_direct_short",
                    ),
                ) { _, selected -> internetDirectSelected = selected }
                .apply {
                    setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                "connect_share.friends.internet_direct.tooltip",
                            ),
                        ),
                    )
                },
        )
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
                        friends.updateInternetDirectGuestOptIn(
                            friend.peerId,
                            internetDirectSelected,
                        )
                        friends.updatePermissions(
                            friend.peerId,
                            FriendPermissions(
                                notifyWhenOnline = notify.selected(),
                                canSeeMyWorlds = shareWorlds.selected(),
                                accessPolicy = accessPolicy,
                            ),
                        )
                    }
                    requestOperationInProgress = false
                    mode = Mode.FRIENDS
                    selectedPeerId = null
                    resetFriendFormDraft()
                    rebuildWidgets()
                }
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.remove"),
            ) {
                removeConfirmation = true
                rebuildWidgets()
            }.bounds(
                layout.contentX + layout.halfButtonWidth + 6,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(
                    layout.contentX,
                    layout.footerTop + 24,
                    layout.contentWidth,
                    20,
                )
                .build(),
        )
        refresh()
    }

    private fun buildRemoveFriendConfirmation(friend: FriendSummary) {
        val layout = AdaptiveShareLayout.form(width, height, 1)
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.friends.remove_confirm.title",
                    friend.displayName,
                ).withStyle(ChatFormatting.BOLD),
                layout.headerY + 12,
            ),
        )
        addRenderableWidget(
            centeredWrapped(
                Component.translatable(
                    "connect_share.friends.remove_confirm.message",
                ),
                layout.bodyTop,
                layout.contentWidth,
            ),
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
                    resetFriendFormDraft()
                    rebuildWidgets()
                }
            }.bounds(
                layout.contentX,
                layout.footerTop + 24,
                (layout.contentWidth - 12) / 3,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.friends.block"),
            ) {
                val activeScope = scope ?: return@builder
                requestOperationInProgress = true
                refresh()
                activeScope.launch {
                    withContext(Dispatchers.IO) {
                        friends.block(friend.peerId)
                    }
                    requestOperationInProgress = false
                    removeConfirmation = false
                    mode = Mode.FRIENDS
                    selectedPeerId = null
                    resetFriendFormDraft()
                    rebuildWidgets()
                }
            }.bounds(
                layout.contentX + (layout.contentWidth - 12) / 3 + 6,
                layout.footerTop + 24,
                (layout.contentWidth - 12) / 3,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) {
                removeConfirmation = false
                rebuildWidgets()
            }.bounds(
                layout.contentX + 2 * ((layout.contentWidth - 12) / 3 + 6),
                layout.footerTop + 24,
                (layout.contentWidth - 12) / 3,
                20,
            ).build(),
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
                    minecraft!!.keyboardHandler.setClipboard(link)
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
                ownConnectAddress =
                    ConnectShareClient.connectPublicAddress(),
            ).fold(
                ifLeft = ::joinFailed,
                ifRight = ::connect,
            )
        }
    }

    private fun requestToJoin(
        peerId: String,
        allowModMismatch: Boolean = false,
    ) {
        if (joining) return
        joining = true
        joiningPeerId = peerId
        reciprocalPairing = false
        safeMessage = null
        refresh()
        scope?.launch {
            ConnectShareClient.friendJoinOrchestrator().request(
                peerId,
                FriendJoinRequest(
                    requestId = UUID.randomUUID(),
                    playerName = minecraft!!.user.name,
                    playerUuid = minecraft!!.user.profileId,
                ),
                allowModMismatch = allowModMismatch,
            ).fold(
                ifLeft = { failure ->
                    joining = false
                    if (failure is FriendJoinAttemptFailure.Compatibility) {
                        minecraft!!.setScreen(
                            CompatibilityMismatchScreen(
                                parent = this@ShareJoinScreen,
                                failure = failure,
                                tryAnyway = {
                                    requestToJoin(peerId, true)
                                },
                            ),
                        )
                    } else {
                        safeMessage = failure.uiMessage()
                        rebuildWidgets()
                    }
                },
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
                    internetDirectGuestOptIn = internetSelected,
                )
            }
            requestOperationInProgress = false
            if (peerId == null) {
                rebuildWidgets()
                return@launch
            }
            mode = Mode.FRIENDS
            resetFriendFormDraft()
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
                    ShareUiMessage("connect_share.friends.request_failed"),
                )
                return@launch
            }
            val targetResult = friends.routeOutgoing(
                peerId = peerId,
                browser = browser,
                authMode = DirectP2pAuthMode.OFFLINE,
            )
            val target = targetResult.getOrNull()
            if (target == null) {
                requestFailed(
                    peerId,
                    targetResult.leftOrNull()?.uiMessage()
                        ?: ShareUiMessage(
                            "connect_share.friends.request_failed",
                        ),
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
                    relationshipId = friends.state.value.outgoingRequests
                        .firstOrNull { it.peerId == peerId }
                        ?.relationshipId
                        ?: run {
                            target.close()
                            requestFailed(
                                peerId,
                                ShareUiMessage(
                                    "connect_share.friends.request_failed",
                                ),
                            )
                            return@launch
                        },
                    displayName = minecraft!!.user.name,
                    invitation = senderCard,
                ),
                onReceived = {
                    minecraft!!.execute {
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
                    result.leftOrNull()?.uiMessage()
                        ?: ShareUiMessage(
                            "connect_share.friends.request_failed",
                        ),
                )
                return@launch
            }
            val accepted = withContext(Dispatchers.IO) {
                ConnectShareClient.friendCardReceiver().receive(
                    invitation = hostCard,
                    displayName = displayName,
                    authenticatedMinecraftUuid = null,
                    relationshipId = friends.state.value.outgoingRequests
                        .firstOrNull { it.peerId == peerId }
                        ?.relationshipId,
                )
            }
            if (accepted.isLeft()) {
                requestFailed(
                    peerId,
                    ShareUiMessage("connect_share.friends.request_failed"),
                )
                return@launch
            }
            requestStates.remove(peerId)
            friends.reload()
            safeMessage = ShareUiMessage(
                "connect_share.friends.request_accepted",
                listOf(displayName),
            )
            rebuildWidgets()
        }
        requestJobs[peerId] = job
        job.invokeOnCompletion {
            minecraft!!.execute {
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
        message: ShareUiMessage,
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
        safeMessage = failure.uiMessage()
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
            ConnectShareClient.holdGuestDirect(target)
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
            checkNotNull(minecraft),
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
                Mode.CONNECTION_OPTIONS -> true
                Mode.MANAGE -> nameValue.isNotBlank()
                Mode.FRIENDS -> true
            }
        secondaryButton?.active = !joining && inputReady
        invitationBox?.setEditable(!joining)
        nameBox?.setEditable(!joining)
    }

    private fun resetFriendFormDraft() {
        applyFriendFormDraft(currentFriendFormDraft().newRequest())
    }

    private fun currentFriendFormDraft(): FriendFormDraft =
        FriendFormDraft(
            displayName = nameValue,
            invitation = invitationValue,
            offlineMode = offlineSelected,
            internetDirect = internetSelected,
        )

    private fun applyFriendFormDraft(draft: FriendFormDraft) {
        nameValue = draft.displayName
        invitationValue = draft.invitation
        offlineSelected = draft.offlineMode
        internetSelected = draft.internetDirect
    }

    private fun friendsSummary(overview: FriendsOverview): Component =
        overview.summary().let { presentation ->
            Component.translatable(
                presentation.translationKey,
                *listOfNotNull(presentation.count).toTypedArray(),
            ).withStyle(
                when (presentation.tone) {
                    FriendsSummaryTone.ATTENTION -> ChatFormatting.YELLOW
                    FriendsSummaryTone.READY -> ChatFormatting.GREEN
                    FriendsSummaryTone.ONLINE -> ChatFormatting.AQUA
                    FriendsSummaryTone.MUTED -> ChatFormatting.GRAY
                },
            )
        }

    private fun friendStatus(friend: FriendSummary): Component =
        friend.presentation().let { presentation ->
            Component.translatable(
                presentation.statusKey,
                *presentation.statusArguments.toTypedArray(),
            )
        }

    private fun FriendPresenceTone.color(): ChatFormatting = when (this) {
        FriendPresenceTone.JOINABLE -> ChatFormatting.GREEN
        FriendPresenceTone.ONLINE -> ChatFormatting.AQUA
        FriendPresenceTone.SAVED -> ChatFormatting.GRAY
        FriendPresenceTone.OFFLINE -> ChatFormatting.DARK_GRAY
    }

    private fun friendlyIngress(ingress: Ingress): Component =
        Component.translatable(
            when (ingress) {
                Ingress.CONNECT -> "connect_share.ingress.online"
                Ingress.DIRECT_LAN -> "connect_share.ingress.nearby"
                Ingress.DIRECT_INTERNET -> "connect_share.ingress.direct"
            },
        )

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

    private fun safeMessage(): ShareUiMessage? =
        safeMessage ?: friends.state.value.safeMessage

    private fun ShareUiMessage.component() =
        Component.translatable(translationKey, *arguments.toTypedArray())

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

    private fun centeredWrapped(
        message: Component,
        y: Int,
        contentWidth: Int = CONTENT_WIDTH,
    ): MultiLineTextWidget =
        MultiLineTextWidget(
            width / 2 - contentWidth / 2,
            y,
            message,
            font,
        ).setMaxWidth(contentWidth).setCentered(true)

    private enum class Mode {
        FRIENDS,
        ADD,
        CONNECTION_OPTIONS,
        MANAGE,
    }

    private sealed interface RelationshipRow {
        data class Incoming(
            val request: IncomingFriendRequestSummary,
        ) : RelationshipRow

        data class Outgoing(
            val request: OutgoingFriendRequestSummary,
        ) : RelationshipRow

        data class Friend(
            val friend: FriendSummary,
        ) : RelationshipRow
    }

    private enum class FriendLinkState(
        val translationKey: String,
    ) {
        IDLE("connect_share.friends.invite"),
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
