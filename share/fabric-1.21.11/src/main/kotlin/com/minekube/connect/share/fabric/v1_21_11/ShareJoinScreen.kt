package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.DiscoveredLanShare
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.GuestJoinTarget
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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareJoinScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.join.title")) {
    private val browser = FabricShareBrowser()
    private var scope: CoroutineScope? = null
    private var invitationBox: EditBox? = null
    private var offlineMode: Checkbox? = null
    private var internetDirect: Checkbox? = null
    private var joinButton: Button? = null
    private var invitationValue = ""
    private var selectedLanAddress: String? = null
    private var safeMessage: String? = null
    private var discoveredFingerprint = 0
    private var joining = false
    private var transferred = false
    private var selectingDiscovered = false

    override fun init() {
        if (scope == null) {
            scope = CoroutineScope(
                SupervisorJob() + minecraft!!.asCoroutineDispatcher(),
            )
            browser.start().onLeft { safeMessage = it.safeMessage }
        }
        discoveredFingerprint = browser.discovered.value.hashCode()

        addRenderableWidget(centered(title, 16))
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.join.description"),
                34,
            ),
        )
        invitationBox = addRenderableWidget(
            EditBox(
                font,
                width / 2 - 155,
                52,
                310,
                20,
                Component.translatable("connect_share.join.invitation"),
            ).apply {
                setMaxLength(MAX_INVITATION_LENGTH)
                setHint(Component.translatable("connect_share.join.invitation_hint"))
                setValue(invitationValue)
                setResponder { value ->
                    invitationValue = value
                    if (!selectingDiscovered) {
                        selectedLanAddress = null
                    }
                    refresh()
                }
            },
        )

        val discovered = browser.discovered.value.take(MAX_VISIBLE_SHARES)
        if (discovered.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.join.scanning"),
                    88,
                ),
            )
        } else {
            discovered.forEachIndexed { index, share ->
                addRenderableWidget(
                    Button.builder(discoveredLabel(share)) {
                        selectDiscovered(share)
                    }.bounds(width / 2 - 155, 80 + index * 24, 310, 20)
                        .build(),
                )
            }
        }

        offlineMode = addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.join.offline"),
                font,
            ).pos(width / 2 - 155, 134)
                .selected(offlineMode?.selected() ?: false)
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
            ).pos(width / 2 - 155, 156)
                .selected(internetDirect?.selected() ?: false)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "connect_share.join.internet.tooltip",
                        ),
                    ),
                )
                .build(),
        )

        safeMessage?.let {
            addRenderableWidget(
                centered(Component.literal(it), 182).setMaxWidth(310),
            )
        }
        joinButton = addRenderableWidget(
            Button.builder(Component.translatable("connect_share.join.join")) {
                join()
            }.bounds(width / 2 - 155, height - 28, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 5, height - 28, 150, 20)
                .build(),
        )
        refresh()
    }

    override fun tick() {
        super.tick()
        val next = browser.discovered.value.hashCode()
        if (next != discoveredFingerprint) {
            invitationValue = invitationBox?.value.orEmpty()
            rebuildWidgets()
        } else {
            refresh()
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    override fun removed() {
        scope?.cancel()
        scope = null
        if (!transferred) {
            browser.close()
        }
        super.removed()
    }

    private fun selectDiscovered(share: DiscoveredLanShare) {
        selectedLanAddress = share.lanAddress
        invitationValue = share.invitationUri
        selectingDiscovered = true
        invitationBox?.value = invitationValue
        selectingDiscovered = false
        safeMessage = null
        refresh()
    }

    private fun join() {
        if (joining || invitationValue.isBlank()) return
        joining = true
        safeMessage = null
        refresh()
        scope?.launch {
            browser.join(
                invitationUri = invitationValue,
                lanAddress = selectedLanAddress,
                internetOptIn = internetDirect?.selected() == true,
                authMode = if (offlineMode?.selected() == true) {
                    DirectP2pAuthMode.OFFLINE
                } else {
                    DirectP2pAuthMode.ONLINE
                },
            ).fold(
                ifLeft = { failure ->
                    joining = false
                    safeMessage = failure.safeMessage
                    rebuildWidgets()
                },
                ifRight = ::connect,
            )
        }
    }

    private fun connect(target: GuestJoinTarget) {
        val client = minecraft ?: run {
            target.close()
            joining = false
            return
        }
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
        val data = ServerData(
            "Connect Share",
            address.toString(),
            ServerData.Type.OTHER,
        )
        ConnectScreen.startConnecting(parent, client, address, data, false, null)
    }

    private fun refresh() {
        joinButton?.active = !joining && invitationValue.isNotBlank()
        invitationBox?.setEditable(!joining)
    }

    private fun discoveredLabel(share: DiscoveredLanShare): Component =
        Component.translatable(
            "connect_share.join.discovered",
            share.displayName,
        )

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

    private companion object {
        const val MAX_INVITATION_LENGTH = 32_768
        const val MAX_VISIBLE_SHARES = 2
    }
}
