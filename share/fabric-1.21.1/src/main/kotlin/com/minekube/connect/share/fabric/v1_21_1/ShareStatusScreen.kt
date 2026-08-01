package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.fabric.ConnectShareClient
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class ShareStatusScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.status.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var fingerprint: Int = 0

    override fun init() {
        val state = viewModel.state.value
        fingerprint = state.hashCode()
        addRenderableWidget(centered(title, 14))

        val sharing = state.shareState as? ShareState.Sharing
        val publicAddress = sharing?.address
        val summary = when {
            publicAddress != null ->
                Component.translatable(
                    "connect_share.status.address",
                    publicAddress,
                )

            sharing != null ->
                Component.translatable("connect_share.status.direct_only")

            else -> Component.translatable(statusKey(state.shareState))
        }
        addRenderableWidget(
            centered(summary, 32),
        )
        val copyInvitation = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.status.copy_invitation"),
            ) {
                viewModel.currentInvitation()?.let(
                    minecraft!!.keyboardHandler::setClipboard,
                )
            }.bounds(width / 2 - 155, 50, 150, 20).build(),
        )
        copyInvitation.active = viewModel.currentInvitation() != null
        val copyAddress = addRenderableWidget(
            Button.builder(
                Component.translatable("connect_share.status.copy_address"),
            ) {
                sharing?.address?.let(minecraft!!.keyboardHandler::setClipboard)
            }.bounds(width / 2 + 5, 50, 150, 20).build(),
        )
        copyAddress.active = sharing?.address != null

        if (sharing != null) {
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.status.link_help",
                    ),
                    78,
                ),
            )
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.status.connection_help",
                    ),
                    94,
                ),
            )
        }

        addRenderableWidget(
            Button.builder(Component.translatable("connect_share.privacy.title")) {
                minecraft!!.setScreen(SharePrivacyScreen(this))
            }.bounds(width / 2 - 155, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.translatable("connect_share.identity.manage")) {
                minecraft!!.setScreen(EndpointIdentityScreen(this))
            }.bounds(width / 2 + 5, height - 52, 150, 20).build(),
        )

        val pending = state.pendingAdmissions
        addRenderableWidget(
            centered(
                Component.translatable(
                    "connect_share.status.requests",
                ),
                110,
            ),
        )
        val visibleRows = ((height - 174) / 26).coerceIn(1, 2)
        pending.take(visibleRows).forEachIndexed { index, request ->
            val y = 124 + index * 26
            val identity = request.identity
            val badge = when (identity) {
                is AdmissionIdentity.Authenticated -> listOfNotNull(
                    identity.source.name.lowercase(),
                    identity.ingress.takeUnless { it == Ingress.CONNECT }
                        ?.displayName(),
                ).joinToString(" · ")

                is AdmissionIdentity.UnverifiedOffline ->
                    "offline · ${identity.ingress.displayName()}"
            }
            val label = Component.translatable(
                if (request.purpose == AdmissionPurpose.FRIEND) {
                    "connect_share.status.friend_request"
                } else {
                    "connect_share.status.request"
                },
                identity.name,
                badge,
            )
            addRenderableWidget(
                StringWidget(
                    width / 2 - 155,
                    y,
                    202,
                    20,
                    label,
                    font,
                ),
            )
            addRenderableWidget(
                Button.builder(Component.translatable("connect_share.status.allow")) {
                    viewModel.allow(request.requestId)
                }.bounds(width / 2 + 51, y, 50, 20).build(),
            )
            addRenderableWidget(
                Button.builder(Component.translatable("connect_share.status.deny")) {
                    viewModel.deny(request.requestId)
                }.bounds(width / 2 + 105, y, 50, 20).build(),
            )
        }
        if (pending.size > visibleRows) {
            addRenderableWidget(
                centered(
                    Component.translatable(
                        "connect_share.status.more",
                        pending.size - visibleRows,
                    ),
                    124 + visibleRows * 26,
                ),
            )
        } else if (pending.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.status.waiting"),
                    128,
                ),
            )
        }

        addRenderableWidget(
            Button.builder(Component.translatable("connect_share.status.stop")) {
                viewModel.stop()
                minecraft!!.setScreen(parent)
            }.bounds(width / 2 - 155, height - 28, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK) { onClose() }
                .bounds(width / 2 + 5, height - 28, 150, 20)
                .build(),
        )
    }

    override fun tick() {
        super.tick()
        val next = viewModel.state.value.hashCode()
        if (next != fingerprint) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(width / 2 - textWidth / 2, y, textWidth, 9, message, font)
    }

    private fun Ingress.displayName(): String = when (this) {
        Ingress.CONNECT -> "connect"
        Ingress.DIRECT_LAN -> "lan"
        Ingress.DIRECT_INTERNET -> "internet"
    }

    private fun statusKey(state: ShareState): String = when (state) {
        ShareState.Idle -> "connect_share.status.idle"
        ShareState.Starting -> "connect_share.status.starting"
        is ShareState.Sharing -> "connect_share.status.active"
        ShareState.Stopping -> "connect_share.status.stopping"
        is ShareState.Failed -> "connect_share.status.failed"
    }

    private companion object {
        const val CONTENT_WIDTH = 310
    }
}
