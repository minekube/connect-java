package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.AdmissionIdentity
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
        addRenderableWidget(centered(title, 18))

        val sharing = state.shareState as? ShareState.Sharing
        val address = sharing?.address
            ?: Component.translatable(statusKey(state.shareState)).string
        addRenderableWidget(
            centered(
                Component.translatable("connect_share.status.address", address),
                38,
            ),
        )
        val copy = addRenderableWidget(
            Button.builder(Component.translatable("connect_share.status.copy")) {
                sharing?.address?.let(minecraft.keyboardHandler::setClipboard)
            }.bounds(width / 2 - 50, 54, 100, 20).build(),
        )
        copy.active = sharing != null

        addRenderableWidget(
            Button.builder(Component.translatable("connect_share.identity.manage")) {
                minecraft.gui.setScreen(EndpointIdentityScreen(this))
            }.bounds(width / 2 - 100, 80, 200, 20).build(),
        )

        val pending = state.pendingAdmissions
        val visibleRows = ((height - 154) / 38).coerceIn(1, 4)
        pending.take(visibleRows).forEachIndexed { index, request ->
            val y = 108 + index * 38
            val identity = request.identity
            val badge = when (identity) {
                is AdmissionIdentity.Authenticated ->
                    identity.source.name.lowercase()

                is AdmissionIdentity.UnverifiedOffline -> "offline"
            }
            val label = Component.translatable(
                "connect_share.status.request",
                identity.name,
                identity.uuid.toString(),
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
                ).setMaxWidth(202),
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
                    108 + visibleRows * 38,
                ),
            )
        } else if (pending.isEmpty()) {
            addRenderableWidget(
                centered(
                    Component.translatable("connect_share.status.waiting"),
                    116,
                ),
            )
        }

        addRenderableWidget(
            Button.builder(Component.translatable("connect_share.status.stop")) {
                viewModel.stop()
                minecraft.gui.setScreen(parent)
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
        minecraft.gui.setScreen(parent)
    }

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(width / 2 - textWidth / 2, y, textWidth, 9, message, font)
    }

    private fun statusKey(state: ShareState): String = when (state) {
        ShareState.Idle -> "connect_share.status.idle"
        ShareState.Starting -> "connect_share.status.starting"
        is ShareState.Sharing -> "connect_share.status.active"
        ShareState.Stopping -> "connect_share.status.stopping"
        is ShareState.Failed -> "connect_share.status.failed"
    }
}
